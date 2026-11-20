package ch.haeberli.elco;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One poll = one read of all configured BSB points on the "elco sx 10" gateway (all in one request; 14 verified to work).
 * Adds heat energy: kW integrated over the time since the previous poll, plus a running total per day.
 * Strictly read-only.
 */
final class Poller {

    static final int BSB_BATCH = 20; // the website sends 10, but the API accepts more (14 verified)
    static final String COL_TS = "timestamp";
    static final String COL_HP_ON = "hp_on";
    static final String COL_HEAT_KW = "heat_output_kw";
    static final String COL_KWH_INTERVAL = "heat_kwh_interval";
    static final String COL_KWH_TODAY = "heat_kwh_today";
    static final String COL_PROBLEMS = "poll_problems";
    /** Longer gaps (app stopped, network down) are not integrated – the energy would be guessed. */
    static final Duration MAX_INTEGRATION_GAP = Duration.ofMinutes(15);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Config cfg;
    private final Api api;
    private final List<Points.Bsb> bsb;
    private final DailyCsvWriter csv;
    private final ZoneId zone;
    private final List<String> columns;

    private Instant prevTime;
    private Double prevKw;
    private LocalDate today;
    private double kwhToday;

    Poller(Config cfg, Api api, List<Points.Bsb> bsb, ZoneId zone) {
        this.cfg = cfg;
        this.api = api;
        this.bsb = bsb;
        this.zone = zone;
        this.columns = buildColumns(bsb);
        this.csv = new DailyCsvWriter(cfg.outputDir, "elco", cfg.csvDelimiter, columns);
    }

    private static List<String> buildColumns(List<Points.Bsb> bsb) {
        List<String> c = new ArrayList<>();
        c.add(COL_TS);
        c.add(COL_HP_ON);
        bsb.stream().map(Points.Bsb::column).filter(x -> !x.equals(COL_HP_ON)).forEach(c::add);
        c.add(COL_KWH_INTERVAL);
        c.add(COL_KWH_TODAY);
        c.add(COL_PROBLEMS);
        return c;
    }

    List<String> columns() {
        return columns;
    }

    /** Polls once, appends a CSV row, returns whether the heat pump (compressor) is on. */
    boolean pollAndWrite(Instant now) throws IOException, InterruptedException {
        List<String> problems = new ArrayList<>();
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < bsb.size(); i += BSB_BATCH) {
            readBatch(bsb.subList(i, Math.min(i + BSB_BATCH, bsb.size())), values, problems);
        }

        LocalDateTime local = LocalDateTime.ofInstant(now, zone);
        LocalDate day = local.toLocalDate();
        if (!day.equals(today)) {
            today = day;
            kwhToday = resumeKwhToday(day);
        }

        Double kw = parse(values.get(COL_HEAT_KW));
        double kwhInterval = 0;
        if (kw != null && prevKw != null && prevTime != null) {
            Duration dt = Duration.between(prevTime, now);
            if (!dt.isNegative() && dt.compareTo(MAX_INTEGRATION_GAP) <= 0) {
                kwhInterval = (prevKw + kw) / 2.0 * dt.toMillis() / 3_600_000.0; // trapezoid
            }
        }
        // the part of an interval that crosses midnight is booked on the new day (negligible)
        kwhToday += kwhInterval;
        prevTime = now;
        prevKw = kw;

        Map<String, String> row = new LinkedHashMap<>(values);
        row.put(COL_TS, local.format(TS));
        row.put(COL_KWH_INTERVAL, round(kwhInterval, 4));
        row.put(COL_KWH_TODAY, round(kwhToday, 3));
        row.put(COL_PROBLEMS, oneLine(String.join(" | ", problems)));
        csv.append(day, row);

        Boolean on = bool(values.get(COL_HP_ON));
        Log.info("hp_on=" + values.getOrDefault(COL_HP_ON, "?")
                + " flow=" + values.getOrDefault("flow_temp_c", "?")
                + " return=" + values.getOrDefault("return_temp_c", "?")
                + " heat=" + values.getOrDefault(COL_HEAT_KW, "?") + " kW"
                + " today=" + round(kwhToday, 2) + " kWh"
                + (problems.isEmpty() ? "" : " problems: " + String.join(" | ", problems)));
        return on == null || on; // unknown → poll at the faster rate
    }

    private void readBatch(List<Points.Bsb> batch, Map<String, String> out, List<String> problems)
            throws InterruptedException {
        String addr = batch.stream().map(p -> String.valueOf(p.address())).collect(Collectors.joining(","));
        try {
            JsonNode res = api.getJson("/R2/PlantMenuBsb/ReadDataPoints/" + cfg.bsbGateway
                    + "?addresses=" + addr + "&rnd=" + System.currentTimeMillis());
            if (!res.path("ok").asBoolean(false)) {
                problems.add("bsb read: " + res.path("message").asText("not ok"));
                return;
            }
            Map<Long, JsonNode> byAddr = new HashMap<>();
            res.path("data").forEach(d -> byAddr.put(d.path("address").asLong(), d));
            for (Points.Bsb p : batch) {
                out.put(p.column(), formatBsb(byAddr.get(p.address()), p));
            }
        } catch (RemoconClient.HttpStatusException e) {
            if (batch.size() > 1) { // one point the controller rejects breaks the whole batch → read singly
                for (Points.Bsb p : batch) {
                    readBatch(List.of(p), out, problems);
                }
            } else {
                problems.add("bsb " + batch.get(0).line() + ": " + e.getMessage());
            }
        } catch (IOException e) {
            problems.add("bsb read: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** After a restart, continue today's running total from the last row of today's file. */
    /**
     * Continue today's total after a restart: newest total found in any of today's files
     * (after a column change the day continues in _2, _3 … but the total carries over).
     */
    private double resumeKwhToday(LocalDate day) {
        List<Path> files = csv.existingFiles(day);
        for (int k = files.size() - 1; k >= 0; k--) {
            Path f = files.get(k);
            try {
                List<List<String>> recs = Csv.parseAll(Files.readString(f), cfg.csvDelimiter);
                int idx = recs.isEmpty() ? -1 : recs.get(0).indexOf(COL_KWH_TODAY);
                for (int i = recs.size() - 1; i >= 1 && idx >= 0; i--) { // newest row that has a total
                    List<String> r = recs.get(i);
                    Double v = idx < r.size() ? parse(r.get(idx)) : null;
                    if (v != null) {
                        return v;
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("Could not resume today's kWh from " + f + ": " + e.getMessage());
            }
        }
        return 0;
    }

    static String formatBsb(JsonNode d, Points.Bsb p) {
        if (d == null || d.path("anyError").asBoolean(false)) {
            return "";
        }
        JsonNode n = d.get("valueAsNumber");
        if (n != null && !n.isNull()) {
            return num(n, p.scale());
        }
        JsonNode s = d.get("valueAsString");
        return s != null && !s.isNull() ? s.asText() : "";
    }

    static String num(JsonNode n, double scale) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "";
        }
        if (!n.isNumber()) {
            return n.asText();
        }
        BigDecimal v = n.decimalValue();
        if (scale != 1.0) {
            v = v.multiply(BigDecimal.valueOf(scale)).setScale(3, RoundingMode.HALF_UP);
        }
        v = v.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0).toPlainString() : v.toPlainString();
    }

    /** Problems go into a CSV cell: keep them on one line and short. */
    static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }

    static String round(double v, int decimals) {
        BigDecimal b = BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString();
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean bool(String s) {
        Double d = parse(s);
        return d == null ? null : d >= 1;
    }
}
