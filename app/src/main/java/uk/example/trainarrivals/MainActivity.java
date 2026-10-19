package uk.example.trainarrivals;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import android.graphics.Color;
import android.graphics.Paint;
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
    /** Minimum gap between manual refreshes: each one is two TfL requests. */
    private static final long MIN_MANUAL_GAP_MS = 10_000;

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
    private String sourceNote = "";
    private boolean loading;
    private Tfl.Board lastBoard;
    private Instant lastBoardAt;
    private TextView filtered;
    /** Until when TfL asked us not to call (HTTP 429). */
    private Instant backoffUntil;
    private Instant lastFetchStart;

    private final Runnable retryAfterBackoff = this::fetch;

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
        Prefs.applyKeys(this);
        if (!prefs.isConfigured()) {
            openSetup();
            return;
        }

        setContentView(R.layout.activity_main);
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        filtered = findViewById(R.id.filtered);
        filtered.setOnClickListener(v -> showDetails());
        Button refresh = findViewById(R.id.refresh);
        refresh.setOnClickListener(v -> manualRefresh());
        Button change = findViewById(R.id.change);
        change.setOnClickListener(v -> openSetup());
        Button sources = findViewById(R.id.sources);
        sources.setOnClickListener(v -> startActivity(new Intent(this, SourcesActivity.class)));

        lineColour = Color.parseColor(LINE_COLOURS.getOrDefault(prefs.lineId, "#6950A1"));
        int onColour = isLight(lineColour) ? 0xFF111111 : 0xFFFFFFFF;
        findViewById(R.id.header).setBackgroundColor(lineColour);
        TextView lineName = findViewById(R.id.lineName);
        TextView stationName = findViewById(R.id.stationName);
        TextView direction = findViewById(R.id.direction);
        lineName.setText(prefs.lineName.toUpperCase(Locale.UK));
        stationName.setText(prefs.stopName);
        direction.setText(prefs.directionLabel());
        for (TextView tv : new TextView[]{lineName, stationName, direction, change, sources}) {
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
        Prefs.applyKeys(this); // may have changed on the Data sources screen
        main.post(fetchLoop);
        main.postDelayed(tickLoop, TICK_EVERY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(fetchLoop);
        main.removeCallbacks(tickLoop);
        main.removeCallbacks(retryAfterBackoff);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---------------------------------------------------------------- network

    private void manualRefresh() {
        Instant now = Instant.now();
        long wait = backoffSecondsLeft(now);
        if (wait > 0) {
            Toast.makeText(this, "TfL asked to wait — retrying in " + wait + " s", Toast.LENGTH_SHORT).show();
            return;
        }
        if (lastFetchStart != null
                && now.toEpochMilli() - lastFetchStart.toEpochMilli() < MIN_MANUAL_GAP_MS) {
            Toast.makeText(this, "Just refreshed — the list updates every 30 s", Toast.LENGTH_SHORT).show();
            return;
        }
        fetch();
    }

    private long backoffSecondsLeft(Instant now) {
        if (backoffUntil == null || !now.isBefore(backoffUntil)) return 0;
        return Math.max(1, (backoffUntil.toEpochMilli() - now.toEpochMilli() + 999) / 1000);
    }

    private void fetch() {
        if (loading) return;
        // Respect TfL's "try again in N seconds"; a retry is already scheduled.
        if (backoffSecondsLeft(Instant.now()) > 0) return;
        loading = true;
        lastFetchStart = Instant.now();
        render();
        final String stopId = prefs.stopId, lineId = prefs.lineId;
        io.execute(() -> {
            Tfl.Board board = Tfl.board(stopId, lineId);

            // Destination choice is a filter too: record what it hides.
            Tfl.Feed mine = new Tfl.Feed("Your destination choice");
            List<Tfl.Arrival> result = new ArrayList<>();
            for (Tfl.Arrival a : board.trains) {
                if (prefs.matches(a)) {
                    result.add(a);
                } else {
                    mine.drops.add(new Tfl.Drop("Other destination",
                            a.destination + " at " + HHMM.format(a.sortTime()), ""));
                }
            }
            mine.raw = board.trains.size();
            mine.kept = result.size();
            if (!mine.drops.isEmpty()) board.feeds.add(mine);

            boolean allFailed = true;
            for (Tfl.Feed f : board.feeds) {
                if (f != mine && f.error.isEmpty()) allFailed = false;
            }

            final boolean failed = allFailed;
            final Instant at = Instant.now();
            main.post(() -> {
                loading = false;
                lastBoard = board;
                lastBoardAt = at;
                if (board.retryAfterSec > 0) {
                    backoffUntil = at.plusSeconds(board.retryAfterSec);
                    main.removeCallbacks(retryAfterBackoff);
                    main.postDelayed(retryAfterBackoff, board.retryAfterSec * 1000L + 500);
                } else {
                    backoffUntil = null;
                }
                if (failed) {
                    // Keep showing the last good list; say what went wrong.
                    lastError = board.sourceNote;
                } else {
                    trains = result;
                    sourceNote = board.sourceNote;
                    lastUpdated = at;
                    lastError = null;
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
        long wait = backoffSecondsLeft(now);
        if (wait > 0) {
            s.append("TfL asked to wait — retrying in ").append(wait).append(" s. ");
        } else if (lastError != null) {
            s.append(lastError).append(". ");
        }
        if (lastUpdated != null) s.append("Updated ").append(HHMMSS.format(lastUpdated));
        else if (!loading && lastError == null) s.append("No data yet");
        if (lastError == null && wait == 0 && !sourceNote.isEmpty()) s.append("\n").append(sourceNote);
        status.setText(s.toString().trim());
        renderFilteredSummary();

        list.removeAllViews();

        List<Tfl.Arrival> upcoming = new ArrayList<>();
        for (Tfl.Arrival t : trains) {
            if (t.sortTime().isAfter(now.minusSeconds(30))) upcoming.add(t);
        }

        if (upcoming.isEmpty() && lastUpdated != null) {
            TextView empty = text("No matching trains in the next ~30 minutes.\n"
                    + "Late at night the service may have finished.", 15, getColor(R.color.muted), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(40), dp(16), dp(16));
            list.addView(empty);
            return;
        }

        boolean highlighted = false;
        for (Tfl.Arrival t : upcoming) {
            boolean first = !highlighted && !t.isCancelled();
            if (first) highlighted = true;
            list.addView(row(t, now, first));
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

        boolean cancelled = t.isCancelled();
        // Timetabled time when known (like station boards); a delay shows on its own line.
        Instant shownTime = t.scheduled != null ? t.scheduled : t.expected;
        TextView title = text(HHMM.format(shownTime) + "  " + t.destination, 18,
                getColor(cancelled ? R.color.muted : R.color.text), true);
        if (cancelled) {
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        left.addView(title);

        // Delay line: "Expected 20:35" when it differs from the timetable.
        if (!cancelled && t.scheduled != null && t.expected != null
                && Duration.between(t.scheduled, t.expected).getSeconds() >= 60) {
            left.addView(text("Expected " + HHMM.format(t.expected), 14, getColor(R.color.amber), true));
        } else if (!cancelled && "Delayed".equalsIgnoreCase(t.status)) {
            left.addView(text(t.expectedUnknown ? "Delayed (no estimate yet)" : "Delayed",
                    14, getColor(R.color.amber), true));
        }
        if (!cancelled && !t.cause.isEmpty()) {
            left.addView(text(t.cause, 12, getColor(R.color.muted), false));
        }

        StringBuilder sub = new StringBuilder();
        if (!t.platform.isEmpty()) sub.append(t.platform);
        if (!cancelled && !t.currentLocation.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(t.currentLocation);
        }
        if (cancelled && !t.cause.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(t.cause);
        }
        if (sub.length() > 0) left.addView(text(sub.toString(), 13, getColor(R.color.muted), false));

        if (!t.note.isEmpty()) {
            TextView note = text("⚠ " + t.note.replace("\n", "\n⚠ "), 12, getColor(R.color.note), false);
            note.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
            note.setPadding(0, dp(3), 0, 0);
            left.addView(note);
        }

        TextView right;
        if (cancelled) {
            boolean notStopping = "NotStoppingAtStation".equalsIgnoreCase(t.status);
            right = text(notStopping ? "Not\nstopping" : "Cancelled", 16, getColor(R.color.red), true);
        } else {
            long secs = Duration.between(now, t.expected).getSeconds();
            boolean due = secs < 60;
            right = text(due ? "Due" : (secs / 60) + " min", first ? 26 : 22,
                    getColor(due ? R.color.amber : R.color.text), true);
        }
        right.setGravity(Gravity.END);

        card.addView(left);
        card.addView(right);
        return card;
    }

    // ------------------------------------------------------------ diagnostics

    /** "12 filtered out: 8 other line · 3 duplicate · 1 other destination  ›" */
    private void renderFilteredSummary() {
        if (lastBoard == null) {
            filtered.setVisibility(View.GONE);
            return;
        }
        Map<String, Integer> byReason = new LinkedHashMap<>();
        int total = 0;
        boolean anyError = false;
        for (Tfl.Feed f : lastBoard.feeds) {
            if (!f.error.isEmpty()) anyError = true;
            for (Tfl.Drop d : f.drops) {
                byReason.merge(d.reason.toLowerCase(Locale.UK), 1, Integer::sum);
                total++;
            }
        }
        if (total == 0 && !anyError) {
            filtered.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (total > 0) {
            sb.append(total).append(" filtered out: ");
            boolean firstReason = true;
            for (Map.Entry<String, Integer> e : byReason.entrySet()) {
                if (!firstReason) sb.append(" · ");
                sb.append(e.getValue()).append(' ').append(e.getKey());
                firstReason = false;
            }
        } else {
            sb.append("A request failed");
        }
        sb.append("  — details ›");
        filtered.setText(sb.toString());
        filtered.setVisibility(View.VISIBLE);
    }

    private String detailsReport() {
        StringBuilder r = new StringBuilder();
        r.append("Fetched ").append(lastBoardAt == null ? "?" : HHMMSS.format(lastBoardAt)).append('\n');
        r.append("Line: ").append(prefs.lineName).append(" (").append(prefs.lineId).append(")\n");
        r.append("Station: ").append(prefs.stopName).append(" (").append(prefs.stopId).append(")\n");
        r.append("Showing: ").append(prefs.directionLabel()).append('\n');
        r.append("TfL API key: ").append(Tfl.appKey.isEmpty() ? "not set (anonymous, low rate limit)" : "set")
                .append('\n');
        r.append("National Rail: ").append(Tfl.nrKey.isEmpty() || Tfl.nrCrs.isEmpty()
                ? "not set up" : "key set, station " + Tfl.nrCrs).append('\n');
        if (lastBoard.retryAfterSec > 0) {
            r.append("Rate limited: TfL asked to wait ").append(lastBoard.retryAfterSec).append(" s\n");
        }
        if (!lastBoard.sourceNote.isEmpty()) r.append("Note: ").append(lastBoard.sourceNote).append('\n');

        for (Tfl.Feed f : lastBoard.feeds) {
            r.append("\n━━ ").append(f.name).append(" ━━\n");
            if (!f.url.isEmpty()) r.append(f.url).append('\n');
            if (f.httpCode != 0) r.append("HTTP ").append(f.httpCode).append(" · ");
            r.append("received ").append(f.raw)
                    .append(" · kept ").append(f.kept)
                    .append(" · filtered ").append(f.drops.size()).append('\n');
            if (!f.error.isEmpty()) r.append("ERROR: ").append(f.error).append('\n');
            if (!f.statuses.isEmpty()) r.append("Statuses: ").append(f.statuses).append('\n');
            for (String m : f.messages) r.append("Message: ").append(m).append('\n');
            if (!f.bodySnippet.isEmpty()) {
                r.append("Response started with:\n").append(f.bodySnippet).append('\n');
            }

            List<Tfl.Drop> drops = new ArrayList<>(f.drops);
            drops.sort((a, b) -> a.reason.compareTo(b.reason));
            String lastReason = null;
            for (Tfl.Drop d : drops) {
                if (!d.reason.equals(lastReason)) {
                    r.append("\n").append(d.reason).append(":\n");
                    lastReason = d.reason;
                }
                r.append(" • ").append(d.summary).append('\n');
                if (!d.json.isEmpty()) r.append("   ").append(d.json).append('\n');
            }
        }
        return r.toString();
    }

    private void showDetails() {
        if (lastBoard == null) return;
        String report = detailsReport();

        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(lineColour);
        bar.setPadding(dp(16), dp(8), dp(4), dp(8));
        int onColour = isLight(lineColour) ? 0xFF111111 : 0xFFFFFFFF;
        TextView title = text("Feed details", 18, onColour, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button copy = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
        copy.setText("Copy");
        copy.setTextColor(onColour);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Train feed details", report));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        Button close = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
        close.setText("Close");
        close.setTextColor(onColour);
        close.setOnClickListener(v -> dialog.dismiss());
        Button key = new Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless);
        key.setText("Keys");
        key.setTextColor(onColour);
        key.setOnClickListener(v -> startActivity(new Intent(this, SourcesActivity.class)));
        bar.addView(title);
        bar.addView(key);
        bar.addView(copy);
        bar.addView(close);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView body = text(report, 12, getColor(R.color.text), false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(body);
        root.addView(scroll);

        dialog.setContentView(root);
        dialog.show();
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
