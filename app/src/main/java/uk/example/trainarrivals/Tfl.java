package uk.example.trainarrivals;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Minimal client for the free TfL Unified API (https://api.tfl.gov.uk). */
final class Tfl {

    private Tfl() {}

    /**
     * Optional free key from https://api-portal.tfl.gov.uk for a much higher
     * rate limit. Entered in the app and stored only on the phone (see Prefs).
     */
    static volatile String appKey = "";

    /**
     * Optional National Rail (Rail Data Marketplace) "Live Departure Board"
     * consumer key and the station's 3-letter CRS code. Stored on the phone only.
     */
    static volatile String nrKey = "";
    static volatile String nrCrs = "";

    private static final String NR_BASE =
            "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120";

    /** National Rail operator code for each TfL rail line. */
    static String operatorFor(String lineId) {
        return "elizabeth".equals(lineId) ? "XR" : "LO";
    }

    /** TfL said "too many requests": how long it asked us to wait. */
    static final class RateLimited extends IOException {
        final int retryAfterSec;
        RateLimited(int sec) {
            super("HTTP 429: TfL rate limit, asked to wait " + sec + " s");
            retryAfterSec = sec;
        }
    }

    private static final String BASE = "https://api.tfl.gov.uk";

    /** Rail-type modes offered in setup. */
    private static final String MODES = "tube,elizabeth-line,dlr,overground,tram";

    // ------------------------------------------------------------------ types

    static final class Item {
        final String id;
        final String name;
        Item(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    static final class Arrival {
        /** Best estimate of when the train leaves (or arrives, for the arrivals feed). */
        Instant expected;
        /** Timetabled departure, when the departure board gives one. */
        Instant scheduled;
        String lineId = "";
        String platform = "";
        String destination = "";
        String destinationId = "";
        String currentLocation = "";
        /** "eastbound" etc. when TfL's platform name says so; "" otherwise. */
        String platformDir = "";
        /** TfL arrivals feed's vehicleId; "" if none. Being checked for stability. */
        String vehicleId = "";
        /** "", OnTime, Delayed, Cancelled or NotStoppingAtStation. */
        String status = "";
        String cause = "";
        /** Where the feeds disagree about this train, one line each; "" if they agree. */
        String note = "";
        /** True when the expected time isn't known (National Rail just says "Delayed"). */
        boolean expectedUnknown = false;

        void addNote(String n) {
            note = note.isEmpty() ? n : note + "\n" + n;
        }

        boolean isCancelled() {
            return "Cancelled".equalsIgnoreCase(status)
                    || "NotStoppingAtStation".equalsIgnoreCase(status);
        }

        /** Time used to order the list and to drop trains that have gone. */
        Instant sortTime() {
            return expected != null ? expected : scheduled;
        }
    }

    /** Trains for the main screen plus a short note on where they came from. */
    static final class Board {
        List<Arrival> trains = new ArrayList<>();
        String sourceNote = "";
        Feed depFeed, arrFeed, nrFeed;
        /** The arrivals feed's trains for this line (all destinations); null if it failed. */
        List<Arrival> arrivals;
        /** Platform label → destinations seen there (with counts) this refresh, all feeds. */
        final Map<String, Map<String, Integer>> platformDests = new LinkedHashMap<>();
        /** Platform label → direction, when a feed names it. */
        final Map<String, String> platformDirs = new HashMap<>();

        void recordPlatforms(List<Arrival> trains) {
            if (trains == null) return;
            for (Arrival a : trains) {
                if (a.platform.isEmpty() || a.destination.isEmpty()) continue;
                platformDests.computeIfAbsent(a.platform, k -> new LinkedHashMap<>())
                        .merge(a.destination, 1, Integer::sum);
                if (!a.platformDir.isEmpty()) platformDirs.put(a.platform, a.platformDir);
            }
        }
        /** Seconds TfL asked us to wait before trying again; 0 if not rate limited. */
        int retryAfterSec = 0;
        /** Every feed requested, in display order. */
        List<Feed> feeds = new ArrayList<>();
    }

    /** One entry the app received but did not show, and why. */
    static final class Drop {
        final String reason, summary, json;
        Drop(String reason, String summary, String json) {
            this.reason = reason; this.summary = summary; this.json = json;
        }
    }

    /** What happened on one request: for the diagnostics screen. */
    static final class Feed {
        final String name;
        String url = "";
        int httpCode = 0;
        String error = "";
        /** Start of the response body, kept when it couldn't be used. */
        String bodySnippet = "";
        int raw = 0, kept = 0;
        final List<Drop> drops = new ArrayList<>();
        /** Service messages (National Rail disruption notices). */
        final List<String> messages = new ArrayList<>();
        /** departureStatus values seen on kept trains, with counts. */
        final Map<String, Integer> statuses = new LinkedHashMap<>();

        Feed(String name) { this.name = name; }

        void drop(String reason, String summary, JSONObject o) {
            String json = o == null ? "" : o.toString();
            if (json.length() > 600) json = json.substring(0, 600) + "…";
            drops.add(new Drop(reason, summary, json));
        }

        void fail(Exception e) {
            String m = e.getMessage();
            error = e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
        }
    }

    private static final DateTimeFormatter HHMM =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"));

    /**
     * National-Rail-style lines, for which TfL's departure board
     * (ArrivalDepartures) carries cancellations and timetabled times.
     */
    private static final Set<String> DEPARTURE_BOARD_LINES = new HashSet<>(java.util.Arrays.asList(
            "elizabeth", "london-overground", "liberty", "lioness", "mildmay",
            "suffragette", "weaver", "windrush"));

    static boolean hasDepartureBoard(String lineId) {
        return DEPARTURE_BOARD_LINES.contains(lineId);
    }

    // -------------------------------------------------------------- requests

    static List<Item> lines() throws Exception {
        JSONArray arr = new JSONArray(get("/Line/Mode/" + MODES));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id"), name = o.optString("name");
            if (id.isEmpty()) continue;
            String mode = o.optString("modeName");
            if ("tube".equals(mode) || "elizabeth-line".equals(mode)) {
                // keep as is
            } else if ("dlr".equals(mode)) {
                name = "DLR";
            } else if ("overground".equals(mode)) {
                name = name + " (Overground)";
            } else if ("tram".equals(mode)) {
                name = "Trams";
            }
            out.add(new Item(id, name));
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static List<Item> stations(String lineId) throws Exception {
        JSONArray arr = new JSONArray(get("/Line/" + enc(lineId) + "/StopPoints"));
        Map<String, Item> byName = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("naptanId", o.optString("id"));
            String name = cleanName(o.optString("commonName"));
            if (id.isEmpty() || name.isEmpty()) continue;
            if (!byName.containsKey(name)) byName.put(name, new Item(id, name));
        }
        List<Item> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /**
     * Where trains on this line can be heading: the ends of each route,
     * plus whatever is currently showing at the chosen station.
     */
    static List<Item> destinations(String lineId, String stopId) throws Exception {
        Map<String, Item> byName = new LinkedHashMap<>();

        String body = get("/Line/" + enc(lineId) + "/Route?serviceTypes=Regular");
        JSONArray lines = body.trim().startsWith("[")
                ? new JSONArray(body) : new JSONArray().put(new JSONObject(body));
        for (int i = 0; i < lines.length(); i++) {
            JSONArray sections = lines.getJSONObject(i).optJSONArray("routeSections");
            if (sections == null) continue;
            for (int j = 0; j < sections.length(); j++) {
                JSONObject s = sections.optJSONObject(j);
                if (s == null) continue;
                addDest(byName, s.optString("destination"), s.optString("destinationName"));
                addDest(byName, s.optString("originator"), s.optString("originationName"));
            }
        }

        try {
            for (Arrival a : arrivals(stopId, lineId)) {
                addDest(byName, a.destinationId, a.destination);
            }
        } catch (Exception ignored) {
            // Live data is a bonus here; route ends are enough.
        }

        List<Item> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static void addDest(Map<String, Item> m, String id, String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty() || m.containsKey(norm(name))) return;
        m.put(norm(name), new Item(id == null ? "" : id, name));
    }

    static List<Arrival> arrivals(String stopId, String lineId) throws Exception {
        return arrivals(stopId, lineId, new Feed("Arrivals feed"));
    }

    static List<Arrival> arrivals(String stopId, String lineId, Feed f) throws Exception {
        JSONArray arr = fetchArray(f, "/StopPoint/" + enc(stopId) + "/Arrivals");
        List<Arrival> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                f.drop("Not a JSON object", String.valueOf(arr.opt(i)), null);
                continue;
            }
            String line = o.optString("lineId");
            if (!lineId.equalsIgnoreCase(line)) {
                f.drop("Other line", line + " → " + o.optString("destinationName"), o);
                continue;
            }

            String rawTime = o.optString("expectedArrival");
            Instant when = parseTime(rawTime);
            if (when == null) {
                f.drop("Unreadable time", "expectedArrival = \"" + rawTime + "\"", o);
                continue;
            }

            // TfL sometimes lists the same train twice; dedupe to the minute.
            String dest = cleanName(o.optString("destinationName", ""));
            String key = (when.getEpochSecond() / 60) + "|" + norm(dest);
            if (!seen.add(key)) {
                f.drop("Duplicate", dest + " at " + HHMM.format(when), o);
                continue;
            }

            Arrival a = new Arrival();
            a.expected = when;
            a.lineId = o.optString("lineId");
            a.platform = cleanPlatform(o.optString("platformName", ""));
            a.platformDir = platformDirection(o.optString("platformName", ""));
            a.destination = dest.isEmpty() ? cleanName(o.optString("towards", "")) : dest;
            a.destinationId = o.optString("destinationNaptanId", "");
            String loc = o.optString("currentLocation", "");
            a.currentLocation = "null".equals(loc) ? "" : loc;
            a.vehicleId = str(o, "vehicleId").trim();
            out.add(a);
        }
        out.sort((a, b) -> a.expected.compareTo(b.expected));
        f.kept = out.size();
        return out;
    }

    /** Raw stopping-pattern data for a line (parsed by Routes). */
    static String routeSequence(String lineId, Feed f) throws Exception {
        return request(f, "/Line/" + enc(lineId) + "/Route/Sequence/all?serviceTypes=Regular");
    }

    /** TfL's departure board for rail lines: includes status and timetabled times. */
    static List<Arrival> departures(String stopId, String lineId, Feed f) throws Exception {
        JSONArray arr = fetchArray(f, "/StopPoint/" + enc(stopId)
                + "/ArrivalDepartures?lineIds=" + enc(lineId));
        List<Arrival> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                f.drop("Not a JSON object", String.valueOf(arr.opt(i)), null);
                continue;
            }

            String rawSched = str(o, "scheduledTimeOfDeparture");
            String rawEst = str(o, "estimatedTimeOfDeparture");
            Instant sched = parseTime(rawSched);
            Instant est = parseTime(rawEst);
            if (sched == null && est == null) {
                if (rawSched.isEmpty() && rawEst.isEmpty()) {
                    f.drop("No departure time (terminates here?)",
                            o.optString("destinationName"), o);
                } else {
                    f.drop("Unreadable time", "scheduled = \"" + rawSched
                            + "\", estimated = \"" + rawEst + "\"", o);
                }
                continue;
            }

            String dest = cleanName(o.optString("destinationName", ""));
            Instant key = sched != null ? sched : est;
            if (!seen.add(key.getEpochSecond() / 60 + "|" + norm(dest))) {
                f.drop("Duplicate", dest + " at " + HHMM.format(key), o);
                continue;
            }

            Arrival a = new Arrival();
            a.lineId = lineId;
            a.scheduled = sched;
            a.expected = est != null ? est : sched;
            a.destination = dest;
            a.destinationId = str(o, "destinationNaptanId");
            a.platform = cleanPlatform(o.optString("platformName", ""));
            a.platformDir = platformDirection(o.optString("platformName", ""));
            a.status = str(o, "departureStatus");
            f.statuses.merge(a.status.isEmpty() ? "(none)" : a.status, 1, Integer::sum);
            a.cause = str(o, "cause");
            out.add(a);
        }
        out.sort((x, y) -> x.sortTime().compareTo(y.sortTime()));
        f.kept = out.size();
        return out;
    }

    /**
     * The trains to show for a station. For rail lines this is the departure
     * board, cross-checked against the arrivals feed, with a note on each
     * train where the two disagree. For Tube/DLR/tram it is the arrivals feed.
     */
    /**
     * Never throws: failures are recorded on the feeds and summarised in
     * sourceNote, so the screen can always say what happened.
     */
    static Board board(String stopId, String lineId) {
        Board b = new Board();
        b.arrFeed = new Feed("TfL arrivals feed");
        b.feeds.add(b.arrFeed);

        if (!hasDepartureBoard(lineId)) {
            List<Arrival> arrs = tryArrivals(stopId, lineId, b.arrFeed, b);
            b.arrivals = arrs == null ? null : new ArrayList<>(arrs);
            b.recordPlatforms(arrs);
            if (arrs != null) b.trains = arrs;
            else b.sourceNote = "Arrivals feed failed: " + b.arrFeed.error;
            return b;
        }

        // 1. TfL departure board.
        b.depFeed = new Feed("TfL departure board");
        b.feeds.add(0, b.depFeed);
        List<Arrival> deps;
        try {
            deps = departures(stopId, lineId, b.depFeed);
        } catch (Exception e) {
            b.depFeed.fail(e);
            noteRateLimit(b, e);
            deps = null;
        }

        // 2. TfL arrivals feed (skipped if TfL just asked us to wait).
        List<Arrival> arrs;
        if (b.retryAfterSec > 0) {
            b.arrFeed.error = "Skipped: TfL asked to wait";
            arrs = null;
        } else {
            arrs = tryArrivals(stopId, lineId, b.arrFeed, b);
            b.arrivals = arrs == null ? null : new ArrayList<>(arrs);
        }

        // 3. National Rail, if the user set it up. Its own rate limit doesn't pause TfL.
        List<Arrival> nr = null;
        String nrKeyNow = nrKey, nrCrsNow = nrCrs;
        if (!nrKeyNow.isEmpty() && !nrCrsNow.isEmpty()) {
            b.nrFeed = new Feed("National Rail");
            b.feeds.add(b.nrFeed);
            try {
                nr = nationalRail(nrCrsNow, nrKeyNow, lineId, b.nrFeed);
            } catch (Exception e) {
                b.nrFeed.fail(e);
            }
        }

        b.recordPlatforms(deps);
        b.recordPlatforms(arrs);
        b.recordPlatforms(nr);

        List<String> notes = new ArrayList<>();
        if (deps == null) notes.add("TfL departure board failed (" + b.depFeed.error + ")");
        else if (deps.isEmpty()) notes.add("TfL departure board had no usable trains (TfL sent " + b.depFeed.raw + ")");
        if (arrs == null) notes.add("TfL arrivals feed failed (" + b.arrFeed.error + ")");
        if (b.nrFeed != null && nr == null) notes.add("National Rail failed (" + b.nrFeed.error + ")");

        // Main list: TfL board, else National Rail, else the arrivals feed.
        List<Arrival> primary;
        if (deps != null && !deps.isEmpty()) {
            primary = deps;
        } else if (nr != null && !nr.isEmpty()) {
            primary = nr;
            notes.add("Showing National Rail");
        } else if (arrs != null) {
            primary = arrs;
            notes.add("Showing the arrivals feed, which doesn't show cancellations");
        } else {
            primary = deps != null ? deps : (nr != null ? nr : new ArrayList<>());
        }

        if (nr != null && primary != nr) crossCheckNationalRail(primary, nr, b.nrFeed);
        if (arrs != null && primary != arrs) crossCheck(primary, arrs);

        b.trains = primary;
        b.sourceNote = String.join(". ", notes);
        return b;
    }

    private static List<Arrival> tryArrivals(String stopId, String lineId, Feed f, Board b) {
        try {
            return arrivals(stopId, lineId, f);
        } catch (Exception e) {
            f.fail(e);
            noteRateLimit(b, e);
            return null;
        }
    }

    private static void noteRateLimit(Board b, Exception e) {
        if (e instanceof RateLimited) {
            b.retryAfterSec = Math.max(b.retryAfterSec, ((RateLimited) e).retryAfterSec);
        }
    }

    /** A train this close to leaving, gone from the arrivals feed, has probably arrived. */
    static final long AT_PLATFORM_WINDOW_SEC = 3 * 60;

    private static void crossCheck(List<Arrival> deps, List<Arrival> arrs) {
        crossCheck(deps, arrs, Instant.now());
    }

    /** Pair up the same train in both feeds, then note any disagreement. */
    static void crossCheck(List<Arrival> deps, List<Arrival> arrs, Instant now) {
        final long matchWindow = 5 * 60;   // seconds: further apart = different trains
        final long reportGap = 3 * 60;     // seconds: arrival vs departure differ a little anyway
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"));
        int[] match = pairClosest(deps, arrs, (d, a) -> {
            if (!norm(a.destination).equals(norm(d.destination))) return -1L;
            long gap = Long.MAX_VALUE;
            if (d.expected != null) gap = Math.min(gap, secsBetween(a.expected, d.expected));
            if (d.scheduled != null) gap = Math.min(gap, secsBetween(a.expected, d.scheduled));
            return gap <= matchWindow ? gap : -1L;
        });
        boolean[] used = new boolean[arrs.size()];
        Instant horizon = null, firstArrival = null;
        for (Arrival a : arrs) {
            if (horizon == null || a.expected.isAfter(horizon)) horizon = a.expected;
            if (firstArrival == null || a.expected.isBefore(firstArrival)) firstArrival = a.expected;
        }

        for (int di = 0; di < deps.size(); di++) {
            Arrival d = deps.get(di);
            int best = match[di];

            if (best < 0) {
                if (d.isCancelled()) continue;
                // The arrivals feed counts down to arrival and drops a train once it
                // gets in, while the departure board keeps it until it leaves. So a
                // train about to leave, ahead of everything in the arrivals feed, has
                // most likely arrived. Inferred, not confirmed: no feed says so.
                boolean aboutToLeave = !d.sortTime().isAfter(now.plusSeconds(AT_PLATFORM_WINDOW_SEC));
                boolean aheadOfFeed = firstArrival == null || !d.sortTime().isAfter(firstArrival);
                if (aboutToLeave && aheadOfFeed) {
                    if (d.currentLocation.isEmpty()) d.currentLocation = "Probably at the platform";
                } else if (horizon != null && !d.sortTime().isAfter(horizon)) {
                    // Within the feed's ~30 min range, yet missing: genuinely odd.
                    // Beyond that range, absence means nothing.
                    d.addNote("Not in the arrivals feed");
                }
                continue;
            }
            used[best] = true;
            Arrival a = arrs.get(best);
            if (d.currentLocation.isEmpty()) d.currentLocation = a.currentLocation;
            if (d.vehicleId.isEmpty()) d.vehicleId = a.vehicleId;

            if (d.isCancelled()) {
                d.addNote("Arrivals feed still shows it, due " + hhmm.format(a.expected));
            } else if (d.expected != null && secsBetween(a.expected, d.expected) >= reportGap) {
                d.addNote("Arrivals feed says " + hhmm.format(a.expected));
            }
        }

        // Trains only the arrivals feed knows about: still show them, flagged.
        for (int i = 0; i < arrs.size(); i++) {
            if (used[i]) continue;
            Arrival a = arrs.get(i);
            a.addNote("Only in the arrivals feed");
            deps.add(a);
        }
        deps.sort((x, y) -> x.sortTime().compareTo(y.sortTime()));
    }

    /** National Rail departure board for a station (CRS), this line's operator only. */
    static List<Arrival> nationalRail(String crs, String key, String lineId, Feed f) throws Exception {
        String url = NR_BASE + "/GetDepartureBoard/" + enc(crs.toUpperCase(Locale.UK))
                + "?numRows=60&timeWindow=120";
        String body = requestUrl(f, url, url, "x-apikey", key);
        return parseNationalRail(body, lineId, f, Instant.now());
    }

    /** Separate from the request so it can be tested with saved responses. */
    static List<Arrival> parseNationalRail(String body, String lineId, Feed f, Instant now)
            throws Exception {
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Exception e) {
            f.bodySnippet = snippet(body);
            throw new IOException("Response wasn't a departure board");
        }

        JSONArray msgs = root.optJSONArray("nrccMessages");
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                String text = m == null ? String.valueOf(msgs.opt(i)) : m.optString("Value", m.toString());
                f.messages.add(text.replaceAll("<[^>]+>", "").trim());
            }
        }

        JSONArray svcs = root.optJSONArray("trainServices");
        List<Arrival> out = new ArrayList<>();
        if (svcs == null) return out; // no trains in the window: a valid, empty board
        f.raw = svcs.length();
        String wantOp = operatorFor(lineId);

        for (int i = 0; i < svcs.length(); i++) {
            JSONObject o = svcs.optJSONObject(i);
            if (o == null) {
                f.drop("Not a JSON object", String.valueOf(svcs.opt(i)), null);
                continue;
            }
            String op = str(o, "operatorCode");
            if (!op.isEmpty() && !op.equalsIgnoreCase(wantOp)) {
                f.drop("Other operator", str(o, "operator") + " (" + op + ") → " + nrDest(o), o);
                continue;
            }
            String std = str(o, "std"), etd = str(o, "etd");
            Instant sched = clockTime(std, now);
            if (sched == null) {
                f.drop("Unreadable time", "std = \"" + std + "\", etd = \"" + etd + "\"", o);
                continue;
            }

            Arrival a = new Arrival();
            a.lineId = lineId;
            a.scheduled = sched;
            a.destination = cleanName(nrDest(o));
            a.platform = cleanPlatform(str(o, "platform"));

            boolean cancelled = o.optBoolean("isCancelled", false) || "Cancelled".equalsIgnoreCase(etd);
            Instant est = clockTime(etd, sched); // "HH:mm" when it's a time
            if (cancelled) {
                a.status = "Cancelled";
                a.expected = sched;
                a.cause = str(o, "cancelReason");
            } else if (est != null) {
                a.expected = est;
                a.status = est.isAfter(sched.plusSeconds(59)) ? "Delayed" : "OnTime";
                a.cause = str(o, "delayReason");
            } else if ("Delayed".equalsIgnoreCase(etd)) {
                a.expected = sched;
                a.expectedUnknown = true;
                a.status = "Delayed";
                a.cause = str(o, "delayReason");
            } else { // "On time" or anything else
                a.expected = sched;
                a.status = "OnTime";
            }
            f.statuses.merge(etd.isEmpty() ? "(none)" : (est != null ? "HH:mm" : etd), 1, Integer::sum);
            out.add(a);
        }
        out.sort((x, y) -> x.sortTime().compareTo(y.sortTime()));
        f.kept = out.size();
        return out;
    }

    private static String nrDest(JSONObject o) {
        JSONArray d = o.optJSONArray("destination");
        if (d == null || d.length() == 0) return "";
        JSONObject first = d.optJSONObject(0);
        return first == null ? "" : first.optString("locationName", "");
    }

    /** "21:35" as the nearest such London clock time to the reference instant. */
    static Instant clockTime(String hhmm, Instant ref) {
        if (hhmm == null || !hhmm.matches("\\d{1,2}:\\d{2}")) return null;
        ZoneId london = ZoneId.of("Europe/London");
        java.time.LocalTime t = java.time.LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
        java.time.LocalDate day = ref.atZone(london).toLocalDate();
        Instant best = null;
        for (int d = -1; d <= 1; d++) {
            Instant c = day.plusDays(d).atTime(t).atZone(london).toInstant();
            if (best == null || secsBetween(c, ref) < secsBetween(best, ref)) best = c;
        }
        return best;
    }

    /**
     * Match each train in the main list with National Rail's, note disagreements,
     * and add trains only National Rail has (within the main list's time range).
     */
    private static void crossCheckNationalRail(List<Arrival> primary, List<Arrival> nr, Feed f) {
        final long schedWindow = 2 * 60, expWindow = 5 * 60, reportGap = 3 * 60;
        int[] match = pairClosest(primary, nr, (p, n) -> {
            if (!norm(n.destination).equals(norm(p.destination))) return -1L;
            if (p.scheduled != null) {
                long gap = secsBetween(p.scheduled, n.scheduled);
                return gap <= schedWindow ? gap : -1L;
            }
            long gap = secsBetween(p.sortTime(), n.sortTime());
            return gap <= expWindow ? gap : -1L;
        });
        boolean[] used = new boolean[nr.size()];
        Instant primaryEnd = null, nrEnd = null;
        for (Arrival p : primary) {
            if (primaryEnd == null || p.sortTime().isAfter(primaryEnd)) primaryEnd = p.sortTime();
        }
        for (Arrival n : nr) {
            if (nrEnd == null || n.sortTime().isAfter(nrEnd)) nrEnd = n.sortTime();
        }

        // Only the trains that were in the list before this check (none added yet).
        int primaryCount = primary.size();
        for (int pi = 0; pi < primaryCount; pi++) {
            Arrival p = primary.get(pi);
            int best = match[pi];

            if (best < 0) {
                if (!p.isCancelled() && nrEnd != null && !p.sortTime().isAfter(nrEnd)) {
                    p.addNote("Not on National Rail");
                }
                continue;
            }
            used[best] = true;
            Arrival n = nr.get(best);

            if (n.isCancelled() && !p.isCancelled()) {
                p.addNote("National Rail: cancelled" + (n.cause.isEmpty() ? "" : " (" + n.cause + ")"));
            } else if (!n.isCancelled() && p.isCancelled()) {
                p.addNote("National Rail shows it running");
            } else if (!n.isCancelled()) {
                if (n.expectedUnknown && p.expected != null && p.scheduled != null
                        && !p.expected.isAfter(p.scheduled.plusSeconds(59))) {
                    p.addNote("National Rail: delayed");
                } else if (!n.expectedUnknown && p.expected != null
                        && secsBetween(n.expected, p.expected) >= reportGap) {
                    p.addNote("National Rail expects " + HHMM.format(n.expected));
                }
            }
            if (p.platform.isEmpty()) {
                p.platform = n.platform;
            } else if (!n.platform.isEmpty() && !n.platform.equalsIgnoreCase(p.platform)) {
                p.addNote("National Rail: " + n.platform);
            }
            // Borrow the reason only when both agree on whether it's cancelled.
            if (p.cause.isEmpty() && n.isCancelled() == p.isCancelled()) p.cause = n.cause;
        }

        for (int i = 0; i < nr.size(); i++) {
            if (used[i]) continue;
            Arrival n = nr.get(i);
            if (primaryEnd != null && !n.sortTime().isAfter(primaryEnd)) {
                n.addNote("Only on National Rail");
                primary.add(n);
            } else {
                f.drop("Later than TfL's list", n.destination + " at " + HHMM.format(n.sortTime()), null);
            }
        }
        primary.sort((x, y) -> x.sortTime().compareTo(y.sortTime()));
    }

    /**
     * Pair trains in two lists, closest pairs first, each train used at most
     * once. gap(a, b) is the distance in seconds, or -1 if they can't be the
     * same train. Returns, for each train in `as`, the index in `bs` or -1.
     */
    static int[] pairClosest(List<Arrival> as, List<Arrival> bs,
                             java.util.function.BiFunction<Arrival, Arrival, Long> gap) {
        List<long[]> pairs = new ArrayList<>(); // {gap, ai, bi}
        for (int i = 0; i < as.size(); i++) {
            for (int j = 0; j < bs.size(); j++) {
                long g = gap.apply(as.get(i), bs.get(j));
                if (g >= 0) pairs.add(new long[]{g, i, j});
            }
        }
        pairs.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0])
                : x[1] != y[1] ? Long.compare(x[1], y[1]) : Long.compare(x[2], y[2]));
        int[] match = new int[as.size()];
        java.util.Arrays.fill(match, -1);
        boolean[] bUsed = new boolean[bs.size()];
        for (long[] pr : pairs) {
            int i = (int) pr[1], j = (int) pr[2];
            if (match[i] >= 0 || bUsed[j]) continue;
            match[i] = j;
            bUsed[j] = true;
        }
        return match;
    }

    private static long secsBetween(Instant a, Instant b) {
        return Math.abs(a.getEpochSecond() - b.getEpochSecond());
    }

    // ---------------------------------------------------------------- helpers

    /** Accepts "2026-10-19T19:30:00Z", with an offset, or local London time. */
    static Instant parseTime(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return null;
        try { return Instant.parse(s); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) {}
        try {
            return LocalDateTime.parse(s).atZone(ZoneId.of("Europe/London")).toInstant();
        } catch (Exception ignored) {}
        return null;
    }

    private static String str(JSONObject o, String key) {
        String v = o.optString(key, "");
        return "null".equals(v) ? "" : v;
    }

    /** "Reading Rail Station" / "Bank Underground Station" -> "Reading" / "Bank". */
    static String cleanName(String s) {
        if (s == null || "null".equals(s)) return "";
        return s.replaceAll("\\s*\\((Elizabeth line|Dist(rict)?|H&C Line|Circle Line|Bakerloo|London)\\)", "")
                .replaceAll("(?i)\\s+(Underground|Rail|DLR|Tram|ELL|Overground)?\\s*Station$", "")
                .replaceAll("(?i)\\s+(Tram Stop|Stop)$", "")
                .trim();
    }

    static String norm(String s) {
        return cleanName(s).toLowerCase(Locale.UK).replaceAll("[^a-z0-9]", "");
    }

    /** "Eastbound - Platform A" → "eastbound"; "" if no direction is named. */
    static String platformDirection(String p) {
        if (p == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b(east|west|north|south)bound\\b|\\b(inner|outer) rail\\b").matcher(p);
        return m.find() ? m.group().toLowerCase(Locale.UK) : "";
    }

    private static String cleanPlatform(String p) {
        if (p == null || p.isEmpty() || "null".equals(p)) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)platform\\s*([0-9A-Za-z]+)").matcher(p);
        if (m.find()) return "Plat " + m.group(1);
        String t = p.trim();
        return t.length() <= 3 ? "Plat " + t : t;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String get(String path) throws Exception {
        Feed f = new Feed("request");
        return request(f, path);
    }

    /** Fetches a JSON list, recording status, size and any failure on the feed. */
    private static JSONArray fetchArray(Feed f, String path) throws Exception {
        String body = request(f, path);
        JSONArray arr;
        try {
            arr = new JSONArray(body);
        } catch (Exception e) {
            f.bodySnippet = snippet(body);
            throw new IOException("Response wasn't a list of trains");
        }
        f.raw = arr.length();
        return arr;
    }

    private static String request(Feed f, String path) throws Exception {
        String shown = BASE + path;
        String url = shown;
        String key = appKey;
        if (key != null && !key.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "app_key=" + URLEncoder.encode(key, "UTF-8");
        }
        return requestUrl(f, url, shown, null, null);
    }

    /** GET a URL; `shown` is what the details screen displays (never contains keys). */
    private static String requestUrl(Feed f, String url, String shown,
                                     String headerName, String headerValue) throws Exception {
        f.url = shown;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Accept", "application/json");
        if (headerName != null) c.setRequestProperty(headerName, headerValue);
        try {
            int code = c.getResponseCode();
            f.httpCode = code;
            if (code != 200) {
                String body = "";
                try {
                    body = readAll(c.getErrorStream());
                } catch (Exception ignored) {
                    // Nothing more to show than the code.
                }
                f.bodySnippet = snippet(body);
                if (code == 429) throw new RateLimited(retryAfter(c, body));
                throw new IOException("HTTP " + code);
            }
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** From the Retry-After header, else "Try again in N seconds" in the body; else 30. */
    private static int retryAfter(HttpURLConnection c, String body) {
        try {
            String h = c.getHeaderField("Retry-After");
            if (h != null) return Math.max(1, Integer.parseInt(h.trim()));
        } catch (Exception ignored) {
            // Fall through to the body.
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)try again in (\\d+) second").matcher(body == null ? "" : body);
        if (m.find()) return Math.max(1, Integer.parseInt(m.group(1)));
        return 30;
    }

    private static String readAll(java.io.InputStream s) throws IOException {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String snippet(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 800 ? s.substring(0, 800) + "…" : s;
    }
}
