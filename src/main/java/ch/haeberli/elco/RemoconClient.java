package ch.haeberli.elco;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only client for the Remocon-NET web portal + app API.
 * Logs in like the web page (cookie session); the same cookies also work for /api/v2.
 * Only GET requests are sent after login.
 */
public class RemoconClient implements Api {

    private static final Pattern RVT = Pattern.compile(
            "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"");

    private final Config cfg;
    private final ObjectMapper json = new ObjectMapper();
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private long lastRequestAt = 0;
    private boolean loggedIn = false;

    public RemoconClient(Config cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Thrown for HTTP errors that are not session/rate-limit related (e.g. 500 for an unknown data point). */
    public static class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpStatusException(int status, String msg) {
            super("HTTP " + status + ": " + msg);
            this.status = status;
        }
    }

    public synchronized void login() throws IOException, InterruptedException {
        loggedIn = false;
        HttpResponse<String> page = send(HttpRequest.newBuilder(uri("/R2/Account/Login")).GET(), false);
        String token = extractVerificationToken(page.body());
        if (token == null) {
            throw new IOException("Login page: __RequestVerificationToken not found (HTTP " + page.statusCode() + ")");
        }
        // the page script copies the hidden token into this cookie before posting
        HttpCookie c = new HttpCookie("__formRequestVerificationToken", token);
        c.setPath("/");
        c.setVersion(0);
        cookies.getCookieStore().add(URI.create(cfg.baseUrl), c);

        Map<String, Object> body = Map.of(
                "email", cfg.email,
                "password", cfg.password,
                "rememberMe", false,
                "language", "en-US",
                "theme", "false");
        HttpRequest.Builder post = HttpRequest.newBuilder(uri("/R2/Account/Login"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> res = send(post, true);
        String b = res.body() == null ? "" : res.body().trim();
        if (res.statusCode() != 200 || !b.startsWith("{") || !json.readTree(b).path("ok").asBoolean(false)) {
            throw new IOException("Login failed (HTTP " + res.statusCode() + "): " + abbreviate(b, 300));
        }
        // verify the session works for the API
        HttpResponse<String> check = send(HttpRequest.newBuilder(uri("/api/v2/remote/plants/lite")).GET(), true);
        if (check.statusCode() != 200 || !check.body().trim().startsWith("[")) {
            throw new IOException("Login seemed OK but API check failed (HTTP " + check.statusCode() + ")");
        }
        loggedIn = true;
        Log.info("Logged in to Remocon-NET");
    }

    /** GET returning JSON; re-logs in on expired session, waits on rate limit (429). */
    @Override
    public JsonNode getJson(String pathAndQuery) throws IOException, InterruptedException {
        if (!loggedIn) {
            login();
        }
        boolean relogged = false;
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpResponse<String> res = send(HttpRequest.newBuilder(uri(pathAndQuery)).GET(), true);
            int s = res.statusCode();
            String b = res.body() == null ? "" : res.body().trim();
            if (s == 429) {
                Log.warn("Rate limited (429) - waiting " + cfg.rateLimitWait.toSeconds() + " s");
                Thread.sleep(cfg.rateLimitWait.toMillis());
                continue;
            }
            // observed without a valid session: 403 (portal /R2), 405 (/api/v2); also redirects / HTML login page
            if (isAuthFailure(s, b)) {
                if (relogged) {
                    throw new HttpStatusException(s, "still not authorised after re-login: " + abbreviate(b, 120));
                }
                Log.warn("Session expired (HTTP " + s + ") - logging in again");
                login();
                relogged = true;
                continue;
            }
            if (s != 200) {
                throw new HttpStatusException(s, abbreviate(b, 200));
            }
            return b.isEmpty() ? json.nullNode() : json.readTree(b);
        }
        throw new IOException("Giving up on " + pathAndQuery + " after repeated rate limit / re-login");
    }

    private HttpResponse<String> send(HttpRequest.Builder rb, boolean ajax) throws IOException, InterruptedException {
        long wait = lastRequestAt + cfg.requestSpacing.toMillis() - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        rb.timeout(Duration.ofSeconds(90))
                .header("User-Agent", "Mozilla/5.0 (Macintosh) elco-poller/1.0")
                .header("Accept", ajax ? "application/json, text/javascript, */*; q=0.01" : "text/html");
        if (ajax) {
            rb.header("Ajax-Request", "json").header("X-Requested-With", "XMLHttpRequest");
        }
        try {
            return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private URI uri(String pathAndQuery) {
        return URI.create(cfg.baseUrl + pathAndQuery);
    }

    static boolean isAuthFailure(int status, String body) {
        return status == 401 || status == 403 || status == 405 || status == 302 || status == 303
                || (status == 200 && body.startsWith("<"));
    }

    static String extractVerificationToken(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = RVT.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    static String abbreviate(String s, int max) {
        String t = s.trim();
        if (t.startsWith("<")) {
            return "HTML error page";
        }
        t = t.replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
