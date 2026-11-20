package ch.haeberli.elco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Mock of the portal: checks the anti-forgery cookie + JSON login, then a session cookie; simulates expiry. */
class RemoconClientTest {

    @Test
    void logsInReadsAndReLogsInAfterExpiry() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger expireNext = new AtomicInteger(0);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String cookie = String.valueOf(ex.getRequestHeaders().getFirst("Cookie"));
            if (path.equals("/R2/Account/Login") && ex.getRequestMethod().equals("GET")) {
                ex.getResponseHeaders().add("Set-Cookie", "__RequestVerificationToken=srvtok; path=/");
                reply(ex, 200, "<form><input name=\"__RequestVerificationToken\" type=\"hidden\" value=\"formtok\" /></form>");
            } else if (path.equals("/R2/Account/Login")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode j = new ObjectMapper().readTree(body);
                boolean ok = cookie.contains("__formRequestVerificationToken=formtok") && cookie.contains("srvtok")
                        && "x@y.ch".equals(j.path("email").asText()) && "pw".equals(j.path("password").asText())
                        && "json".equals(ex.getRequestHeaders().getFirst("Ajax-Request"));
                if (ok) {
                    logins.incrementAndGet();
                    ex.getResponseHeaders().add("Set-Cookie", "auth=s" + logins.get() + "; path=/");
                }
                reply(ex, 200, "{\"ok\":" + ok + "}");
            } else if (!cookie.contains("auth=s")) {
                reply(ex, path.startsWith("/api") ? 405 : 403, "<html>nope</html>");
            } else if (path.equals("/api/v2/remote/plants/lite")) {
                reply(ex, 200, "[]");
            } else if (path.startsWith("/R2/PlantMenuBsb/ReadDataPoints/")) {
                if (expireNext.getAndSet(0) == 1) {
                    reply(ex, 403, "<html>expired</html>");
                } else {
                    reply(ex, 200, "{\"ok\":true,\"data\":[{\"address\":328993,\"valueAsNumber\":8.8}]}");
                }
            } else {
                reply(ex, 404, "");
            }
        });
        srv.start();
        try {
            Properties p = new Properties();
            p.setProperty("baseUrl", "http://127.0.0.1:" + srv.getAddress().getPort());
            p.setProperty("email", "x@y.ch");
            p.setProperty("password", "pw");
            p.setProperty("gateway.bsb", "BSBGW");
            p.setProperty("requestSpacing.ms", "0");
            RemoconClient c = new RemoconClient(Config.of(p));

            JsonNode r = c.getJson("/R2/PlantMenuBsb/ReadDataPoints/GW?addresses=328993");
            assertEquals(1, logins.get());
            assertEquals("8.8", r.path("data").get(0).path("valueAsNumber").asText());

            expireNext.set(1); // server drops the session once → client must log in again and retry
            r = c.getJson("/R2/PlantMenuBsb/ReadDataPoints/GW?addresses=328993");
            assertEquals(2, logins.get());
            assertTrue(r.path("ok").asBoolean());
        } finally {
            srv.stop(0);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }
}
