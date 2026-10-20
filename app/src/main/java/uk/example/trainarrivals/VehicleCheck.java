package uk.example.trainarrivals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tracks the TfL arrivals feed's vehicleId across refreshes to find out
 * whether it reliably identifies one train. Kept in memory while the app is
 * open; makes no requests of its own.
 */
final class VehicleCheck {

    /** Expected time moving more than this between refreshes is suspicious. */
    static final long MAX_JUMP_SEC = 3 * 60;
    /** A train that disappears this close to its expected time has probably arrived. */
    static final long ARRIVED_WINDOW_SEC = 2 * 60;
    /** Forget ids not seen for this long. */
    static final long FORGET_AFTER_SEC = 20 * 60;

    private static final DateTimeFormatter HHMM =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/London"));

    static final class Track {
        int seen;
        Instant firstSeen, lastSeen, firstExpected, lastExpected;
        String dest = "";
        int destChanges, bigJumps;
        long maxJumpSec;
    }

    private final Map<String, Track> tracks = new HashMap<>();
    private Instant previousFetch;
    private int refreshes;
    /** Session totals (not reset when ids are forgotten). */
    private int arrivedTotal, vanishedEarlyTotal;

    // Results of the latest update, for the report.
    private String lastSummary = "No refresh with the arrivals feed yet.\n";

    void update(List<Tfl.Arrival> arrivals, Instant at) {
        if (arrivals == null) {
            String prev = lastSummary.startsWith("Arrivals feed didn't load")
                    ? lastSummary.substring(lastSummary.indexOf('\n') + 1) : lastSummary;
            lastSummary = "Arrivals feed didn't load on the latest refresh; showing the previous check.\n"
                    + prev;
            return;
        }
        refreshes++;

        Map<String, Integer> countInFetch = new HashMap<>();
        for (Tfl.Arrival a : arrivals) {
            if (hasId(a)) countInFetch.merge(a.vehicleId, 1, Integer::sum);
        }

        int withId = 0, noId = 0, fresh = 0, again = 0, consistent = 0;
        List<String> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, Boolean> updatedThisFetch = new HashMap<>();

        for (Tfl.Arrival a : arrivals) {
            String when = HHMM.format(a.expected) + "  " + pad(a.destination, 14);
            if (!hasId(a)) {
                noId++;
                rows.add(when + "  (no id: \"" + a.vehicleId + "\")");
                continue;
            }
            withId++;
            String id = a.vehicleId;
            int dupes = countInFetch.get(id);
            if (updatedThisFetch.containsKey(id)) {
                rows.add(when + "  " + id + "  DUPLICATE id in this refresh");
                continue;
            }
            updatedThisFetch.put(id, true);

            Track t = tracks.get(id);
            String flag;
            if (t == null) {
                t = new Track();
                t.firstSeen = at;
                t.firstExpected = a.expected;
                t.dest = a.destination;
                tracks.put(id, t);
                fresh++;
                flag = "new";
            } else {
                again++;
                long jump = Math.abs(Duration.between(t.lastExpected, a.expected).getSeconds());
                t.maxJumpSec = Math.max(t.maxJumpSec, jump);
                boolean destChanged = !Tfl.norm(t.dest).equals(Tfl.norm(a.destination));
                if (destChanged) {
                    t.destChanges++;
                    problems.add(id + ": destination " + t.dest + " → " + a.destination);
                    t.dest = a.destination;
                }
                if (jump > MAX_JUMP_SEC) {
                    t.bigJumps++;
                    problems.add(id + ": expected time jumped " + signed(t.lastExpected, a.expected)
                            + " (" + HHMM.format(t.lastExpected) + " → " + HHMM.format(a.expected) + ")");
                }
                if (!destChanged && jump <= MAX_JUMP_SEC) consistent++;
                flag = "seen " + (t.seen + 1) + "×, " + signed(t.firstExpected, a.expected)
                        + " since first seen at " + HHMM.format(t.firstSeen);
            }
            t.seen++;
            t.lastSeen = at;
            t.lastExpected = a.expected;
            rows.add(when + "  " + id + "  " + flag + (dupes > 1 ? "  (listed " + dupes + "×)" : ""));
        }

        // Ids from the previous refresh that are gone now.
        int gone = 0, goneNearDue = 0;
        List<String> goneEarly = new ArrayList<>();
        Iterator<Map.Entry<String, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Track> e = it.next();
            Track t = e.getValue();
            if (previousFetch != null && t.lastSeen.equals(previousFetch)) {
                gone++;
                long toDue = Duration.between(at, t.lastExpected).getSeconds();
                if (toDue <= ARRIVED_WINDOW_SEC) {
                    goneNearDue++;
                    arrivedTotal++;
                } else {
                    vanishedEarlyTotal++;
                    goneEarly.add(e.getKey() + " (was due " + HHMM.format(t.lastExpected) + ")");
                }
            }
            if (Duration.between(t.lastSeen, at).getSeconds() > FORGET_AFTER_SEC) it.remove();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Refreshes checked: ").append(refreshes)
                .append(" (resets when the app is closed)\n");
        sb.append("This refresh: ").append(withId).append(" with an id, ")
                .append(noId).append(" without; ")
                .append(again).append(" seen before, ").append(fresh).append(" new\n");
        if (again > 0) {
            sb.append("Of those seen before: ").append(consistent)
                    .append(" consistent (same destination, moved ≤ 3 min), ")
                    .append(again - consistent).append(" not\n");
        }
        if (previousFetch != null) {
            sb.append("Gone since the previous refresh: ").append(gone)
                    .append(" (").append(goneNearDue).append(" were due within 2 min, i.e. likely arrived");
            if (!goneEarly.isEmpty()) sb.append("; ").append(goneEarly.size()).append(" vanished early");
            sb.append(")\n");
        }
        sb.append(historyLine());
        sb.append(verdict());
        for (String p : problems) sb.append("! ").append(p).append('\n');
        for (String g : goneEarly) sb.append("! vanished early: ").append(g).append('\n');
        sb.append('\n');
        for (String r : rows) sb.append(r).append('\n');
        lastSummary = sb.toString();
        previousFetch = at;
    }

    String report() {
        return lastSummary;
    }

    private String historyLine() {
        int tracked = 0, destChanges = 0, bigJumps = 0, multi = 0;
        for (Track t : tracks.values()) {
            tracked++;
            destChanges += t.destChanges;
            bigJumps += t.bigJumps;
            if (t.seen > 1) multi++;
        }
        return "Over this session: " + tracked + " ids tracked, " + multi + " seen more than once, "
                + destChanges + " destination changes, " + bigJumps + " time jumps > 3 min, "
                + arrivedTotal + " dropped out when due (likely arrived), "
                + vanishedEarlyTotal + " vanished early (cancelled, or id not stable?)\n";
    }

    private String verdict() {
        int multi = 0, bad = 0;
        for (Track t : tracks.values()) {
            if (t.seen > 1) multi++;
            if (t.destChanges > 0 || t.bigJumps > 0) bad++;
        }
        if (refreshes < 3 || multi == 0) return "So far: not enough data; leave the app open a few minutes\n";
        if (bad == 0) return "So far: looks stable\n";
        return "So far: NOT reliable (" + bad + " of " + multi + " repeat ids misbehaved)\n";
    }

    private static boolean hasId(Tfl.Arrival a) {
        return !a.vehicleId.isEmpty() && !a.vehicleId.matches("0+") && !"null".equals(a.vehicleId);
    }

    private static String signed(Instant from, Instant to) {
        long s = Duration.between(from, to).getSeconds();
        String sign = s < 0 ? "−" : "+";
        s = Math.abs(s);
        return sign + (s / 60) + ":" + String.format(java.util.Locale.UK, "%02d", s % 60);
    }

    private static String pad(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }
}
