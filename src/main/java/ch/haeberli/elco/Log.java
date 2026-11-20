package ch.haeberli.elco;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class Log {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {
    }

    static void info(String msg) {
        System.out.println(LocalDateTime.now().format(F) + " INFO  " + msg);
    }

    static void warn(String msg) {
        System.out.println(LocalDateTime.now().format(F) + " WARN  " + msg);
    }

    static void error(String msg, Throwable t) {
        System.out.println(LocalDateTime.now().format(F) + " ERROR " + msg + (t == null ? "" : " - " + t));
    }
}
