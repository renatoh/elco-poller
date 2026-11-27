package ch.haeberli.elco;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Small dashboard served from the same JVM (JDK built-in HTTP server, no dependencies).
 * GET /                      → dashboard page
 * GET /api/day?date=YYYY-MM-DD → that day's readings, 2h electricity slices and defrost starts (default: today)
 * GET /api/days              → dates that have data
 */
final class WebServer {

    private final HttpServer server;
    private final DayStore store;
    private final ZoneId zone;
    private final ObjectMapper json = new ObjectMapper();
    private final byte[] page;

    WebServer(Config cfg, ZoneId zone) throws IOException {
        this.store = new DayStore(cfg.outputDir, cfg.csvDelimiter);
        this.zone = zone;
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                throw new IOException("Missing resource /web/index.html");
            }
            this.page = in.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress(cfg.webBind, cfg.webPort), 0);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "web");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> send(ex, 200, "text/html; charset=utf-8", page);
                case "/api/days" -> sendJson(ex, Map.of("days", store.days(), "today", today().toString()));
                case "/api/day" -> day(ex);
                default -> send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.error("web " + ex.getRequestURI(), e);
            send(ex, 500, "text/plain", "error".getBytes(StandardCharsets.UTF_8));
        } finally {
            ex.close();
        }
    }

    private void day(HttpExchange ex) throws IOException {
        String q = query(ex).get("date");
        LocalDate d;
        try {
            d = q == null || q.isBlank() ? today() : LocalDate.parse(q);
        } catch (DateTimeParseException e) {
            send(ex, 400, "text/plain", "bad date".getBytes(StandardCharsets.UTF_8));
            return;
        }
        List<Map<String, String>> rows = store.rows(d);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", d.toString());
        out.put("today", today().toString());
        out.put("rows", rows);
        out.put("power", store.power(d));
        // defrosts of the previous day too, so "since last defrost" and the cycle length work across midnight
        List<Map<String, String>> both = new java.util.ArrayList<>(store.rows(d.minusDays(1)));
        both.addAll(rows);
        out.put("defrosts", Defrosts.starts(both));
        out.put("defrostThresholdKw", Defrosts.THRESHOLD_KW);
        sendJson(ex, out);
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private void sendJson(HttpExchange ex, Object body) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", json.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
