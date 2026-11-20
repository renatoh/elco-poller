package ch.haeberli.elco;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** BSB data point definitions loaded from the bundled bsb_points.csv. */
final class Points {

    /** BSB data point on the "elco sx 10" gateway. scale converts raw values (e.g. seconds → hours). */
    record Bsb(long address, int line, String column, String name, String unit, double scale) {
    }

    private Points() {
    }

    static List<Bsb> loadBsb() throws IOException {
        List<Bsb> out = new ArrayList<>();
        for (List<String> r : readCsv("/bsb_points.csv")) {
            out.add(new Bsb(Long.parseLong(r.get(0)), Integer.parseInt(r.get(1)), r.get(2), r.get(3), r.get(4),
                    Double.parseDouble(r.get(6))));
        }
        return out;
    }

    /** Minimal CSV reader (handles quoted fields with commas / doubled quotes). Skips the header row. */
    static List<List<String>> readCsv(String resource) throws IOException {
        InputStream in = Points.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("Missing resource " + resource);
        }
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (!line.isBlank()) {
                    rows.add(parseLine(line));
                }
            }
        }
        return rows;
    }

    static List<String> parseLine(String line) {
        return Csv.parseLine(line, ',');
    }
}
