package uk.example.trainarrivals;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The line's stopping patterns, from TfL's /Line/{id}/Route/Sequence/all.
 * Used to work out whether a train heading for a destination calls at a
 * given station. This is the timetable pattern, not live: a train skipping
 * a stop on the day won't show here.
 */
final class Routes {

    /** One stop in a pattern: TfL id and normalised name. */
    static final class Stop {
        final String id, norm;
        Stop(String id, String norm) { this.id = id; this.norm = norm; }

        boolean is(String otherId, String otherName) {
            if (otherId != null && !otherId.isEmpty() && otherId.equalsIgnoreCase(id)) return true;
            return otherName != null && !otherName.isEmpty() && Tfl.norm(otherName).equals(norm);
        }
    }

    final List<List<Stop>> patterns = new ArrayList<>();

    static Routes parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        Map<String, String> names = new HashMap<>();
        JSONArray seqs = root.optJSONArray("stopPointSequences");
        if (seqs != null) {
            for (int i = 0; i < seqs.length(); i++) {
                JSONObject sq = seqs.optJSONObject(i);
                JSONArray stops = sq == null ? null : sq.optJSONArray("stopPoint");
                if (stops == null) continue;
                for (int j = 0; j < stops.length(); j++) {
                    JSONObject st = stops.optJSONObject(j);
                    if (st == null) continue;
                    String name = st.optString("name", "");
                    for (String k : new String[]{"id", "stationId", "topMostParentId"}) {
                        String id = st.optString(k, "");
                        if (!id.isEmpty() && !name.isEmpty()) names.put(id, name);
                    }
                }
            }
        }

        Routes r = new Routes();
        JSONArray ordered = root.optJSONArray("orderedLineRoutes");
        if (ordered != null) {
            for (int i = 0; i < ordered.length(); i++) {
                JSONObject route = ordered.optJSONObject(i);
                JSONArray ids = route == null ? null : route.optJSONArray("naptanIds");
                if (ids == null) continue;
                List<Stop> pattern = new ArrayList<>();
                for (int j = 0; j < ids.length(); j++) {
                    String id = ids.optString(j, "");
                    pattern.add(new Stop(id, Tfl.norm(names.getOrDefault(id, ""))));
                }
                if (pattern.size() > 1) r.patterns.add(pattern);
            }
        }
        // Fallback: if there are no ordered routes, use the stop sequences themselves.
        if (r.patterns.isEmpty() && seqs != null) {
            for (int i = 0; i < seqs.length(); i++) {
                JSONObject sq = seqs.optJSONObject(i);
                JSONArray stops = sq == null ? null : sq.optJSONArray("stopPoint");
                if (stops == null) continue;
                List<Stop> pattern = new ArrayList<>();
                for (int j = 0; j < stops.length(); j++) {
                    JSONObject st = stops.optJSONObject(j);
                    if (st != null) pattern.add(new Stop(st.optString("id"), Tfl.norm(st.optString("name"))));
                }
                if (pattern.size() > 1) r.patterns.add(pattern);
            }
        }
        if (r.patterns.isEmpty()) throw new java.io.IOException("No stopping patterns in TfL's route data");
        return r;
    }

    /**
     * Does a train from `from` to `dest` call at any of the target stations
     * (after `from`, up to and including `dest`)? TRUE / FALSE, or null when
     * no known pattern runs from `from` to `dest` (can't tell).
     */
    Boolean callsAt(String fromId, String fromName, String destId, String destName,
                    Collection<String> targetIds, Collection<String> targetNames) {
        boolean known = false;
        for (List<Stop> p : patterns) {
            int iFrom = -1;
            for (int i = 0; i < p.size(); i++) {
                if (p.get(i).is(fromId, fromName)) { iFrom = i; break; }
            }
            if (iFrom < 0) continue;
            int iDest = -1;
            for (int i = iFrom + 1; i < p.size(); i++) {
                if (p.get(i).is(destId, destName)) { iDest = i; break; }
            }
            if (iDest < 0) continue;
            known = true;
            for (int i = iFrom + 1; i <= iDest; i++) {
                Stop s = p.get(i);
                for (String t : targetIds) if (s.is(t, null)) return Boolean.TRUE;
                for (String t : targetNames) if (s.is(null, t)) return Boolean.TRUE;
            }
        }
        return known ? Boolean.FALSE : null;
    }

    // ------------------------------------------------------------------ cache

    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final Map<String, Routes> memory = new HashMap<>();

    /**
     * The route map for a line: from memory, else from the phone (if under a
     * day old), else fetched from TfL (one request per line per day). A stale
     * copy is used if fetching fails. Records the request on `f` when made.
     */
    static synchronized Routes get(Context c, String lineId, Tfl.Feed f, boolean allowNetwork)
            throws Exception {
        Routes r = memory.get(lineId);
        if (r != null) return r;

        android.content.SharedPreferences sp = c.getSharedPreferences("routes", Context.MODE_PRIVATE);
        String cached = sp.getString(lineId, "");
        long at = sp.getLong(lineId + "_at", 0);
        if (!cached.isEmpty() && System.currentTimeMillis() - at < MAX_AGE_MS) {
            try {
                r = parse(cached);
                memory.put(lineId, r);
                return r;
            } catch (Exception ignored) {
                // Re-fetch below.
            }
        }
        if (!allowNetwork) {
            if (!cached.isEmpty()) {
                r = parse(cached);
                memory.put(lineId, r);
                return r;
            }
            throw new java.io.IOException("Not fetched: TfL asked to wait");
        }
        try {
            String body = Tfl.routeSequence(lineId, f);
            r = parse(body);
            sp.edit().putString(lineId, body).putLong(lineId + "_at", System.currentTimeMillis()).apply();
            memory.put(lineId, r);
            return r;
        } catch (Exception e) {
            if (!cached.isEmpty()) {
                r = parse(cached);
                memory.put(lineId, r);
                return r;
            }
            throw e;
        }
    }
}
