package uk.example.trainarrivals;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live departures for the line, station and destinations chosen in setup. */
public class MainActivity extends Activity {

    private static final long FETCH_EVERY_MS = 30_000;
    private static final long TICK_EVERY_MS = 5_000;

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(LONDON);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(LONDON);

    /** Official TfL line colours; anything else falls back to Elizabeth purple. */
    private static final Map<String, String> LINE_COLOURS = new HashMap<>();
    static {
        LINE_COLOURS.put("bakerloo", "#B36305");
        LINE_COLOURS.put("central", "#E32017");
        LINE_COLOURS.put("circle", "#FFD300");
        LINE_COLOURS.put("district", "#00782A");
        LINE_COLOURS.put("hammersmith-city", "#F3A9BB");
        LINE_COLOURS.put("jubilee", "#A0A5A9");
        LINE_COLOURS.put("metropolitan", "#9B0056");
        LINE_COLOURS.put("northern", "#000000");
        LINE_COLOURS.put("piccadilly", "#003688");
        LINE_COLOURS.put("victoria", "#0098D4");
        LINE_COLOURS.put("waterloo-city", "#95CDBA");
        LINE_COLOURS.put("elizabeth", "#6950A1");
        LINE_COLOURS.put("dlr", "#00A4A7");
        LINE_COLOURS.put("tram", "#5FB526");
        LINE_COLOURS.put("liberty", "#676767");
        LINE_COLOURS.put("lioness", "#E5A229");
        LINE_COLOURS.put("mildmay", "#3A75B8");
        LINE_COLOURS.put("suffragette", "#4DA34B");
        LINE_COLOURS.put("weaver", "#87344F");
        LINE_COLOURS.put("windrush", "#E2231A");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private int lineColour;
    private LinearLayout list;
    private TextView status;

    private List<Tfl.Arrival> trains = new ArrayList<>();
    private Instant lastUpdated;
    private String lastError;
    private boolean loading;

    private final Runnable fetchLoop = new Runnable() {
        @Override public void run() {
            fetch();
            main.postDelayed(this, FETCH_EVERY_MS);
        }
    };

    private final Runnable tickLoop = new Runnable() {
        @Override public void run() {
            render();
            main.postDelayed(this, TICK_EVERY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.load(this);
        if (!prefs.isConfigured()) {
            openSetup();
            return;
        }

        setContentView(R.layout.activity_main);
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        Button refresh = findViewById(R.id.refresh);
        refresh.setOnClickListener(v -> fetch());
        Button change = findViewById(R.id.change);
        change.setOnClickListener(v -> openSetup());

        lineColour = Color.parseColor(LINE_COLOURS.getOrDefault(prefs.lineId, "#6950A1"));
        int onColour = isLight(lineColour) ? 0xFF111111 : 0xFFFFFFFF;
        findViewById(R.id.header).setBackgroundColor(lineColour);
        TextView lineName = findViewById(R.id.lineName);
        TextView stationName = findViewById(R.id.stationName);
        TextView direction = findViewById(R.id.direction);
        lineName.setText(prefs.lineName.toUpperCase(Locale.UK));
        stationName.setText(prefs.stopName);
        direction.setText(prefs.directionLabel());
        for (TextView tv : new TextView[]{lineName, stationName, direction, change}) {
            tv.setTextColor(onColour);
        }
        getWindow().setStatusBarColor(lineColour);
    }

    private void openSetup() {
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list == null) return;
        main.post(fetchLoop);
        main.postDelayed(tickLoop, TICK_EVERY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(fetchLoop);
        main.removeCallbacks(tickLoop);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---------------------------------------------------------------- network

    private void fetch() {
        if (loading) return;
        loading = true;
        render();
        final String stopId = prefs.stopId, lineId = prefs.lineId;
        io.execute(() -> {
            List<Tfl.Arrival> result = null;
            String error = null;
            try {
                result = new ArrayList<>();
                for (Tfl.Arrival a : Tfl.arrivals(stopId, lineId)) {
                    if (prefs.matches(a)) result.add(a);
                }
            } catch (Exception e) {
                result = null;
                error = "Couldn't load times (" + e.getClass().getSimpleName() + ")";
            }
            final List<Tfl.Arrival> r = result;
            final String err = error;
            main.post(() -> {
                loading = false;
                if (r != null) {
                    trains = r;
                    lastUpdated = Instant.now();
                    lastError = null;
                } else {
                    lastError = err;
                }
                render();
            });
        });
    }

    // --------------------------------------------------------------------- UI

    private void render() {
        if (list == null) return;
        Instant now = Instant.now();

        StringBuilder s = new StringBuilder();
        if (loading) s.append("Updating… ");
        if (lastError != null) s.append(lastError).append(". ");
        if (lastUpdated != null) s.append("Updated ").append(HHMMSS.format(lastUpdated));
        else if (!loading && lastError == null) s.append("No data yet");
        status.setText(s.toString().trim());

        list.removeAllViews();

        List<Tfl.Arrival> upcoming = new ArrayList<>();
        for (Tfl.Arrival t : trains) {
            if (t.expected.isAfter(now.minusSeconds(30))) upcoming.add(t);
        }

        if (upcoming.isEmpty() && lastUpdated != null) {
            TextView empty = text("No matching trains in the next ~30 minutes.\n"
                    + "Late at night the service may have finished.", 15, getColor(R.color.muted), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(40), dp(16), dp(16));
            list.addView(empty);
            return;
        }

        for (int i = 0; i < upcoming.size(); i++) {
            list.addView(row(upcoming.get(i), now, i == 0));
        }
    }

    private LinearLayout row(Tfl.Arrival t, Instant now, boolean first) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setCornerRadius(dp(12));
        if (first) bg.setStroke(dp(2), lineColour == 0xFF000000 ? 0xFF888888 : lineColour);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(text(HHMM.format(t.expected) + "  " + t.destination, 18, getColor(R.color.text), true));

        StringBuilder sub = new StringBuilder();
        if (!t.platform.isEmpty()) sub.append(t.platform);
        if (!t.currentLocation.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(t.currentLocation);
        }
        if (sub.length() > 0) left.addView(text(sub.toString(), 13, getColor(R.color.muted), false));

        long secs = Duration.between(now, t.expected).getSeconds();
        boolean due = secs < 60;
        TextView right = text(due ? "Due" : (secs / 60) + " min", first ? 26 : 22,
                getColor(due ? R.color.amber : R.color.text), true);
        right.setGravity(Gravity.END);

        card.addView(left);
        card.addView(right);
        return card;
    }

    private static boolean isLight(int c) {
        double l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return l > 170;
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(colour);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
