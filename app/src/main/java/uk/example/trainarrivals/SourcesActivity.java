package uk.example.trainarrivals;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Data sources" panel: what each feed is, how to get the optional keys,
 * and fields to enter them. Keys are stored only on this phone.
 */
public class SourcesActivity extends Activity {

    private EditText tflKey, nrKey, nrCrs;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(getColor(R.color.purple));
        header.setPadding(dp(20), dp(20), dp(20), dp(16));
        TextView title = text("Data sources", 24, 0xFFFFFFFF, true);
        TextView sub = text("Both keys are optional and free. They're stored only on this phone.",
                14, 0xFFD9CFF0, false);
        header.addView(title);
        header.addView(sub);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll);

        // ---------------------------------------------------------------- TfL
        section("TfL (always used)");
        para("Every station uses TfL's free live data. Elizabeth line and Overground "
                + "stations use two TfL feeds: the departure board (timetable, delays, "
                + "cancellations) and the arrivals feed (live predictions, where each train is). "
                + "The app compares them.");
        para("Without a key TfL allows only a few requests, shared with everyone on the same "
                + "network, so you may see \"TfL asked to wait\". A free key fixes that.");
        steps("How to get a TfL key:",
                "Open api-portal.tfl.gov.uk and sign up (free).",
                "Go to Products and subscribe to the free product (it's called something like "
                        + "\"500 Requests per min\").",
                "Open your Profile: the Primary key there is your key. Paste it below.");
        link("Open the TfL API portal", "https://api-portal.tfl.gov.uk/");
        tflKey = field("TfL API key", Prefs.loadApiKey(this), false);

        // ------------------------------------------------------- National Rail
        section("National Rail (optional)");
        para("National Rail's departure board is where cancellations come from originally. "
                + "If you add it, the app checks it against TfL and marks any train where they "
                + "disagree. If TfL's departure board fails, National Rail is shown instead. "
                + "It's only used for Elizabeth line and Overground stations.");
        steps("How to get a National Rail key:",
                "Open raildata.org.uk (the Rail Data Marketplace) and register (free).",
                "Search the catalogue for \"Live Departure Board\" and subscribe to it.",
                "Open the subscription's details: the \"Consumer key\" is your key. "
                        + "Paste it below.");
        link("Open the Rail Data Marketplace", "https://raildata.org.uk/");
        nrKey = field("National Rail consumer key", Prefs.loadNrKey(this), false);

        para("National Rail identifies stations by a 3-letter code (CRS), e.g. KGX for "
                + "King's Cross. You'll find it on your station's page on nationalrail.co.uk.");
        link("Find a station code", "https://www.nationalrail.co.uk/stations/");
        nrCrs = field("Station code (3 letters)", Prefs.loadNrCrs(this), true);

        Button save = new Button(this);
        save.setText("Save");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(16), dp(8), dp(16), dp(16));
        save.setLayoutParams(blp);
        save.setOnClickListener(v -> save());
        root.addView(save);

        setContentView(root);
    }

    private void save() {
        String crs = nrCrs.getText().toString().trim().toUpperCase(java.util.Locale.UK);
        String nr = nrKey.getText().toString().trim();
        if (!crs.isEmpty() && !crs.matches("[A-Z]{3}")) {
            nrCrs.setError("Must be 3 letters, e.g. KGX");
            return;
        }
        if (!nr.isEmpty() && crs.isEmpty()) {
            nrCrs.setError("Needed for National Rail");
            return;
        }
        Prefs.saveApiKey(this, tflKey.getText().toString());
        Prefs.saveNationalRail(this, nr, crs);
        Prefs.applyKeys(this);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------------------------------------------------------------- helpers

    private void section(String s) {
        TextView tv = text(s, 18, getColor(R.color.text), true);
        tv.setPadding(0, dp(20), 0, dp(4));
        content.addView(tv);
    }

    private void para(String s) {
        TextView tv = text(s, 14, getColor(R.color.muted), false);
        tv.setPadding(0, dp(4), 0, dp(4));
        content.addView(tv);
    }

    private void steps(String heading, String... items) {
        TextView h = text(heading, 14, getColor(R.color.text), true);
        h.setPadding(0, dp(8), 0, dp(2));
        content.addView(h);
        for (int i = 0; i < items.length; i++) {
            TextView tv = text((i + 1) + ".  " + items[i], 14, getColor(R.color.text), false);
            tv.setPadding(dp(8), dp(2), 0, dp(2));
            content.addView(tv);
        }
    }

    private void link(String label, String url) {
        TextView tv = text(label + "  ›", 14, getColor(R.color.amber), true);
        tv.setPadding(0, dp(8), 0, dp(8));
        tv.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, url, Toast.LENGTH_LONG).show();
            }
        });
        content.addView(tv);
    }

    private EditText field(String hint, String value, boolean shortCode) {
        TextView label = text(hint, 12, getColor(R.color.muted), true);
        label.setPadding(0, dp(8), 0, 0);
        content.addView(label);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextColor(getColor(R.color.text));
        e.setHintTextColor(getColor(R.color.muted));
        e.setHint(shortCode ? "e.g. KGX" : "Paste key here");
        e.setTypeface(Typeface.MONOSPACE);
        e.setInputType(shortCode
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (shortCode) e.setGravity(Gravity.START);
        content.addView(e);
        return e;
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
