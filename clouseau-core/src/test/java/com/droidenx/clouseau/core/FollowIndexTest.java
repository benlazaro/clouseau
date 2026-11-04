package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-16 step 5: following an indexed file. Appended lines extend the index the way the
 * first pass builds it, a file renamed away keeps its rows readable, and a file truncated
 * in place keeps its rows' index columns with a placeholder message.
 */
class FollowIndexTest {

    private static final LineParser PARSER = new LineParser(Optional.empty(), List.of(new RegexLogParser("t",
            Pattern.compile("(?<timestamp>\\S+ \\S+) (?<level>[A-Z]+) (?<logger>\\S+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss")), null);
    private static final String LOST = "gone (rotated)";

    @TempDir Path dir;

    private EntryIndex index;
    private IndexedEntries entries;
    /** Rows the follower has added: the test may read rows below this. */
    private final AtomicInteger published = new AtomicInteger();
    private final List<FileFollower.Rotation> rotations = new CopyOnWriteArrayList<>();
    private final List<Boolean> lostText = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private Thread follower;

    @AfterEach
    void stop() throws InterruptedException {
        cancelled.set(true);
        if (follower != null) {
            follower.interrupt();
            follower.join(5_000);
        }
        if (entries != null) entries.close();
        assertThat(failure.get()).isNull();
    }

    private static String line(int i, String level, String text) {
        return "2026-11-04 10:00:" + String.format("%02d", i % 60) + " " + level + " com.acme.L" + i % 3 + " " + text;
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Indexes {@code file} as a load does, then follows it as a tab does (LogPanel). */
    private void loadAndFollow(Path file) throws IOException {
        index = new EntryIndex();
        long end;
        try (InputStream in = Files.newInputStream(file)) {
            end = LineScanner.scan(in, (o, l, t) -> index.add(o, l, PARSER.parse(t)), () -> false);
        }
        entries = IndexedEntries.open(file, index, PARSER, LOST);
        published.set(index.size());

        follower = new Thread(() -> {
            try {
                FileFollower.follow(file, end, new FileFollower.Listener() {
                    @Override public void line(long offset, int length, String text) {
                        index.add(offset, length, PARSER.parse(text));
                        published.incrementAndGet();
                    }
                    @Override public void rotated(FileFollower.Rotation how) {
                        rotations.add(how);
                        lostText.add(entries.rotated());
                    }
                    @Override public void position(long position) {}
                }, cancelled::get, 50);
            } catch (InterruptedException ignored) {
                // stopped
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        follower.setDaemon(true);
        follower.start();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).as("condition within 10 s").isTrue();
    }

    private List<String> scanMessages() {
        List<String> got = new ArrayList<>();
        assertThat(entries.scan(0, published.get(), (row, e) -> got.add(e.message()), () -> false)).isTrue();
        return got;
    }

    @Test
    void appendedLinesExtendTheIndexAsTheFirstPassWould() throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, "﻿" + line(0, "INFO", "started") + "\r\n" + line(1, "WARN", "slow") + "\n");
        loadAndFollow(log);

        append(log, line(2, "ERROR", "boom") + "\r\n  at com.acme.Frame\n\n" + line(3, "INFO", "ünïcode") + "\n");
        await(() -> published.get() == 5);
        append(log, line(4, "INFO", "half"));                   // no line ending yet: held back
        Thread.sleep(300);
        assertThat(published.get()).isEqualTo(5);
        append(log, " a line\n");
        await(() -> published.get() == 6);

        // Same rows, offsets and lengths as indexing the whole file in one pass.
        EntryIndex whole = new EntryIndex();
        try (InputStream in = Files.newInputStream(log)) {
            LineScanner.scan(in, (o, l, t) -> whole.add(o, l, PARSER.parse(t)), () -> false);
        }
        assertThat(whole.size()).isEqualTo(6);
        for (int row = 0; row < 6; row++) {
            assertThat(index.offset(row)).as("offset of row %d", row).isEqualTo(whole.offset(row));
            assertThat(index.length(row)).as("length of row %d", row).isEqualTo(whole.length(row));
            assertThat(index.level(row)).isEqualTo(whole.level(row));
        }
        assertThat(entries.get(2).message()).isEqualTo("boom");
        assertThat(entries.get(3).rawLine()).isEqualTo("  at com.acme.Frame");
        assertThat(entries.get(4).message()).isEqualTo("ünïcode");
        assertThat(entries.get(5).message()).isEqualTo("half a line");
        assertThat(scanMessages()).containsExactly("started", "slow", "boom", "  at com.acme.Frame", "ünïcode",
                "half a line");
        assertThat(rotations).isEmpty();
    }

    @Test
    void aRenamedFileKeepsItsRowsAndTheNewFileIsFollowed() throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, line(0, "INFO", "old 0") + "\n" + line(1, "INFO", "old 1") + "\n");
        loadAndFollow(log);
        append(log, line(2, "INFO", "old 2") + "\n");
        await(() -> published.get() == 3);
        assertThat(entries.get(2).message()).isEqualTo("old 2");   // the EDT has read it once

        // Rolling: the writer renames the file (which our open channels must allow, also on
        // Windows) and starts a new one with the same name.
        Files.move(log, dir.resolve("app.log.1"));
        append(log, line(3, "ERROR", "new 0") + "\n" + line(4, "INFO", "new 1") + "\n");
        await(() -> published.get() == 5);

        assertThat(rotations).containsExactly(FileFollower.Rotation.RENAMED);
        assertThat(lostText).containsExactly(false);
        for (int row = 0; row < 3; row++) assertThat(entries.get(row).message()).isEqualTo("old " + row);
        assertThat(entries.get(3).message()).isEqualTo("new 0");
        assertThat(entries.get(3).level()).isEqualTo(LogLevel.ERROR);
        assertThat(entries.get(4).message()).isEqualTo("new 1");
        assertThat(index.offset(3)).isZero();                      // the new file's own offsets
        assertThat(scanMessages()).containsExactly("old 0", "old 1", "old 2", "new 0", "new 1");
        assertThat(entries.hasReadFailed()).isFalse();

        // A scan cancelled by an interrupt doesn't close the renamed file's channel.
        Thread.currentThread().interrupt();
        assertThat(entries.scan(0, 5, (row, e) -> {}, () -> false)).isFalse();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(scanMessages()).containsExactly("old 0", "old 1", "old 2", "new 0", "new 1");
    }

    @Test
    void linesWrittenToTheOldFileJustBeforeTheRenameAreRead() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), line(0, "INFO", "old 0") + "\n");
        index = new EntryIndex();
        entries = IndexedEntries.open(log, index, PARSER, LOST);
        java.util.concurrent.CountDownLatch paused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        follower = new Thread(() -> {
            try {
                FileFollower.follow(log, 0, new FileFollower.Listener() {
                    @Override public void line(long offset, int length, String text) {
                        index.add(offset, length, PARSER.parse(text));
                        published.incrementAndGet();
                    }
                    @Override public void rotated(FileFollower.Rotation how) {
                        rotations.add(how);
                        lostText.add(entries.rotated());
                    }
                    @Override public void position(long position) {
                        if (paused.getCount() == 0) return;
                        paused.countDown();         // after the first read: hold the follower here
                        try {
                            resume.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, cancelled::get, 50);
            } catch (InterruptedException ignored) {
                // stopped
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        follower.setDaemon(true);
        follower.start();
        assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();

        // Between the follower's read and its next look: a last line, then the roll.
        append(log, line(1, "INFO", "last old") + "\n");
        Files.move(log, dir.resolve("app.log.1"));
        // Shorter than what was read from the old file, so it's seen as replaced at once.
        Files.writeString(log, line(2, "INFO", "n") + "\n");
        resume.countDown();
        await(() -> published.get() == 3);

        assertThat(rotations).containsExactly(FileFollower.Rotation.RENAMED);
        assertThat(scanMessages()).containsExactly("old 0", "last old", "n");
    }

    @Test
    void aTruncatedFileKeepsItsRowsIndexColumnsWithAPlaceholder() throws Exception {
        Path log = dir.resolve("app.log");
        Files.writeString(log, line(0, "INFO", "old 0") + "\n" + line(1, "WARN", "old 1") + "\n"
                + line(2, "ERROR", "old 2 with a longer message than what replaces it") + "\n");
        loadAndFollow(log);
        LogEntry before = entries.get(1);

        // copytruncate: the file is copied elsewhere, then cut to nothing in place.
        Files.writeString(log, line(3, "INFO", "new 0") + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        await(() -> published.get() == 4);

        assertThat(rotations).containsExactly(FileFollower.Rotation.TRUNCATED);
        assertThat(lostText).containsExactly(true);
        LogEntry after = entries.get(1);
        assertThat(after.message()).isEqualTo(LOST);
        assertThat(after.timestamp()).isEqualTo(before.timestamp());
        assertThat(after.level()).isEqualTo(LogLevel.WARN);
        assertThat(after.logger()).isEqualTo("com.acme.L1");
        assertThat(entries.get(3).message()).isEqualTo("new 0");
        assertThat(index.offset(3)).isZero();
        assertThat(scanMessages()).containsExactly(LOST, LOST, LOST, "new 0");
        assertThat(entries.hasReadFailed()).isFalse();

        append(log, line(4, "INFO", "new 1") + "\n");
        await(() -> published.get() == 5);
        assertThat(entries.get(4).message()).isEqualTo("new 1");
        assertThat(rotations).hasSize(1);
    }

    @Test
    void aRotationWithNoRowsYetLosesNothing() throws IOException {
        Path log = Files.writeString(dir.resolve("app.log"), "");
        index = new EntryIndex();
        entries = IndexedEntries.open(log, index, PARSER, LOST);
        assertThat(entries.rotated()).isFalse();
        entries.close();
        assertThat(entries.rotated()).isFalse();                   // after close: ignored
    }
}
