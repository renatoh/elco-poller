package ch.haeberli.elco;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Settings from elco.properties (path = first program argument, default ./elco.properties).
 * Environment variables ELCO_EMAIL / ELCO_PASSWORD override the file.
 */
public final class Config {

    final String baseUrl;
    final String email;
    final String password;
    final String bsbGateway;
    final String stromGateway;  // "strom": electricity consumption
    final boolean powerEnabled;     // BSB gateway ("elco sx 10"): heat pump diagnostics
    final Path outputDir;
    final Duration intervalHpOn;
    final Duration intervalHpOff;
    final Duration requestSpacing;
    final Duration rateLimitWait;
    final char csvDelimiter;
    final boolean webEnabled;
    final String webBind;
    final int webPort;

    private Config(Properties p) {
        baseUrl = p.getProperty("baseUrl", "https://www.remocon-net.remotethermo.com");
        email = env("ELCO_EMAIL", p.getProperty("email", ""));
        password = env("ELCO_PASSWORD", p.getProperty("password", ""));
        bsbGateway = p.getProperty("gateway.bsb", "").trim();
        stromGateway = p.getProperty("gateway.strom", "").trim();
        powerEnabled = Boolean.parseBoolean(p.getProperty("power.enabled", "true")) && !stromGateway.isEmpty();
        outputDir = Path.of(p.getProperty("outputDir", "data"));
        intervalHpOn = Duration.ofSeconds(Long.parseLong(p.getProperty("interval.hpOn.seconds", "115")));
        intervalHpOff = Duration.ofSeconds(Long.parseLong(p.getProperty("interval.hpOff.seconds", "600")));
        requestSpacing = Duration.ofMillis(Long.parseLong(p.getProperty("requestSpacing.ms", "3000")));
        rateLimitWait = Duration.ofSeconds(Long.parseLong(p.getProperty("rateLimitWait.seconds", "120")));
        String d = p.getProperty("csv.delimiter", ",");
        csvDelimiter = d.isEmpty() ? ',' : d.charAt(0);
        webEnabled = Boolean.parseBoolean(p.getProperty("web.enabled", "true"));
        webBind = p.getProperty("web.bind", "127.0.0.1");
        webPort = Integer.parseInt(p.getProperty("web.port", "8083"));
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("email/password missing (elco.properties or ELCO_EMAIL / ELCO_PASSWORD)");
        }
        if (bsbGateway.isEmpty()) {
            throw new IllegalStateException("gateway.bsb missing in elco.properties (gateway id from /api/v2/remote/plants/lite)");
        }
    }

    static Config load(Path file) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        return new Config(p);
    }

    static Config of(Properties p) {
        return new Config(p);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
