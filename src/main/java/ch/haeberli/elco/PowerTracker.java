package ch.haeberli.elco;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Electricity consumption from the "strom" gateway, in 2-hour slices (00-02, 02-04, … local time).
 *
 * The API returns the last 24 slices (48 h); the last value is the slice that ended at the most recent
 * even hour. Right after an even hour the server may not have the new slice yet, so the new series is
 * checked against the previous one: it must be the previous series shifted by the expected number of
 * slices. If it isn't updated yet, the next poll tries again.
 *
 * Slices are appended once to elco_power_YYYY-MM-DD.csv (date of the slice start).
 */
final class PowerTracker {

    static final Duration SLOT = Duration.ofHours(2);
    static final List<String> COLUMNS = List.of("slot_start", "slot_end", "electricity_kwh");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final double EPS = 0.0005;

    private final Config cfg;
    private final Api api;
    private final ZoneId zone;
    private final DailyCsvWriter writer;
    private final DayStore store;

    private List<Double> prev;
    private LocalDateTime prevEnd;
    private LocalDateTime doneFor; // even hour whose slice has been written
    private final Map<LocalDate, Set<String>> written = new HashMap<>();

    PowerTracker(Config cfg, Api api, ZoneId zone) {
        this.cfg = cfg;
        this.api = api;
        this.zone = zone;
        this.writer = new DailyCsvWriter(cfg.outputDir, "elco_power", cfg.csvDelimiter, COLUMNS);
        this.store = new DayStore(cfg.outputDir, cfg.csvDelimiter);
    }

    /** Called after every poll; does nothing until the first poll after an even hour. */
    void maybeFetch(Instant now) throws InterruptedException {
        LocalDateTime evenHour = floorEven(LocalDateTime.ofInstant(now, zone));
        if (evenHour.equals(doneFor)) {
            return;
        }
        List<Double> series;
        try {
            series = fetch();
        } catch (IOException e) {
            Log.warn("power: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return;
        }
        if (series.isEmpty()) {
            Log.warn("power: empty series");
            return;
        }
        LocalDateTime end = align(series, evenHour);
        if (end == null) {
            Log.info("power: newest 2h slice not published yet - will retry on the next poll");
            return;
        }
        int added = write(series, end);
        prev = series;
        prevEnd = end;
        if (!end.isBefore(evenHour)) {
            doneFor = evenHour;
        }
        Log.info("power: " + added + " new 2h slice(s), latest " + end.minus(SLOT).toLocalTime() + "-"
                + end.toLocalTime() + " = " + Poller.round(series.get(series.size() - 1), 3) + " kWh");
    }

    /** Total electricity per slice = sum of all usages (heating + hot water) of the 2h series (p=1). */
    private List<Double> fetch() throws IOException, InterruptedException {
        JsonNode arr = api.getJson("/api/v2/remote/reports/" + cfg.stromGateway
                + "/consSequencesApi8?usages=Ch,Dhw&hasSlp=false");
        // k=1 / k=2 and k=24 / k=25 carry the same data twice → use only the lowest pair seen
        Map<Integer, JsonNode> byK = new HashMap<>();
        for (JsonNode s : arr) {
            if (s.path("p").asInt() == 1) {
                byK.put(s.path("k").asInt(), s.path("v"));
            }
        }
        List<Integer> ks = new ArrayList<>(byK.keySet());
        ks.sort(Integer::compare);
        List<Integer> use = ks.contains(1) ? List.of(1, 2) : ks.contains(24) ? List.of(24, 25) : ks;
        List<Double> total = new ArrayList<>();
        for (int k : use) {
            JsonNode v = byK.get(k);
            if (v == null) {
                continue;
            }
            for (int i = 0; i < v.size(); i++) {
                double x = v.get(i).asDouble(0);
                if (i < total.size()) {
                    total.set(i, total.get(i) + x);
                } else {
                    total.add(x);
                }
            }
        }
        return total;
    }

    /** End time of the last slice in {@code series}, or null if it has not moved on since the last fetch. */
    LocalDateTime align(List<Double> series, LocalDateTime evenHour) {
        if (prev != null && prev.size() == series.size()) {
            long expected = Duration.between(prevEnd, evenHour).dividedBy(SLOT);
            for (long k = Math.min(expected, series.size() - 1); k >= 0; k--) {
                if (shiftedEquals(prev, series, (int) k)) {
                    return k == 0 ? null : prevEnd.plus(SLOT.multipliedBy(k));
                }
            }
            return evenHour; // values were revised – trust the clock
        }
        // first fetch since start: check against slices already on disk
        for (LocalDateTime candidate : List.of(evenHour, evenHour.minus(SLOT))) {
            int[] r = compareWithStored(series, candidate);
            if (r[0] > 0 && r[1] == 0) {
                return candidate;
            }
        }
        return evenHour;
    }

    static boolean shiftedEquals(List<Double> prev, List<Double> cur, int k) {
        int n = cur.size();
        for (int i = 0; i + k < n; i++) {
            if (Math.abs(prev.get(i + k) - cur.get(i)) > EPS) {
                return false;
            }
        }
        return true;
    }

    /** [matches, mismatches] of non-zero stored slices if the series ended at {@code end}. */
    private int[] compareWithStored(List<Double> series, LocalDateTime end) {
        int match = 0, mismatch = 0;
        Map<String, Double> stored = new HashMap<>();
        for (LocalDate d : List.of(end.toLocalDate(), end.toLocalDate().minusDays(1), end.toLocalDate().minusDays(2))) {
            try {
                for (Map<String, String> r : store.power(d)) {
                    try {
                        stored.put(r.get("slot_start"), Double.parseDouble(r.get("electricity_kwh")));
                    } catch (RuntimeException ignored) {
                        // skip broken row
                    }
                }
            } catch (IOException ignored) {
                // no file
            }
        }
        int n = series.size();
        for (int i = 0; i < n; i++) {
            LocalDateTime start = end.minus(SLOT.multipliedBy(n - i));
            Double s = stored.get(start.format(TS));
            if (s != null && s > EPS) {
                if (Math.abs(s - series.get(i)) <= EPS) {
                    match++;
                } else {
                    mismatch++;
                }
            }
        }
        return new int[]{match, mismatch};
    }

    private int write(List<Double> series, LocalDateTime end) {
        int n = series.size(), added = 0;
        for (int i = 0; i < n; i++) {
            LocalDateTime slotEnd = end.minus(SLOT.multipliedBy(n - 1 - i));
            LocalDateTime slotStart = slotEnd.minus(SLOT);
            LocalDate day = slotStart.toLocalDate();
            Set<String> have = written.computeIfAbsent(day, this::loadWritten);
            String key = slotStart.format(TS);
            if (have.contains(key)) {
                continue;
            }
            Map<String, String> row = new HashMap<>();
            row.put("slot_start", key);
            row.put("slot_end", slotEnd.format(TS));
            row.put("electricity_kwh", Poller.round(series.get(i), 3));
            try {
                writer.append(day, row);
                have.add(key);
                added++;
            } catch (IOException e) {
                Log.warn("power: cannot write " + key + ": " + e.getMessage());
            }
        }
        return added;
    }

    private Set<String> loadWritten(LocalDate day) {
        Set<String> s = new HashSet<>();
        try {
            for (Map<String, String> r : store.power(day)) {
                s.add(r.get("slot_start"));
            }
        } catch (IOException ignored) {
            // nothing yet
        }
        return s;
    }

    static LocalDateTime floorEven(LocalDateTime t) {
        LocalDateTime h = t.withMinute(0).withSecond(0).withNano(0);
        return h.getHour() % 2 == 0 ? h : h.minusHours(1);
    }
}
