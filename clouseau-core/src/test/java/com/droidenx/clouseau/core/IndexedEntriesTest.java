package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IndexedEntriesTest {

    private static final LineParser NO_PARSERS = new LineParser(Optional.empty(), List.of(), null);

    @TempDir Path dir;

    private static IndexedEntries index(Path file) throws IOException {
        EntryIndex index = new EntryIndex();
        try (InputStream in = Files.newInputStream(file)) {
            LineScanner.scan(in, (o, l, t) -> index.add(o, l, NO_PARSERS.parse(t)), () -> false);
        }
        return IndexedEntries.open(file, index, NO_PARSERS);
    }

    private Path write(String text) throws IOException {
        return Files.writeString(dir.resolve("app.log"), text, StandardCharsets.UTF_8);
    }

    @Test
    void getReadsTheLineBackFromDisk() throws IOException {
        Path file = write("first\r\n\nsécond\nthird");
        try (IndexedEntries entries = index(file)) {
            assertThat(entries.get(1).rawLine()).isEqualTo("sécond");
            assertThat(entries.get(0).rawLine()).isEqualTo("first");
            assertThat(entries.get(2).message()).isEqualTo("third");
            assertThat(entries.get(1)).isSameAs(entries.get(1));   // cached
            assertThat(entries.hasReadFailed()).isFalse();
        }
    }

    @Test
    void scanReadsRowsInOrder() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) sb.append("line ").append(i).append('\n');
        sb.append("x".repeat(2 << 20)).append('\n');   // longer than one scan block
        sb.append("last\n");
        Path file = write(sb.toString());

        List<String> got = new ArrayList<>();
        try (IndexedEntries entries = index(file)) {
            boolean complete = entries.scan(4_998, 5_002, (row, e) -> got.add(row + ":" + e.rawLine().length()),
                    () -> false);

            assertThat(complete).isTrue();
            assertThat(got).containsExactly("4998:9", "4999:9", "5000:" + (2 << 20), "5001:4");
        }
    }

    @Test
    void scanReadsOnlyWantedRows() throws IOException {
        Path file = write("a\nb\nc\nd\ne\n");
        List<Integer> asked = new ArrayList<>();
        List<String> got = new ArrayList<>();
        try (IndexedEntries entries = index(file)) {
            boolean complete = entries.scan(1, 5, row -> {
                asked.add(row);
                return row % 2 == 1;
            }, (row, e) -> got.add(row + ":" + e.rawLine()), () -> false);

            assertThat(complete).isTrue();
            assertThat(asked).containsExactly(1, 2, 3, 4);
            assertThat(got).containsExactly("1:b", "3:d");
        }
    }

    @Test
    void cancelledScanStops() throws IOException {
        Path file = write("a\nb\nc\nd\n");
        List<String> got = new ArrayList<>();
        try (IndexedEntries entries = index(file)) {
            boolean complete = entries.scan(0, 4, (row, e) -> got.add(e.rawLine()), () -> got.size() >= 2);

            assertThat(complete).isFalse();
            assertThat(got).containsExactly("a", "b");
        }
    }

    @Test
    void interruptingAScanLeavesGetWorking() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200_000; i++) sb.append("line number ").append(i).append('\n');
        Path file = write(sb.toString());

        try (IndexedEntries entries = index(file)) {
            AtomicReference<Thread> scanner = new AtomicReference<>();
            Thread t = new Thread(() -> entries.scan(0, 200_000, (row, e) -> {
                if (row == 1_000) Thread.currentThread().interrupt();
            }, () -> false));
            scanner.set(t);
            t.start();
            t.join(10_000);

            // The scan used its own channel, so the shared one is still open.
            assertThat(entries.get(150_000).rawLine()).isEqualTo("line number 150000");
            assertThat(entries.hasReadFailed()).isFalse();
        }
    }

    @Test
    void truncatedFileGivesNullAndIsReported() throws IOException {
        Path file = write("one\ntwo\nthree\n");
        try (IndexedEntries entries = index(file)) {
            Files.writeString(file, "o", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

            LogEntry gone = entries.get(2);

            assertThat(gone).isNull();
            assertThat(entries.hasReadFailed()).isTrue();
            assertThat(entries.scan(0, 3, (row, e) -> {}, () -> false)).isFalse();
        }
    }
}
