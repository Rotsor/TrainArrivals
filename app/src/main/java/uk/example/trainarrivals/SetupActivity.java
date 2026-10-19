package uk.example.trainarrivals;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Three-step setup: pick a line, a station on it, then which destinations to show. */
public class SetupActivity extends Activity {

    private static final int STEP_LINE = 0, STEP_STATION = 1, STEP_DEST = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView stepLabel, title, hint;
    private EditText search;
    private ListView listView;
    private ProgressBar progress;
    private Button primary;

    private int step = STEP_LINE;
    private int requestSeq = 0;
    private Tfl.Item line, station;
    private ArrayAdapter<Tfl.Item> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Tfl.appKey = Prefs.loadApiKey(this);
        buildUi();
        showLines();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (step == STEP_DEST) showStations();
        else if (step == STEP_STATION) showLines();
        else if (Prefs.load(this).isConfigured()) {
            // Came here via "Change": back out to the existing setup unchanged.
            startActivity(new Intent(this, MainActivity.class));
            finish();
        } else super.onBackPressed();
    }

    // ------------------------------------------------------------------ steps

    private void showLines() {
        step = STEP_LINE;
        setHeader("Step 1 of 3", "Choose a line", null);
        search.setVisibility(View.GONE);
        primary.setVisibility(View.GONE);
        load(Tfl::lines, items -> setSingleChoice(items, item -> {
            line = item;
            showStations();
        }));
    }

    private void showStations() {
        step = STEP_STATION;
        setHeader("Step 2 of 3", "Choose your station", line.name);
        search.setText("");
        search.setVisibility(View.VISIBLE);
        primary.setVisibility(View.GONE);
        final String lineId = line.id;
        load(() -> Tfl.stations(lineId), items -> setSingleChoice(items, item -> {
            station = item;
            showDestinations();
        }));
    }

    private void showDestinations() {
        step = STEP_DEST;
        setHeader("Step 3 of 3", "Which trains?",
                "Tick the destinations of the trains you want, usually the end of the line "
                        + "in your direction. Leave all unticked to see every train.");
        search.setVisibility(View.GONE);
        primary.setVisibility(View.GONE);
        final String lineId = line.id, stopId = station.id;
        load(() -> Tfl.destinations(lineId, stopId), items -> {
            adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_multiple_choice, new ArrayList<>(items));
            listView.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE);
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(null);
            primary.setVisibility(View.VISIBLE);
        });
    }

    private void save() {
        Prefs p = new Prefs();
        p.lineId = line.id;
        p.lineName = line.name;
        p.stopId = station.id;
        p.stopName = station.name;
        SparseBooleanArray checked = listView.getCheckedItemPositions();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (checked != null && checked.get(i)) {
                Tfl.Item d = adapter.getItem(i);
                p.destNames.add(d.name);
                if (!d.id.isEmpty()) p.destIds.add(d.id);
            }
        }
        p.save(this);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    // ---------------------------------------------------------------- helpers

    private void setSingleChoice(List<Tfl.Item> items, Consumer<Tfl.Item> onPick) {
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(items));
        listView.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                onPick.accept(adapter.getItem(position)));
        if (search.getVisibility() == View.VISIBLE && search.length() > 0) {
            adapter.getFilter().filter(search.getText());
        }
    }

    private <T> void load(Callable<T> task, Consumer<T> onDone) {
        final int seq = ++requestSeq;
        listView.setAdapter(null);
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            T result = null;
            Exception error = null;
            try {
                result = task.call();
            } catch (Exception e) {
                error = e;
            }
            final T r = result;
            final Exception err = error;
            main.post(() -> {
                if (seq != requestSeq || isFinishing()) return;
                progress.setVisibility(View.GONE);
                if (err != null) {
                    hint.setVisibility(View.VISIBLE);
                    hint.setText("Couldn't reach TfL (" + err.getClass().getSimpleName()
                            + "). Check your connection, then tap Retry.");
                    primary.setText("Retry");
                    primary.setVisibility(View.VISIBLE);
                    primary.setOnClickListener(v -> retry());
                } else {
                    primary.setText("Save");
                    primary.setOnClickListener(v -> save());
                    onDone.accept(r);
                }
            });
        });
    }

    private void retry() {
        if (step == STEP_LINE) showLines();
        else if (step == STEP_STATION) showStations();
        else showDestinations();
    }

    private void setHeader(String stepText, String titleText, String hintText) {
        stepLabel.setText(stepText);
        title.setText(titleText);
        hint.setText(hintText == null ? "" : hintText);
        hint.setVisibility(hintText == null ? View.GONE : View.VISIBLE);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(getColor(R.color.purple));
        header.setPadding(dp(20), dp(20), dp(20), dp(16));
        stepLabel = text(12, true);
        stepLabel.setTextColor(0xFFD9CFF0);
        title = text(24, true);
        title.setTextColor(0xFFFFFFFF);
        header.addView(stepLabel);
        header.addView(title);
        root.addView(header);

        hint = text(14, false);
        hint.setTextColor(getColor(R.color.muted));
        hint.setPadding(dp(20), dp(12), dp(20), 0);
        root.addView(hint);

        search = new EditText(this);
        search.setHint("Search stations");
        search.setSingleLine(true);
        search.setTextColor(getColor(R.color.text));
        search.setHintTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(16), dp(8), dp(16), 0);
        search.setLayoutParams(slp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (adapter != null && step == STEP_STATION) adapter.getFilter().filter(s);
            }
        });
        root.addView(search);

        progress = new ProgressBar(this);
        progress.setPadding(0, dp(24), 0, dp(24));
        root.addView(progress);

        listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(listView);

        primary = new Button(this);
        primary.setText("Save");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(16), dp(8), dp(16), dp(16));
        primary.setLayoutParams(blp);
        root.addView(primary);

        setContentView(root);
    }

    private TextView text(int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
