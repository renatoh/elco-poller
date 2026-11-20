package ch.haeberli.elco;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the daily CSV files back:
 * readings  elco_DATE.csv (+ elco_DATE_2.csv … written after a column change),
 * power     elco_power_DATE.csv (2-hour electricity slices).
 */
final class DayStore {

    private static final Pattern READINGS = Pattern.compile("elco_(\\d{4}-\\d{2}-\\d{2})(?:_(\\d+))?\\.csv");
    private static final Pattern POWER = Pattern.compile("elco_power_(\\d{4}-\\d{2}-\\d{2})(?:_(\\d+))?\\.csv");
    private static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    private static final Pattern SLOT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");

    private final Path dir;
    private final char delim;

    DayStore(Path dir, char delim) {
        this.dir = dir;
        this.delim = delim;
    }

    /** Dates that have readings, ascending. */
    List<String> days() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                Matcher m = READINGS.matcher(p.getFileName().toString());
                if (m.matches()) {
                    out.add(m.group(1));
                }
            });
        }
        return new ArrayList<>(out);
    }

    /** All readings of a day as column → value maps, sorted by timestamp. */
    List<Map<String, String>> rows(LocalDate day) throws IOException {
        return read(READINGS, day, "timestamp", TS);
    }

    /** Electricity slices that start on this day, sorted by slot_start. */
    List<Map<String, String>> power(LocalDate day) throws IOException {
        return read(POWER, day, "slot_start", SLOT);
    }

    private List<Map<String, String>> read(Pattern filePattern, LocalDate day, String keyColumn, Pattern keyFormat)
            throws IOException {
        List<Map<String, String>> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                Matcher m = filePattern.matcher(p.getFileName().toString());
                if (m.matches() && m.group(1).equals(day.toString())) {
                    files.add(p);
                }
            });
        }
        for (Path f : files) {
            List<List<String>> recs = Csv.parseAll(Files.readString(f, StandardCharsets.UTF_8), delim);
            if (recs.isEmpty()) {
                continue;
            }
            List<String> header = recs.get(0);
            for (int i = 1; i < recs.size(); i++) {
                List<String> v = recs.get(i);
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.size() && c < v.size(); c++) {
                    row.put(header.get(c), v.get(c));
                }
                if (keyFormat.matcher(row.getOrDefault(keyColumn, "")).matches()) { // skip anything malformed
                    out.add(row);
                }
            }
        }
        out.sort((a, b) -> a.getOrDefault(keyColumn, "").compareTo(b.getOrDefault(keyColumn, "")));
        return out;
    }
}
