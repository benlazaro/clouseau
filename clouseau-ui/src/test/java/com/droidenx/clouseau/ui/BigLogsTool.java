package com.droidenx.clouseau.ui;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/**
 * Dev-only: writes large logs for checking speed by hand (run by the {@code bigLogs} Gradle
 * task). Each file has its own fixed seed, so the files come out the same every time.
 * <ul>
 *   <li>{@code node1.log} to {@code node4.log}: four services' logs over the same hours, to
 *       open together as one merged view.</li>
 *   <li>{@code out-of-order.log}: about 5% of its lines are stamped up to 10 minutes early,
 *       so sorting by Timestamp really has to reorder rows.</li>
 * </ul>
 * Every 50th entry has a one-line stack trace under it.
 * <p>
 * Arguments: the output folder, and how many entries per file (default 1,000,000, about
 * 125 MB a file).
 */
public final class BigLogsTool {

    private static final String[] LEVELS = {"INFO", "DEBUG", "WARN", "ERROR"};

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        int entries = args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000;
        Files.createDirectories(dir);
        for (int s = 1; s <= 4; s++) write(dir.resolve("node" + s + ".log"), s, entries, false);
        write(dir.resolve("out-of-order.log"), 5, entries, true);
        System.out.println("Wrote 5 logs of " + entries + " entries to " + dir.toAbsolutePath());
    }

    private static void write(Path file, int service, int entries, boolean outOfOrder) throws IOException {
        Random random = new Random(service);
        long t = 0;
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < entries; i++) {
                t += random.nextInt(40);
                long at = t;
                if (outOfOrder && random.nextInt(100) < 5) at = Math.max(0, t - random.nextInt(600_000));
                long sec = at / 1000;
                out.write(String.format(Locale.ROOT, "2026-03-27 %02d:%02d:%02d.%03d [worker-%d] %s com.example.svc%d.Service"
                                + " - request %d handled in %d ms for customer CUST-%d\n",
                        8 + sec / 3600, sec / 60 % 60, sec % 60, at % 1000, random.nextInt(8),
                        LEVELS[random.nextInt(LEVELS.length)], service, i, random.nextInt(500),
                        random.nextInt(100_000)));
                if (i % 50 == 0) out.write("\tat com.example.svc.Service.handle(Service.java:" + i % 900 + ")\n");
            }
        }
    }
}
