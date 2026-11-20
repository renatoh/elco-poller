package ch.haeberli.elco;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Appends rows to &lt;dir&gt;/&lt;prefix&gt;_YYYY-MM-DD.csv - a new file per day, header written once per file. */
final class DailyCsvWriter {

    private final Path dir;
    private final String prefix;
    private final char delim;
    private final List<String> columns;

    DailyCsvWriter(Path dir, String prefix, char delim, List<String> columns) {
        this.dir = dir;
        this.prefix = prefix;
        this.delim = delim;
        this.columns = columns;
    }

    /**
     * Today's file. If a file for the day already exists with a different header (columns changed between
     * versions), continue in prefix_DATE_2.csv, _3.csv … instead of mixing formats.
     */
    Path fileFor(LocalDate day) throws IOException {
        String header = join(columns);
        for (int n = 1; ; n++) {
            Path f = dir.resolve(prefix + "_" + day + (n == 1 ? "" : "_" + n) + ".csv");
            if (!Files.exists(f) || Files.size(f) == 0) {
                return f;
            }
            try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
                if (header.equals(lines.findFirst().orElse(""))) {
                    return f;
                }
            }
        }
    }

    /** All existing files of that day, oldest first (prefix_DATE.csv, prefix_DATE_2.csv, …). */
    List<Path> existingFiles(LocalDate day) {
        List<Path> out = new java.util.ArrayList<>();
        for (int n = 1; ; n++) {
            Path f = dir.resolve(prefix + "_" + day + (n == 1 ? "" : "_" + n) + ".csv");
            if (!Files.exists(f)) {
                return out;
            }
            out.add(f);
        }
    }

    void append(LocalDate day, Map<String, String> row) throws IOException {
        Files.createDirectories(dir);
        Path f = fileFor(day);
        boolean newFile = !Files.exists(f) || Files.size(f) == 0;
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                w.write(join(columns));
                w.write('\n');
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(delim);
                }
                sb.append(escape(row.getOrDefault(columns.get(i), "")));
            }
            w.write(sb.toString());
            w.write('\n');
        }
    }

    private String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(delim);
            }
            sb.append(escape(values.get(i)));
        }
        return sb.toString();
    }

    String escape(String v) {
        if (v == null) {
            return "";
        }
        boolean needs = v.indexOf(delim) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return needs ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }
}
