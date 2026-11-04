package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class FileChangeWatcherTest {

    @TempDir Path dir;

    private static void appendLater(Path file, long delayMillis) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                Files.writeString(file, "more\n", StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @Test
    void wakesUpWhenTheFileIsAppendedTo() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "first\n");
        try (FileChangeWatcher watcher = FileChangeWatcher.watch(log)) {
            assertThat(watcher.isNotified()).isTrue();
            appendLater(log, 200);

            long start = System.nanoTime();
            boolean changed = watcher.awaitChange(10_000);
            long tookMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(changed).isTrue();
            assertThat(tookMillis).isLessThan(5_000);   // notified, not timed out
        }
    }

    @Test
    void ignoresOtherFilesInTheFolder() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "first\n");
        try (FileChangeWatcher watcher = FileChangeWatcher.watch(log)) {
            appendLater(dir.resolve("other.log"), 50);

            assertThat(watcher.awaitChange(700)).isFalse();
        }
    }

    @Test
    void anInterruptEndsTheWait() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "first\n");
        AtomicBoolean interrupted = new AtomicBoolean();
        try (FileChangeWatcher watcher = FileChangeWatcher.watch(log)) {
            Thread waiter = new Thread(() -> {
                try {
                    watcher.awaitChange(60_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            });
            waiter.start();
            Thread.sleep(200);
            waiter.interrupt();
            waiter.join(5_000);

            assertThat(waiter.isAlive()).isFalse();
            assertThat(interrupted).isTrue();
        }
    }

    @Test
    void aFolderThatCantBeWatchedFallsBackToTheTimeout() throws IOException, InterruptedException {
        Path missing = dir.resolve("no-such-folder").resolve("app.log");
        try (FileChangeWatcher watcher = FileChangeWatcher.watch(missing)) {
            assertThat(watcher.isNotified()).isFalse();
            assertThat(watcher.awaitChange(100)).isFalse();
        }
    }
}
