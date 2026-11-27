package ch.haeberli.elco;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Finds defrost cycles in the readings: the heat pump is on and the heat output is clearly negative
 * (heat is taken back from the water to melt the ice on the outdoor coil). Small negative values
 * that occur while the pump just runs are ignored by the threshold.
 */
final class Defrosts {

    /** Output below this (kW) while the heat pump is on counts as defrosting. */
    static final double THRESHOLD_KW = -1.0;
    /** Readings further apart than this do not belong to the same defrost. */
    private static final Duration SAME_EVENT = Duration.ofMinutes(15);

    private Defrosts() {
    }

    /** Start timestamps (yyyy-MM-ddTHH:mm:ss) of all defrosts in the rows, oldest first. */
    static List<String> starts(List<Map<String, String>> rows) {
        List<Map<String, String>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(r -> r.getOrDefault("timestamp", "")));
        List<String> out = new ArrayList<>();
        LocalDateTime lastDefrostRow = null;
        boolean inDefrost = false;
        for (Map<String, String> r : sorted) {
            LocalDateTime t = time(r.get("timestamp"));
            if (t == null) {
                continue;
            }
            Double on = num(r.get("hp_on"));
            Double kw = num(r.get("heat_output_kw"));
            if (on == null || kw == null) {
                continue; // failed poll: neither starts nor ends a defrost
            }
            boolean d = on == 1 && kw < THRESHOLD_KW;
            if (d && !(inDefrost && lastDefrostRow != null
                    && Duration.between(lastDefrostRow, t).compareTo(SAME_EVENT) <= 0)) {
                out.add(r.get("timestamp"));
            }
            if (d) {
                lastDefrostRow = t;
            }
            inDefrost = d;
        }
        return out;
    }

    private static LocalDateTime time(String ts) {
        if (ts == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(ts);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Double num(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
