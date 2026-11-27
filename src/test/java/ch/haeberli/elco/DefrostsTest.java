package ch.haeberli.elco;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefrostsTest {

    private static Map<String, String> r(String ts, String on, String kw) {
        return Map.of("timestamp", ts, "hp_on", on, "heat_output_kw", kw);
    }

    @Test
    void findsDefrostStartsAndIgnoresSmallNegativeValues() {
        List<Map<String, String>> rows = new ArrayList<>(List.of(
                r("2026-11-20T08:41:34", "1", "6.61"),
                r("2026-11-20T08:43:29", "1", "-0.4"),   // small negative while running: not a defrost
                r("2026-11-20T08:45:24", "1", "-14.6"),  // defrost starts
                r("2026-11-20T08:47:19", "1", "-3.2"),   // same defrost
                r("2026-11-20T08:49:14", "1", "5.65"),
                r("2026-11-20T09:00:00", "0", "-2.0"),   // heat pump off: not a defrost
                r("2026-11-20T09:50:00", "1", "-12.0"),  // second defrost
                r("2026-11-20T09:51:00", "1", "")        // failed poll: ignored
        ));
        java.util.Collections.reverse(rows); // order must not matter
        assertEquals(List.of("2026-11-20T08:45:24", "2026-11-20T09:50:00"), Defrosts.starts(rows));
    }

    @Test
    void longGapStartsANewDefrost() {
        List<Map<String, String>> rows = List.of(
                r("2026-11-19T23:58:00", "1", "-10"),
                r("2026-11-20T00:30:00", "1", "-10"));
        assertEquals(List.of("2026-11-19T23:58:00", "2026-11-20T00:30:00"), Defrosts.starts(rows));
    }
}
