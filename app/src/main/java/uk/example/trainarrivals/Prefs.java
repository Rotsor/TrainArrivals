package uk.example.trainarrivals;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The user's chosen line, station and destinations, stored only on the phone. */
final class Prefs {

    String lineId = "", lineName = "";
    String stopId = "", stopName = "";
    /** Destination names (display form). Empty = any direction. */
    List<String> destNames = new ArrayList<>();
    /** Destination stop IDs, where known, matched alongside names. */
    Set<String> destIds = new HashSet<>();

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
                .apply();
    }

    boolean isConfigured() {
        return !lineId.isEmpty() && !stopId.isEmpty();
    }

    boolean matches(Tfl.Arrival a) {
        if (destNames.isEmpty()) return true;
        if (!a.destinationId.isEmpty() && destIds.contains(a.destinationId)) return true;
        String n = Tfl.norm(a.destination);
        for (String d : destNames) if (Tfl.norm(d).equals(n)) return true;
        return false;
    }

    String directionLabel() {
        return destNames.isEmpty() ? "All directions" : "→ " + String.join(" / ", destNames);
    }
}
