package uk.example.trainarrivals;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each platform at a station is used for: its direction (when TfL names
 * it) and the destinations of trains seen there. Built up from every refresh
 * and kept on the phone per station, so labels are there straight away.
 */
final class PlatformBook {

    private static final String FILE = "platforms";
    private static final int MAX_SHOWN = 3;
    private static final int DECAY_ABOVE = 500;

    private final Map<String, Map<String, Integer>> dests = new LinkedHashMap<>();
    private final Map<String, String> dirs = new HashMap<>();

    static PlatformBook load(Context c, String stopId) {
        PlatformBook b = new PlatformBook();
        String raw = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(stopId, "");
        if (raw.isEmpty()) return b;
        try {
            JSONObject root = new JSONObject(raw);
            Iterator<String> plats = root.keys();
            while (plats.hasNext()) {
                String plat = plats.next();
                JSONObject p = root.getJSONObject(plat);
                String dir = p.optString("dir", "");
                if (!dir.isEmpty()) b.dirs.put(plat, dir);
                JSONObject d = p.optJSONObject("dests");
                if (d == null) continue;
                Map<String, Integer> m = new LinkedHashMap<>();
                Iterator<String> names = d.keys();
                while (names.hasNext()) {
                    String n = names.next();
                    m.put(n, d.optInt(n, 0));
                }
                b.dests.put(plat, m);
            }
        } catch (Exception ignored) {
            // Start afresh if the saved copy is unreadable.
        }
        return b;
    }

    void merge(Tfl.Board board) {
        for (Map.Entry<String, Map<String, Integer>> e : board.platformDests.entrySet()) {
            Map<String, Integer> m = dests.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
            for (Map.Entry<String, Integer> d : e.getValue().entrySet()) m.merge(d.getKey(), d.getValue(), Integer::sum);
            // Halve old counts now and then, so a platform's current use wins over past use.
            int total = 0;
            for (int v : m.values()) total += v;
            if (total > DECAY_ABOVE) {
                m.replaceAll((k, v) -> v / 2);
                m.values().removeIf(v -> v == 0);
            }
        }
        dirs.putAll(board.platformDirs);
    }

    void save(Context c, String stopId) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Map<String, Integer>> e : dests.entrySet()) {
                JSONObject p = new JSONObject();
                String dir = dirs.get(e.getKey());
                if (dir != null) p.put("dir", dir);
                p.put("dests", new JSONObject(e.getValue()));
                root.put(e.getKey(), p);
            }
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(stopId, root.toString()).apply();
        } catch (Exception ignored) {
            // Labels are a nicety; never fail a refresh over them.
        }
    }

    /**
     * "Plat A — eastbound: Abbey Wood, Shenfield" style label. Uses the
     * train's own direction if it has one, else what's known for the platform.
     */
    String label(String platform, String trainDir) {
        if (platform == null || platform.isEmpty()) return "";
        String dir = trainDir != null && !trainDir.isEmpty() ? trainDir : dirs.getOrDefault(platform, "");
        Map<String, Integer> m = dests.get(platform);
        List<Map.Entry<String, Integer>> top = new ArrayList<>();
        if (m != null) top.addAll(m.entrySet());
        top.sort((x, y) -> y.getValue() != x.getValue().intValue()
                ? Integer.compare(y.getValue(), x.getValue()) : x.getKey().compareTo(y.getKey()));

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_SHOWN, top.size()); i++) {
            if (i > 0) names.append(", ");
            names.append(top.get(i).getKey());
        }
        if (top.size() > MAX_SHOWN) names.append(" +").append(top.size() - MAX_SHOWN);

        if (dir.isEmpty() && names.length() == 0) return platform;
        if (dir.isEmpty()) return platform + " — to " + names;
        if (names.length() == 0) return platform + " — " + dir;
        return platform + " — " + dir + ": " + names;
    }
}
