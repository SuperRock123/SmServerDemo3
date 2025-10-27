package org.toehold.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class Log {
    private static final boolean DEBUG = true;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String LOG_DIR = "logs";
    private static final int RETAIN_DAYS = 10;

    private static String currentDay = null;
    private static BufferedWriter writer = null;

    private static Path getLogDir() {
        return Paths.get(System.getProperty("user.dir"), LOG_DIR);
    }

    private static synchronized void ensureWriter() {
        String today = LocalDate.now().format(DAY);
        if (writer == null || !today.equals(currentDay)) {
            closeWriterQuietly();
            Path dir = getLogDir();
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
            Path file = dir.resolve(today + ".txt");
            try {
                writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                currentDay = today;
            } catch (IOException e) {
                System.err.println("[" + LocalDateTime.now().format(TS) + "] [ERROR] 打开日志文件失败: " + e.getMessage());
                writer = null;
            }
            enforceRetention(dir);
        }
    }

    private static void enforceRetention(Path dir) {
        try {
            List<Path> files = Files.list(dir)
                    .filter(Files::isRegularFile)
                    .map(p -> {
                        String n = p.getFileName().toString();
                        if (n.endsWith(".txt")) {
                            String datePart = n.substring(0, n.length() - 4);
                            try { LocalDate.parse(datePart, DAY); return p; } catch (Exception ignored) {}
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .sorted((a,b) -> {
                        String an = a.getFileName().toString();
                        String bn = b.getFileName().toString();
                        LocalDate ad = LocalDate.parse(an.substring(0, an.length()-4), DAY);
                        LocalDate bd = LocalDate.parse(bn.substring(0, bn.length()-4), DAY);
                        return ad.compareTo(bd);
                    })
                    .collect(Collectors.toList());
            int excess = files.size() - RETAIN_DAYS;
            for (int i = 0; i < excess; i++) {
                try { Files.deleteIfExists(files.get(i)); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private static void closeWriterQuietly() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    public static void debug(String msg) {
        if (DEBUG) { write("DEBUG", msg, null); }
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private static synchronized void write(String level, String msg, Throwable t) {
        ensureWriter();
        String line = "[" + LocalDateTime.now().format(TS) + "] [" + level + "] " + msg;
        if (DEBUG && "DEBUG".equals(level)) { System.out.println(line); }
        if ("ERROR".equals(level)) { System.err.println(line); }
        if (writer != null) {
            try {
                writer.write(line);
                writer.newLine();
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    t.printStackTrace(pw);
                    pw.flush();
                    writer.write(sw.toString());
                    writer.newLine();
                }
                writer.flush();
            } catch (IOException e) {
                System.err.println("[" + LocalDateTime.now().format(TS) + "] [ERROR] 写入日志失败: " + e.getMessage());
            }
        }
    }

    static {
        try { Files.createDirectories(getLogDir()); } catch (IOException e) {
            System.err.println("[" + LocalDateTime.now().format(TS) + "] [ERROR] 创建日志目录失败: " + e.getMessage());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Log::closeWriterQuietly));
    }
}
