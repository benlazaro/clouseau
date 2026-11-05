package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Following one file into its index, as single-file tabs and merged views both do (G-11). */
class IndexedFollowTest {

    @TempDir Path dir;

    private final LineParser lines = new LineParser(Optional.empty(), List.of(), null);
    private final List<LogEntry> seen = new CopyOnWriteArrayList<>();

    private Thread run(IndexedFollow follow, IndexedEntries entries, AtomicBoolean cancelled) {
        Thread t = new Thread(() -> follow.run(entries, lines, new IndexedFollow.Listener() {
            @Override public void entry(LogEntry e) { seen.add(e); }
            @Override public void textLost() {}
        }, cancelled::get, 100));
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void await(int count) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (seen.size() < count && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(seen).hasSize(count);
    }

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Test
    void addsAppendedLinesToTheIndexAndResumesWhereItStopped() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "old line\n");
        EntryIndex index = new EntryIndex();
        try (IndexedEntries entries = IndexedEntries.open(log, index, lines)) {
            IndexedFollow follow = new IndexedFollow(log, Files.size(log));

            AtomicBoolean cancelled = new AtomicBoolean();
            Thread t = run(follow, entries, cancelled);
            append(log, "new 1\nnew 2\n");
            await(2);
            cancelled.set(true);
            t.interrupt();
            t.join(5_000);
            assertThat(index.size()).isEqualTo(2);
            assertThat(follow.position()).isEqualTo(Files.size(log));

            // Stopped and started again: nothing is lost or read twice.
            append(log, "new 3\n");
            Thread again = run(follow, entries, new AtomicBoolean());
            await(3);
            again.interrupt();
            again.join(5_000);
            assertThat(seen).extracting(LogEntry::rawLine).containsExactly("new 1", "new 2", "new 3");
            assertThat(entries.get(2).rawLine()).isEqualTo("new 3");
        }
    }

    @Test
    void followsWithoutAnIndex() throws Exception {
        Path log = Files.writeString(dir.resolve("app.log"), "");
        IndexedFollow follow = new IndexedFollow(log, 0);
        Thread t = run(follow, null, new AtomicBoolean());
        append(log, "only line\n");
        await(1);
        t.interrupt();
        t.join(5_000);
    }
}
