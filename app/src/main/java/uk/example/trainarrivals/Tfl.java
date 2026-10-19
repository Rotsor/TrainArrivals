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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Minimal client for the free TfL Unified API (https://api.tfl.gov.uk). */
final class Tfl {

    private Tfl() {}

    /** Optional free key from https://api-portal.tfl.gov.uk for higher rate limits. */
    private static final String APP_KEY = "";

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
        Instant expected;
        String lineId;
        String platform;
        String destination;
        String destinationId;
        String currentLocation;
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
        JSONArray arr = new JSONArray(get("/StopPoint/" + enc(stopId) + "/Arrivals"));
        List<Arrival> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!lineId.equalsIgnoreCase(o.optString("lineId"))) continue;

            Instant when;
            try {
                when = Instant.parse(o.optString("expectedArrival"));
            } catch (Exception e) {
                continue;
            }

            // TfL sometimes lists the same train twice; dedupe to the minute.
            String dest = cleanName(o.optString("destinationName", ""));
            String key = (when.getEpochSecond() / 60) + "|" + norm(dest);
            if (!seen.add(key)) continue;

            Arrival a = new Arrival();
            a.expected = when;
            a.lineId = o.optString("lineId");
            a.platform = cleanPlatform(o.optString("platformName", ""));
            a.destination = dest.isEmpty() ? cleanName(o.optString("towards", "")) : dest;
            a.destinationId = o.optString("destinationNaptanId", "");
            String loc = o.optString("currentLocation", "");
            a.currentLocation = "null".equals(loc) ? "" : loc;
            out.add(a);
        }
        out.sort((a, b) -> a.expected.compareTo(b.expected));
        return out;
    }

    // ---------------------------------------------------------------- helpers

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
        String url = BASE + path;
        if (!APP_KEY.isEmpty()) url += (url.contains("?") ? "&" : "?") + "app_key=" + APP_KEY;

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
