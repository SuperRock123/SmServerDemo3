package org.toehold.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Log {
    private static final boolean DEBUG = true;
    private static final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void debug(String msg) {
        if (DEBUG) {
            System.out.println("[" + LocalDateTime.now().format(df) + "] [DEBUG] " + msg);
        }
    }

    public static void error(String msg, Throwable t) {
        System.err.println("[" + LocalDateTime.now().format(df) + "] [ERROR] " + msg);
        if (t != null) {
            t.printStackTrace();
        }
    }
}
