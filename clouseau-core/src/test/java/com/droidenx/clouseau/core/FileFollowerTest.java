package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FileFollowerTest {

    @TempDir Path dir;

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final AtomicLong position = new AtomicLong(-1);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private Thread follow(Path file, long start) {
        Thread t = new Thread(() -> {
            try {
                FileFollower.follow(file, start, position::set, lines::add, cancelled::get, 100);
            } catch (InterruptedException ignored) {
                // the test interrupted
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private void awaitLines(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (lines.size() < count && System.nanoTime() < deadline) Thread.sleep(20);
    }

    @Test
    void readsOnlyWhatIsAppendedAfterTheStart() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "old\n");
        Thread t = follow(log, Files.size(log));

        append(log, "new 1\nnew 2\n");
        awaitLines(2);
        cancelled.set(true);
        t.join(5_000);

        assertThat(lines).containsExactly("new 1", "new 2");
        assertThat(position.get()).isEqualTo(Files.size(log));
        assertThat(t.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    @Test
    void startsAgainAtTheTopWhenTheFileShrinks() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "a fairly long first line\n");
        Thread t = follow(log, Files.size(log));

        Files.writeString(log, "rotated\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        awaitLines(1);
        cancelled.set(true);
        t.join(5_000);

        assertThat(lines).containsExactly("rotated");
    }

    @Test
    void interruptEndsTheWaitAtOnce() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "");
        Thread t = new Thread(() -> {
            try {
                FileFollower.follow(log, 0, p -> {}, l -> {}, () -> false, 60_000);
            } catch (InterruptedException ignored) {
                // expected
            }
        });
        t.start();
        Thread.sleep(200);

        long start = System.nanoTime();
        t.interrupt();
        t.join(5_000);

        assertThat(t.isAlive()).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000);
    }
}
