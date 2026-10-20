package uk.example.trainarrivals;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The user's chosen line, station and destinations, stored only on the phone. */
final class Prefs {

    String lineId = "", lineName = "";
    String stopId = "", stopName = "";
    /** Destination names (display form). Empty = any direction. */
    List<String> destNames = new ArrayList<>();
    /** Destination stop IDs, where known, matched alongside names. */
    Set<String> destIds = new HashSet<>();

    /** How trains are filtered: by final destination, or by a station they call at. */
    static final String MODE_DEST = "dest", MODE_CALLS = "calls";
    String mode = MODE_DEST;
    /** "Calling at" stations (display names) and their stop IDs. Empty = any train. */
    List<String> callNames = new ArrayList<>();
    Set<String> callIds = new HashSet<>();

    boolean isCallingMode() {
        return MODE_CALLS.equals(mode);
    }

    private static final String FILE = "settings";

    static Prefs load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        p.lineId = sp.getString("lineId", "");
        p.lineName = sp.getString("lineName", "");
        p.stopId = sp.getString("stopId", "");
        p.stopName = sp.getString("stopName", "");
        String names = sp.getString("destNames", "");
        if (!names.isEmpty()) for (String n : names.split("\n")) p.destNames.add(n);
        p.destIds = new HashSet<>(sp.getStringSet("destIds", new HashSet<>()));
        p.mode = sp.getString("mode", MODE_DEST);
        String calls = sp.getString("callNames", "");
        if (!calls.isEmpty()) for (String n : calls.split("\n")) p.callNames.add(n);
        p.callIds = new HashSet<>(sp.getStringSet("callIds", new HashSet<>()));
        return p;
    }

    void save(Context c) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("lineId", lineId)
                .putString("lineName", lineName)
                .putString("stopId", stopId)
                .putString("stopName", stopName)
                .putString("destNames", String.join("\n", destNames))
                .putStringSet("destIds", new HashSet<>(destIds))
                .putString("mode", mode)
                .putString("callNames", String.join("\n", callNames))
                .putStringSet("callIds", new HashSet<>(callIds))
                .apply();
    }

    // ------------------------------------------------------------- starred

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("lineId", lineId).put("lineName", lineName)
                .put("stopId", stopId).put("stopName", stopName)
                .put("mode", mode)
                .put("destNames", new JSONArray(destNames))
                .put("destIds", new JSONArray(destIds))
                .put("callNames", new JSONArray(callNames))
                .put("callIds", new JSONArray(callIds));
    }

    static Prefs fromJson(JSONObject o) {
        Prefs p = new Prefs();
        p.lineId = o.optString("lineId");
        p.lineName = o.optString("lineName");
        p.stopId = o.optString("stopId");
        p.stopName = o.optString("stopName");
        p.mode = o.optString("mode", MODE_DEST);
        addAll(p.destNames, o.optJSONArray("destNames"));
        addAll(p.destIds, o.optJSONArray("destIds"));
        addAll(p.callNames, o.optJSONArray("callNames"));
        addAll(p.callIds, o.optJSONArray("callIds"));
        return p;
    }

    private static void addAll(java.util.Collection<String> to, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i, "");
            if (!v.isEmpty()) to.add(v);
        }
    }

    /** Identity of a setup: same line, station and filter = same star. */
    String key() {
        Set<String> names = new TreeSet<>();
        for (String n : isCallingMode() ? callNames : destNames) names.add(Tfl.norm(n));
        return lineId + "|" + stopId + "|" + (names.isEmpty() ? "all" : mode) + "|" + String.join(",", names);
    }

    /** Short label for a star chip, e.g. "Bank → Morden" or "Bank via Kennington". */
    String chipLabel() {
        List<String> names = isCallingMode() ? callNames : destNames;
        if (names.isEmpty()) return stopName + " (all)";
        String joined = names.size() <= 2 ? String.join("/", names)
                : names.get(0) + " +" + (names.size() - 1);
        return stopName + (isCallingMode() ? " via " : " → ") + joined;
    }

    static List<Prefs> loadStarred(Context c) {
        List<Prefs> out = new ArrayList<>();
        String raw = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("starred", "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    Prefs p = fromJson(o);
                    if (p.isConfigured()) out.add(p);
                }
            }
        } catch (Exception ignored) {
            // Corrupt list: start again rather than crash.
        }
        return out;
    }

    static void saveStarred(Context c, List<Prefs> list) {
        JSONArray a = new JSONArray();
        for (Prefs p : list) {
            try {
                a.put(p.toJson());
            } catch (Exception ignored) {
                // Skip anything that can't be written.
            }
        }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("starred", a.toString()).apply();
    }

    static boolean isStarred(Context c, Prefs p) {
        for (Prefs s : loadStarred(c)) if (s.key().equals(p.key())) return true;
        return false;
    }

    /** Star or unstar; returns the new state. */
    static boolean toggleStar(Context c, Prefs p) {
        List<Prefs> list = loadStarred(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key().equals(p.key())) {
                list.remove(i);
                saveStarred(c, list);
                return false;
            }
        }
        list.add(p);
        saveStarred(c, list);
        return true;
    }

    /** Kept apart from the station choice so re-running setup never clears it. */
    static String loadApiKey(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("tflApiKey", "");
    }

    static void saveApiKey(Context c, String key) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("tflApiKey", key == null ? "" : key.trim())
                .apply();
    }

    static String loadNrKey(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("nrApiKey", "");
    }

    static String loadNrCrs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("nrCrs", "");
    }

    static void saveNationalRail(Context c, String key, String crs) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("nrApiKey", key == null ? "" : key.trim())
                .putString("nrCrs", crs == null ? "" : crs.trim().toUpperCase(java.util.Locale.UK))
                .apply();
    }

    /** Push all saved keys into the API client. */
    static void applyKeys(Context c) {
        Tfl.appKey = loadApiKey(c);
        Tfl.nrKey = loadNrKey(c);
        Tfl.nrCrs = loadNrCrs(c);
    }

    boolean isConfigured() {
        return !lineId.isEmpty() && !stopId.isEmpty();
    }

    /** Destination filter only; "calling at" needs the route map (see Routes). */
    boolean matches(Tfl.Arrival a) {
        if (isCallingMode()) return true;
        if (destNames.isEmpty()) return true;
        if (!a.destinationId.isEmpty() && destIds.contains(a.destinationId)) return true;
        String n = Tfl.norm(a.destination);
        for (String d : destNames) if (Tfl.norm(d).equals(n)) return true;
        return false;
    }

    String directionLabel() {
        if (isCallingMode()) {
            return callNames.isEmpty() ? "All directions" : "Calling at " + String.join(" / ", callNames);
        }
        return destNames.isEmpty() ? "All directions" : "→ " + String.join(" / ", destNames);
    }
}
