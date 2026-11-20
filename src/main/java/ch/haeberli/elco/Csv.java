package ch.haeberli.elco;

import java.util.ArrayList;
import java.util.List;

/** Minimal CSV line parser: quoted fields, doubled quotes, configurable delimiter. */
final class Csv {

    private Csv() {
    }

    /** Parses a whole CSV document; quoted fields may contain delimiters, quotes and line breaks. */
    static List<List<String>> parseAll(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else if (c == '"') {
                    q = false;
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                q = true;
            } else if (c == delim) {
                row.add(sb.toString());
                sb.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(sb.toString());
                sb.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0 || !row.isEmpty()) {
            row.add(sb.toString());
            rows.add(row);
        }
        return rows;
    }

    static List<String> parseLine(String line, char delim) {
        List<String> f = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else if (c == '"') {
                    q = false;
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                q = true;
            } else if (c == delim) {
                f.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        f.add(sb.toString());
        return f;
    }
}
