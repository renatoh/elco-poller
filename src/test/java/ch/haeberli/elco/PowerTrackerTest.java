package ch.haeberli.elco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PowerTrackerTest {

    static final ObjectMapper M = new ObjectMapper();
    static final ZoneId Z = ZoneId.of("Europe/Zurich");

    /** Serves a 24-slot series; slot i (counted from the end) of "ground truth" = value for its start time. */
    static class FakeStrom implements Api {
        List<Double> series = new ArrayList<>();
        int calls = 0;

        @Override
        public JsonNode getJson(String path) throws IOException {
            if (!path.contains("consSequencesApi8")) {
                throw new IOException("unexpected " + path);
            }
            calls++;
            ArrayNode arr = M.createArrayNode();
            for (int k : new int[]{24, 25, 1, 2}) {
                ObjectNode s = arr.addObject();
                s.put("k", k);
                s.put("p", 1);
                ArrayNode v = s.putArray("v");
                for (double x : series) {
                    v.add(k == 25 || k == 2 ? 0.0 : x); // hot water usage reported separately (0 here)
                }
                ObjectNode daily = arr.addObject(); // other periods must be ignored
                daily.put("k", k);
                daily.put("p", 2);
                daily.putArray("v").add(99.0);
            }
            return arr;
        }
    }

    /** Distinct, non-zero value per slot start, so alignment mistakes show up. */
    static double truth(LocalDateTime slotStart) {
        return 1 + slotStart.getDayOfMonth() * 0.01 + slotStart.getHour() * 0.1;
    }

    /** The 24 slices the server would publish if its newest slice ended at {@code end}. */
    static List<Double> seriesEndingAt(LocalDateTime end) {
        List<Double> s = new ArrayList<>();
        for (int i = 24; i >= 1; i--) {
            s.add(Math.round(truth(end.minusHours(2L * i)) * 1000) / 1000.0);
        }
        return s;
    }

    static java.time.Instant at(String local) {
        return LocalDateTime.parse(local).atZone(Z).toInstant();
    }

    static Config cfg(Path dir) {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("email", "x@y.ch");
        p.setProperty("password", "pw");
        p.setProperty("gateway.bsb", "BSBGW");
        p.setProperty("gateway.strom", "STROMGW");
        p.setProperty("outputDir", dir.toString());
        return Config.of(p);
    }

    static void assertSlots(Path dir, LocalDate day, int expectedCount) throws IOException {
        List<Map<String, String>> rows = new DayStore(dir, ',').power(day);
        assertEquals(expectedCount, rows.size(), "slots on " + day);
        for (Map<String, String> r : rows) {
            LocalDateTime start = LocalDateTime.parse(r.get("slot_start"));
            assertEquals(Poller.round(truth(start), 3), r.get("electricity_kwh"), "value for " + start);
            assertEquals(start.plusHours(2).toString(), r.get("slot_end"));
        }
    }

    @Test
    void backfills48hThenAddsOneSliceAfterEachEvenHourAndWaitsForLateServer(@TempDir Path dir) throws Exception {
        FakeStrom api = new FakeStrom();
        PowerTracker pt = new PowerTracker(cfg(dir), api, Z);

        api.series = seriesEndingAt(LocalDateTime.parse("2026-11-17T06:00"));
        pt.maybeFetch(at("2026-11-17T07:55:00"));                // first run → 24 slices back to 15th 06:00
        assertSlots(dir, LocalDate.parse("2026-11-15"), 9);       // 06,08,…,22
        assertSlots(dir, LocalDate.parse("2026-11-16"), 12);
        assertSlots(dir, LocalDate.parse("2026-11-17"), 3);       // 00,02,04

        pt.maybeFetch(at("2026-11-17T07:57:00"));                // same 2h window → no request
        assertEquals(1, api.calls);

        pt.maybeFetch(at("2026-11-17T08:01:00"));                // server not updated yet → nothing written
        assertSlots(dir, LocalDate.parse("2026-11-17"), 3);

        api.series = seriesEndingAt(LocalDateTime.parse("2026-11-17T08:00"));
        pt.maybeFetch(at("2026-11-17T08:03:00"));                // now published → 06-08 added
        assertSlots(dir, LocalDate.parse("2026-11-17"), 4);
        int calls = api.calls;
        pt.maybeFetch(at("2026-11-17T09:30:00"));                // done for this window
        assertEquals(calls, api.calls);
    }

    @Test
    void restartWhileServerIsLateDoesNotShiftSlices(@TempDir Path dir) throws Exception {
        FakeStrom api = new FakeStrom();
        api.series = seriesEndingAt(LocalDateTime.parse("2026-11-17T06:00"));
        new PowerTracker(cfg(dir), api, Z).maybeFetch(at("2026-11-17T07:10:00"));

        PowerTracker restarted = new PowerTracker(cfg(dir), api, Z);
        restarted.maybeFetch(at("2026-11-17T08:01:00"));          // still the 06:00 series on the server
        assertSlots(dir, LocalDate.parse("2026-11-17"), 3);       // nothing written at the wrong time
        api.series = seriesEndingAt(LocalDateTime.parse("2026-11-17T08:00"));
        restarted.maybeFetch(at("2026-11-17T08:05:00"));
        assertSlots(dir, LocalDate.parse("2026-11-17"), 4);
    }

    @Test
    void floorEven() {
        assertEquals(LocalDateTime.parse("2026-11-17T06:00"), PowerTracker.floorEven(LocalDateTime.parse("2026-11-17T07:59:59")));
        assertEquals(LocalDateTime.parse("2026-11-17T08:00"), PowerTracker.floorEven(LocalDateTime.parse("2026-11-17T08:00:00")));
        assertEquals(LocalDateTime.parse("2026-11-17T00:00"), PowerTracker.floorEven(LocalDateTime.parse("2026-11-17T01:30")));
    }
}
