package com.droidenx.clouseau.example;

import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.SourceSetting;
import org.pf4j.Extension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * An example {@link LogSourceProvider}: a source that makes up logfmt lines at a steady
 * rate, so you can try live sources without a server. A real provider (Loki, CloudWatch,
 * a message queue) has the same shape: declare settings, check them in {@code create},
 * connect in {@link LogSource#open}, and make {@link LogSource#close} unblock it.
 *
 * <p>The lines it writes are in logfmt, so the {@link LogfmtParser} in this same plugin
 * auto-detects them.
 */
@Extension
public class DemoStreamSource implements LogSourceProvider {

    @Override
    public String getName() {
        return "Demo log stream (example plugin)";
    }

    @Override
    public List<SourceSetting> settings() {
        return List.of(
                SourceSetting.text("service", "Service name").withDefault("checkout"),
                SourceSetting.number("interval", "Milliseconds between lines").withDefault("500"),
                SourceSetting.choice("mix", "Levels", List.of("mostly info", "noisy", "errors only")),
                SourceSetting.number("count", "Stop after this many lines").optional());
    }

    @Override
    public LogSource create(Map<String, String> values) {
        String service = values.get("service");
        long interval = Long.parseLong(values.get("interval"));
        if (interval < 10 || interval > 60_000)
            throw new IllegalArgumentException("Milliseconds between lines must be between 10 and 60000.");
        long count = values.containsKey("count") ? Long.parseLong(values.get("count")) : Long.MAX_VALUE;
        if (count < 1) throw new IllegalArgumentException("Stop after must be at least 1 line.");
        return new Stream(service, interval, values.get("mix"), count);
    }

    /** One running stream. Holds all per-connection state; the provider holds none. */
    static final class Stream implements LogSource {

        private static final String[] MESSAGES = {
                "request handled", "cache refreshed", "order created", "payment authorised",
                "slow query", "retrying call", "connection reset", "request failed" };

        private final String service;
        private final long intervalMillis;
        private final String mix;
        private final long count;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final Random random = new Random();

        Stream(String service, long intervalMillis, String mix, long count) {
            this.service = service;
            this.intervalMillis = intervalMillis;
            this.mix = mix;
            this.count = count;
        }

        @Override
        public String getName() {
            return "demo: " + service;
        }

        /** Writes a line every interval until {@link #close} or {@code count} lines. */
        @Override
        public void open(Consumer<String> lineConsumer) throws InterruptedException {
            for (long n = 1; n <= count; n++) {
                if (closed.getCount() == 0) return;   // closed before or while starting
                lineConsumer.accept(line(n));
                // Waiting on the latch, not sleeping, lets close() end the stream at once.
                if (n < count && closed.await(intervalMillis, TimeUnit.MILLISECONDS)) return;
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private String line(long n) {
            String level = level();
            String msg = MESSAGES[random.nextInt(MESSAGES.length)];
            return "time=" + Instant.now() + " level=" + level + " logger=" + service
                    + " msg=\"" + msg + "\" seq=" + n + " duration_ms=" + random.nextInt(2000);
        }

        private String level() {
            int r = random.nextInt(100);
            return switch (mix) {
                case "errors only" -> r < 80 ? "error" : "fatal";
                case "noisy"       -> r < 40 ? "debug" : r < 70 ? "info" : r < 90 ? "warn" : "error";
                default            -> r < 85 ? "info" : r < 95 ? "warn" : "error";
            };
        }
    }
}
