package ch.haeberli.elco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class PollerTest {

    static final ObjectMapper M = new ObjectMapper();
    static final ZoneId Z = ZoneId.of("Europe/Zurich");

    /** Fake API answering ReadDataPoints from a mutable address → value map. */
    static class FakeApi implements Api {
        final List<String> calls = new ArrayList<>();
        final Map<Long, Double> values = new HashMap<>(Map.of(
                328993L, 8.8, 2950430L, 22.2, 3212591L, 48.5, 5834029L, 34.7,
                5834039L, 29.7, 332559L, 20.7, 334296L, 6.0, 330165L, 1.0, 5834599L, 37.0, 5834977L, -0.4));
        {
            values.put(334189L, -1.4);
            values.put(5834175L, 67.6);
            values.put(5834184L, 24.6);
            values.put(331050L, 100.0);
            values.put(330147L, 1.0);
        }
        long rejectAddress = -1;

        @Override
        public JsonNode getJson(String path) throws IOException {
            calls.add(path);
            if (!path.startsWith("/R2/PlantMenuBsb/ReadDataPoints/")) {
                throw new IOException("unexpected " + path);
            }
            String a = path.substring(path.indexOf("addresses=") + 10, path.indexOf("&rnd"));
            String[] addrs = a.split(",");
            for (String s : addrs) {
                if (Long.parseLong(s) == rejectAddress) {
                    throw new RemoconClient.HttpStatusException(500, "boom");
                }
            }
            ObjectNode r = M.createObjectNode();
            r.put("ok", true);
            ArrayNode data = r.putArray("data");
            for (String s : addrs) {
                long addr = Long.parseLong(s);
                ObjectNode d = data.addObject();
                d.put("address", addr);
                d.put("anyError", false);
                d.put("valueAsNumber", values.getOrDefault(addr, 0.0));
            }
            return r;
        }
    }

    static Config cfg(Path dir) {
        Properties p = new Properties();
        p.setProperty("email", "x@y.ch");
        p.setProperty("password", "pw");
        p.setProperty("gateway.bsb", "BSBGW");
        p.setProperty("outputDir", dir.toString());
        return Config.of(p);
    }

    static Map<String, String> row(Path file, int index) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<String> h = Points.parseLine(lines.get(0));
        List<String> r = Points.parseLine(lines.get(index));
        assertEquals(h.size(), r.size());
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < h.size(); i++) {
            m.put(h.get(i), r.get(i));
        }
        return m;
    }

    @Test
    void writesRequestedColumnsInOneRequestAndIntegratesKwh(@TempDir Path dir) throws Exception {
        FakeApi api = new FakeApi();
        Poller poller = new Poller(cfg(dir), api, Points.loadBsb(), Z);
        assertEquals(List.of("timestamp", "hp_on", "outdoor_temp_c", "indoor_temp_c", "hot_water_temp_c",
                "flow_temp_c", "flow_setpoint_c", "return_temp_c", "evaporator_temp_c",
                "suction_gas_temp_c", "hot_gas_temp_c", "liquid_temp_c", "compressor_modulation_pct", "dhw_valve", "flow_rate_l_min", "heat_output_kw",
                "heat_kwh_interval", "heat_kwh_today", "poll_problems"), poller.columns());

        Instant t0 = Instant.parse("2026-11-16T19:00:00Z");
        assertTrue(poller.pollAndWrite(t0));
        assertEquals(1, api.calls.size(), "all 15 points in one request");
        api.values.put(334296L, 8.0);
        poller.pollAndWrite(t0.plusSeconds(1800 / 2)); // 15 min later, 6 → 8 kW → avg 7 kW × 0.25 h
        api.values.put(330165L, 0.0);
        api.values.put(334296L, 0.0);
        assertFalse(poller.pollAndWrite(t0.plusSeconds(900 + 600))); // 10 min later, 8 → 0 kW

        Path f = dir.resolve("elco_2026-11-16.csv");
        Map<String, String> r1 = row(f, 1);
        assertEquals("2026-11-16T20:00:00", r1.get("timestamp"));
        assertEquals("1", r1.get("hp_on"));
        assertEquals("8.8", r1.get("outdoor_temp_c"));
        assertEquals("22.2", r1.get("indoor_temp_c"));
        assertEquals("48.5", r1.get("hot_water_temp_c"));
        assertEquals("34.7", r1.get("flow_temp_c"));
        assertEquals("37", r1.get("flow_setpoint_c"));
        assertEquals("29.7", r1.get("return_temp_c"));
        assertEquals("-0.4", r1.get("evaporator_temp_c"));
        assertEquals("-1.4", r1.get("suction_gas_temp_c"));
        assertEquals("67.6", r1.get("hot_gas_temp_c"));
        assertEquals("24.6", r1.get("liquid_temp_c"));
        assertEquals("100", r1.get("compressor_modulation_pct"));
        assertEquals("1", r1.get("dhw_valve"));
        assertEquals("20.7", r1.get("flow_rate_l_min"));
        assertEquals("6", r1.get("heat_output_kw"));
        assertEquals("0", r1.get("heat_kwh_interval"));

        Map<String, String> r2 = row(f, 2);
        assertEquals("1.75", r2.get("heat_kwh_interval"));
        assertEquals("1.75", r2.get("heat_kwh_today"));

        Map<String, String> r3 = row(f, 3);
        assertEquals("0", r3.get("hp_on"));
        assertEquals("0.6667", r3.get("heat_kwh_interval"));
        assertEquals("2.417", r3.get("heat_kwh_today"));
    }

    @Test
    void newFileEachDayAndTotalResetsAtMidnight(@TempDir Path dir) throws Exception {
        Poller poller = new Poller(cfg(dir), new FakeApi(), Points.loadBsb(), Z);
        poller.pollAndWrite(Instant.parse("2026-11-16T22:50:00Z")); // 23:50 local
        poller.pollAndWrite(Instant.parse("2026-11-16T22:58:00Z")); // 23:58
        poller.pollAndWrite(Instant.parse("2026-11-16T23:02:00Z")); // 00:02 next day
        assertTrue(Files.exists(dir.resolve("elco_2026-11-16.csv")));
        Path f2 = dir.resolve("elco_2026-11-17.csv");
        assertEquals(2, Files.readAllLines(f2).size()); // header + 1 row
        assertEquals("0.4", row(f2, 1).get("heat_kwh_today")); // 6 kW × 4 min, total restarted
        assertEquals("0.8", row(dir.resolve("elco_2026-11-16.csv"), 2).get("heat_kwh_today"));
    }

    @Test
    void resumesTodaysTotalAfterRestart(@TempDir Path dir) throws Exception {
        Instant t0 = Instant.parse("2026-11-16T10:00:00Z");
        Poller p1 = new Poller(cfg(dir), new FakeApi(), Points.loadBsb(), Z);
        p1.pollAndWrite(t0);
        p1.pollAndWrite(t0.plusSeconds(600)); // 1 kWh
        Poller p2 = new Poller(cfg(dir), new FakeApi(), Points.loadBsb(), Z); // "restart"
        p2.pollAndWrite(t0.plusSeconds(3600));
        assertEquals("1", row(dir.resolve("elco_2026-11-16.csv"), 3).get("heat_kwh_today"));
    }

    @Test
    void changedColumnsGoToNewFileInsteadOfMixing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("elco_2026-11-16.csv"), "timestamp,old_column\n2026-11-16T08:00:00,1\n");
        Poller p = new Poller(cfg(dir), new FakeApi(), Points.loadBsb(), Z);
        p.pollAndWrite(Instant.parse("2026-11-16T10:00:00Z"));
        assertEquals(2, Files.readAllLines(dir.resolve("elco_2026-11-16.csv")).size(), "old file untouched");
        assertEquals("37", row(dir.resolve("elco_2026-11-16_2.csv"), 1).get("flow_setpoint_c"));
    }

    @Test
    void totalCarriesOverIntoNewFileAfterColumnChange(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("elco_2026-11-16.csv"),
                "timestamp,hp_on,heat_kwh_interval,heat_kwh_today\n2026-11-16T09:00:00,1,0.15,3.079\n");
        Poller p = new Poller(cfg(dir), new FakeApi(), Points.loadBsb(), Z);
        p.pollAndWrite(Instant.parse("2026-11-16T10:00:00Z"));
        assertEquals("3.079", row(dir.resolve("elco_2026-11-16_2.csv"), 1).get("heat_kwh_today"));
    }

    @Test
    void longGapIsNotIntegrated(@TempDir Path dir) throws Exception {
        Poller p = new Poller(cfg(dir), new FakeApi(), Points.loadBsb(), Z);
        Instant t0 = Instant.parse("2026-11-16T10:00:00Z");
        p.pollAndWrite(t0);
        p.pollAndWrite(t0.plusSeconds(3600));
        assertEquals("0", row(dir.resolve("elco_2026-11-16.csv"), 2).get("heat_kwh_interval"));
    }

    @Test
    void rejectedPointFallsBackToSingleReads(@TempDir Path dir) throws Exception {
        FakeApi api = new FakeApi();
        api.rejectAddress = 5834029; // flow temp
        Poller poller = new Poller(cfg(dir), api, Points.loadBsb(), Z);
        poller.pollAndWrite(Instant.parse("2026-11-16T10:00:00Z"));
        Map<String, String> r = row(dir.resolve("elco_2026-11-16.csv"), 1);
        assertEquals("", r.get("flow_temp_c"));
        assertEquals("29.7", r.get("return_temp_c"), "other points still read");
        assertTrue(r.get("poll_problems").contains("bsb 8412"));
    }

    @Test
    void dayStoreHandlesMultiLineErrorFieldsAndSkipsGarbage(@TempDir Path dir) throws Exception {
        // the case seen on the server: an HTML error page (with line breaks) stored in a quoted CSV cell
        Files.writeString(dir.resolve("elco_2026-11-16.csv"),
                "timestamp,hp_on,flow_temp_c,poll_problems\n"
                        + "2026-11-16T21:00:00,1,35.1,\"HTTP 500: <html>\n  <style>\n   body {font-family:Verdana}\n</style>\"\n"
                        + "2026-11-16T21:02:00,1,35.3,\n"
                        + "not a reading,1,2,\n");
        List<Map<String, String>> rows = new DayStore(dir, ',').rows(java.time.LocalDate.parse("2026-11-16"));
        assertEquals(2, rows.size());
        assertEquals("2026-11-16T21:00:00", rows.get(0).get("timestamp"));
        assertEquals("35.3", rows.get(1).get("flow_temp_c"));
        assertTrue(rows.get(0).get("poll_problems").contains("body {font-family"));
    }

    @Test
    void problemTextIsKeptOnOneLineWithoutHtml() {
        assertEquals("HTML error page", RemoconClient.abbreviate("\r\n<!DOCTYPE html>\n<html>…", 200));
        assertEquals("a b c", Poller.oneLine("a\n  b\r\nc"));
    }

    @Test
    void extractsLoginToken() {
        String html = "<form><input name=\"__RequestVerificationToken\" type=\"hidden\" value=\"abc-123_X\" /></form>";
        assertEquals("abc-123_X", RemoconClient.extractVerificationToken(html));
        assertNull(RemoconClient.extractVerificationToken("<html></html>"));
    }

    @Test
    void numberFormatting() throws Exception {
        assertEquals("34.7", Poller.num(M.readTree("34.7"), 1));
        assertEquals("100", Poller.num(M.readTree("100.0"), 1));
        assertEquals("-0.4", Poller.num(M.readTree("-0.4"), 1));
    }
}
