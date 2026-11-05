package com.droidenx.clouseau.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Following a merged view: hold-back, late rows and re-merges (D-17 step 5). */
class MergedFollowTest {

    private static final LineParser PARSER = new LineParser(Optional.of(new RegexLogParser("test",
            Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) (?<level>[A-Z]+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss")), List.of(), null);

    @TempDir Path dir;
    private final List<Path> files = new ArrayList<>();
    private MergedEntries view;
    private MergedFollow follow;

    private IndexedEntries index(String name, String text) throws IOException {
        Path file = Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
        files.add(file);
        EntryIndex index = new EntryIndex();
        try (InputStream in = Files.newInputStream(file)) {
            LineScanner.scan(in, (o, l, t) -> index.add(o, l, PARSER.parse(t)), () -> false);
        }
        return IndexedEntries.open(file, index, PARSER);
    }

    @BeforeEach
    void open() throws IOException {
        IndexedEntries app = index("app.log", "2026-11-04 10:00:01 INFO app starts\n");
        IndexedEntries db = index("db.log", "2026-11-04 10:00:02 INFO db ready\n");
        IndexedEntries old = index("app.log.1", "2026-11-04 09:00:00 INFO an hour ago\n");
        view = new MergedEntries(List.of(new MergedEntries.Source("app.log", app),
                new MergedEntries.Source("db.log", db), new MergedEntries.Source("app.log.1", old)),
                MergeOrder.build(List.of(in(app), in(db), in(old))));
        follow = new MergedFollow(view);
    }

    @AfterEach
    void close() {
        view.close();
    }

    private static MergeOrder.Input in(IndexedEntries e) {
        return new MergeOrder.Input(e.index(), e.index().size(), 0);
    }

    /** Appends lines to a source's file and index, as its tail worker does, and hands the count over. */
    private void write(int source, long now, String... lines) throws IOException {
        Path file = files.get(source);
        EntryIndex index = view.sources().get(source).entries().index();
        for (String line : lines) {
            long offset = Files.size(file);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            index.add(offset, line.getBytes(StandardCharsets.UTF_8).length, PARSER.parse(line));
        }
        follow.arrived(source, index.size(), now);
    }

    private List<String> shown() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < view.size(); i++) out.add(view.get(i).message());
        return out;
    }

    @Test
    void rowsWaitUntilEveryWritingFileHasCaughtUp() throws IOException {
        write(0, 0, "2026-11-04 10:00:05 INFO app 5");
        // Only app has written: nothing to wait for.
        assertThat(follow.step(0)).isNotNull();
        assertThat(shown()).endsWith("app 5");

        write(1, 10, "2026-11-04 10:00:07 INFO db 7");
        assertThat(follow.step(10)).isNull();              // app may still write something older
        write(0, 20, "2026-11-04 10:00:06 INFO app 6");
        MergedFollow.Placed p = follow.step(20);
        assertThat(p.moved()).isFalse();
        assertThat(shown()).endsWith("app 5", "app 6");    // db 7 waits for app to reach 7
        write(0, 30, "2026-11-04 10:00:08 INFO app 8");
        follow.step(30);
        assertThat(shown()).endsWith("app 5", "app 6", "db 7");   // app 8 now waits for db
        assertThat(follow.hasPending()).isTrue();
        follow.step(10 + MergedFollow.HOLD_MILLIS);
        assertThat(shown()).endsWith("db 7", "app 8");
        assertThat(follow.hasPending()).isFalse();
    }

    @Test
    void heldRowsGoAfterOneSecondAtTheMost() throws IOException {
        write(0, 0, "2026-11-04 10:00:05 INFO app 5");
        write(1, 0, "2026-11-04 10:00:09 INFO db 9");
        follow.step(0);
        assertThat(shown()).endsWith("app 5");
        assertThat(follow.step(MergedFollow.HOLD_MILLIS - 1)).isNull();
        assertThat(follow.step(MergedFollow.HOLD_MILLIS)).isNotNull();
        assertThat(shown()).endsWith("app 5", "db 9");
    }

    @Test
    void aLateRowReMergesAndOtherRowsKeepTheirIdentity() throws IOException {
        write(0, 0, "2026-11-04 10:00:05 INFO app 5");
        write(1, 0, "2026-11-04 10:00:06 INFO db 6");
        follow.step(MergedFollow.HOLD_MILLIS);
        assertThat(shown()).endsWith("app 5", "db 6");

        write(0, 5_000, "2026-11-04 10:00:04 INFO app 4, flushed late");
        MergedFollow.Placed p = follow.step(5_000);
        assertThat(p.moved()).isTrue();
        assertThat(view.order()).isSameAs(p.after());
        // A file keeps its order, so app 4 goes right after app 5, before db 6.
        assertThat(shown()).endsWith("app 5", "app 4, flushed late", "db 6");
        assertThat(p.from()).isEqualTo(4);
        assertThat(p.before().ordinalOf(1, 1)).isEqualTo(4);   // db 6 moved by one
        assertThat(p.after().ordinalOf(1, 1)).isEqualTo(5);
    }

    @Test
    void reMergesAreSpacedOut() throws IOException {
        write(0, 0, "2026-11-04 10:00:05 INFO app 5");
        write(1, 0, "2026-11-04 10:00:06 INFO db 6");
        follow.step(MergedFollow.HOLD_MILLIS);
        write(0, 2_000, "2026-11-04 10:00:04 INFO app late");
        assertThat(follow.step(2_000).moved()).isTrue();
        write(0, 2_100, "2026-11-04 10:00:03 INFO app later");
        assertThat(follow.step(2_100)).isNull();               // the last re-merge was at 2000
        assertThat(follow.hasPending()).isTrue();
        assertThat(follow.step(2_000 + MergedFollow.REMERGE_EVERY_MILLIS).moved()).isTrue();
        assertThat(shown()).endsWith("app 5", "app late", "app later", "db 6");
    }
}
