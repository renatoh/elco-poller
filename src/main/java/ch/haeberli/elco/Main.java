package ch.haeberli.elco;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Elco Remocon-NET poller (read-only).
 *
 * Usage: java -jar elco-poller.jar [path/to/elco.properties] [--once] [--no-web]
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path props = Path.of("elco.properties");
        boolean once = false;
        boolean noWeb = false;
        for (String a : args) {
            if ("--once".equals(a)) {
                once = true;
            } else if ("--no-web".equals(a)) {
                noWeb = true;
            } else {
                props = Path.of(a);
            }
        }
        Config cfg = Config.load(props);
        RemoconClient client = new RemoconClient(cfg);
        Poller poller = new Poller(cfg, client, Points.loadBsb(), ZoneId.systemDefault());
        PowerTracker power = cfg.powerEnabled ? new PowerTracker(cfg, client, ZoneId.systemDefault()) : null;
        if (cfg.webEnabled && !once && !noWeb) {
            new WebServer(cfg, ZoneId.systemDefault()).start();
            Log.info("Dashboard: http://" + ("0.0.0.0".equals(cfg.webBind) ? "localhost" : cfg.webBind) + ":" + cfg.webPort + "/");
        }
        Log.info("Writing CSV files to " + cfg.outputDir.toAbsolutePath()
                + " - interval " + cfg.intervalHpOn.toSeconds() + " s (HP on) / "
                + cfg.intervalHpOff.toSeconds() + " s (HP off)");

        while (true) {
            Instant start = Instant.now();
            Duration next;
            try {
                boolean on = poller.pollAndWrite(start);
                next = on ? cfg.intervalHpOn : cfg.intervalHpOff;
                if (power != null) {
                    power.maybeFetch(start); // only does something on the first poll after an even hour
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.error("Poll failed", e);
                next = cfg.intervalHpOn; // retry soon
            }
            if (once) {
                return;
            }
            long sleep = Duration.between(Instant.now(), start.plus(next)).toMillis();
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }
    }
}
