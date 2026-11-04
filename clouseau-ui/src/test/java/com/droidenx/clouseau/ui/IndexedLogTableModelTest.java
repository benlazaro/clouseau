package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.core.CombinedFilter;
import com.droidenx.clouseau.core.EntryIndex;
import com.droidenx.clouseau.core.RegexLogParser;
import com.droidenx.clouseau.core.IndexedEntries;
import com.droidenx.clouseau.core.LineParser;
import com.droidenx.clouseau.core.LineScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** A tab showing an indexed static file (D-16 step 2). Model calls run on the EDT. */
class IndexedLogTableModelTest {

    private static final LineParser NO_PARSERS = new LineParser(Optional.empty(), List.of(), null);

    @TempDir Path dir;
    private LogTableModel model;

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            model = new LogTableModel();
            model.setMaxEntries(10);   // an indexed tab must ignore it
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> model.dispose());
    }

    private interface EdtAction { void run() throws Exception; }

    private static void onEdt(EdtAction action) throws Exception {
        Exception[] failure = {null};
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); } catch (Exception e) { failure[0] = e; }
        });
        if (failure[0] != null) throw failure[0];
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250 && !condition.getAsBoolean(); i++) {
            Thread.sleep(20);
            SwingUtilities.invokeAndWait(() -> {});
        }
    }

    /** Indexes {@code lines} the way LogPanel does: index first, then hand the entries over. */
    private void loadIndexed(List<String> lines) throws Exception {
        loadIndexed(lines, NO_PARSERS);
    }

    private Path loadIndexed(List<String> lines, LineParser parser) throws Exception {
        Path file = Files.writeString(dir.resolve("app.log"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        EntryIndex index = new EntryIndex();
        IndexedEntries entries = IndexedEntries.open(file, index, parser);
        List<LogEntry> parsed = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            LineScanner.scan(in, (o, l, t) -> {
                LogEntry e = parser.parse(t);
                index.add(o, l, e);
                parsed.add(e);
            }, () -> false);
        }
        onEdt(() -> {
            model.clear();
            model.beginIndexedLoad(entries);
            model.appendBatch(parsed.subList(0, parsed.size() / 2));
            model.appendBatch(parsed.subList(parsed.size() / 2, parsed.size()));
        });
        return file;
    }

    private static List<String> lines(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add("line " + i);
        return list;
    }

    private static LogEntry live(String text) {
        return new LogEntry(null, LogEntry.LogLevel.UNKNOWN, null, null, text, text, Map.of());
    }

    @Test
    void entriesAreReadBackFromTheFile() throws Exception {
        loadIndexed(lines(50));

        onEdt(() -> {
            assertThat(model.isIndexed()).isTrue();
            assertThat(model.getTotalCount()).isEqualTo(50);
            assertThat(model.getRowCount()).isEqualTo(50);
            assertThat(model.getEntry(37).rawLine()).isEqualTo("line 37");
            assertThat(model.getValueAt(37, 5)).isEqualTo("line 37");
        });
    }

    @Test
    void onlyFollowedEntriesAreTrimmed() throws Exception {
        loadIndexed(lines(50));

        onEdt(() -> {
            model.applyFilter(e -> !e.rawLine().endsWith("3"));
            List<LogEntry> live = new ArrayList<>();
            for (int i = 0; i < 25; i++) live.add(live("new " + i));
            model.appendLive(live);      // the limit is 10: the 15 oldest followed ones go
        });
        await(() -> model.getRowCount() == 45 + 9);

        onEdt(() -> {
            assertThat(model.getTotalCount()).isEqualTo(60);
            assertThat(model.getTrimmedCount()).isEqualTo(15);
            assertThat(model.entryAtOrdinal(49).rawLine()).isEqualTo("line 49");
            assertThat(model.entryAtOrdinal(50)).isNull();
            assertThat(model.entryAtOrdinal(65).rawLine()).isEqualTo("new 15");
            assertThat(model.nextOrdinal()).isEqualTo(75);
            assertThat(model.getRowCount()).isEqualTo(45 + 9);   // "new 23" ends in 3
            assertThat(model.getEntry(44).rawLine()).isEqualTo("line 49");
            assertThat(model.getEntry(45).rawLine()).isEqualTo("new 15");

            model.applyFilter(e -> true);   // a filter run after the trim skips the gap
        });
        await(() -> model.getRowCount() == 60);
        onEdt(() -> assertThat(model.getEntry(50).rawLine()).isEqualTo("new 15"));
    }

    @Test
    void followedEntriesComeAfterTheIndexAndAreNotTrimmed() throws Exception {
        loadIndexed(lines(50));

        onEdt(() -> {
            model.appendLive(List.of(live("new 1"), live("new 2")));

            assertThat(model.getTotalCount()).isEqualTo(52);
            assertThat(model.getTrimmedCount()).isZero();
            assertThat(model.entryAtOrdinal(0).rawLine()).isEqualTo("line 0");
            assertThat(model.entryAtOrdinal(51).rawLine()).isEqualTo("new 2");
            assertThat(model.nextOrdinal()).isEqualTo(52);
        });
    }

    @Test
    void filterScansTheFileAndKeepsOrdinals() throws Exception {
        loadIndexed(lines(5_000));
        onEdt(() -> model.appendLive(List.of(live("live 7"))));

        onEdt(() -> model.applyFilter(e -> e.rawLine().endsWith("7")));
        await(() -> model.getRowCount() == 501);

        onEdt(() -> {
            assertThat(model.getRowCount()).isEqualTo(501);   // 500 indexed + 1 live
            assertThat(model.ordinalAt(0)).isEqualTo(7);
            assertThat(model.getEntry(1).rawLine()).isEqualTo("line 17");
            assertThat(model.getEntry(500).rawLine()).isEqualTo("live 7");
        });
    }

    private static final LineParser LEVELS = new LineParser(Optional.empty(), List.of(new RegexLogParser("t",
            Pattern.compile("(?<timestamp>\\S+ \\S+) (?<level>[A-Z]+) (?<message>.*)"), "yyyy-MM-dd HH:mm:ss")), null);

    private static List<String> levelLines(int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add("2026-11-04 10:00:00 " + (i % 4 == 0 ? "ERROR" : "INFO") + " event " + i);
        }
        return list;
    }

    private static CombinedFilter filter(Set<LogLevel> levels, String text) {
        return new CombinedFilter(levels, Set.of(), false, Map.of(), null, null, text);
    }

    @Test
    void columnFilterDoesNotReadTheFile() throws Exception {
        Path file = loadIndexed(levelLines(10_000), LEVELS);
        // If the filter read any text now, it would fail and keep nothing.
        Files.writeString(file, "", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

        onEdt(() -> model.applyFilter(filter(EnumSet.of(LogLevel.ERROR), null)));
        await(() -> model.getRowCount() == 2_500);

        onEdt(() -> {
            assertThat(model.getRowCount()).isEqualTo(2_500);
            assertThat(model.ordinalAt(1)).isEqualTo(4);
        });
    }

    @Test
    void textFilterReportsProgressThenEnds() throws Exception {
        loadIndexed(levelLines(50_000), LEVELS);
        List<Integer> progress = java.util.Collections.synchronizedList(new ArrayList<>());
        onEdt(() -> {
            model.setFilterProgressListener(progress::add);
            // The columns keep only ERROR rows; only those are read for the text.
            model.applyFilter(filter(EnumSet.of(LogLevel.ERROR), "event \\d*6$"));
        });
        await(() -> !progress.isEmpty() && progress.get(progress.size() - 1) == -1);

        onEdt(() -> {
            assertThat(progress).last().isEqualTo(-1);
            assertThat(progress).contains(0).allMatch(p -> p >= -1 && p < 100);
            assertThat(model.getRowCount()).isEqualTo(2_500);   // i % 4 == 0 and ends in 6: i % 20 == 16
            assertThat(model.getEntry(0).message()).isEqualTo("event 16");
        });
    }

    @Test
    void findCountsContinuationLinesAndFollowedEntries() throws Exception {
        loadIndexed(List.of(
                "2026-11-04 10:00:00 INFO start",
                "  at com.acme.Plain",
                "2026-11-04 10:00:01 INFO other",
                "  at com.acme.Needle",
                "2026-11-04 10:00:02 ERROR needle in the message",
                "2026-11-04 10:00:03 INFO no match"), LEVELS);
        onEdt(() -> {
            model.applyFilter(filter(EnumSet.complementOf(EnumSet.of(LogLevel.UNKNOWN)), null));
            model.appendLive(List.of(LEVELS.parse("2026-11-04 10:00:04 WARN needle again")));
        });
        await(() -> model.getRowCount() == 5);

        List<int[]> found = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Integer> progress = java.util.Collections.synchronizedList(new ArrayList<>());
        onEdt(() -> model.find(e -> e.message().toLowerCase().contains("needle"), progress::add, found::add));
        await(() -> !found.isEmpty());

        assertThat(found).hasSize(1);
        assertThat(found.get(0)).containsExactly(2, 4, 6);
        assertThat(progress).last().isEqualTo(-1);
    }

    @Test
    void aNewFindDropsTheOldOne() throws Exception {
        loadIndexed(lines(200_000));
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        onEdt(() -> {
            model.find(e -> true, p -> {}, r -> results.add("first"));
            model.find(e -> e.rawLine().equals("line 3"), p -> {}, r -> results.add("second " + r.length));
        });
        await(() -> !results.isEmpty());
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> {});

        // Lines without a time are continuations of the line before, so rows 0 to 2 match through row 3.
        assertThat(results).containsExactly("second 4");
    }

    @Test
    void continuationLinesSpanIndexedRows() throws Exception {
        loadIndexed(lines(5));

        onEdt(() -> assertThat(model.getContinuationLines(1)).extracting(LogEntry::rawLine)
                .containsExactly("line 2", "line 3", "line 4"));
    }

    @Test
    void clearDropsTheIndexAndAStaleFilterResult() throws Exception {
        loadIndexed(lines(2_000));

        onEdt(() -> {
            model.applyFilter(e -> true);   // runs in the background...
            Thread.sleep(500);              // ...finishes, and queues its result behind this task
            model.clear();                  // which is now for entries that are gone
            model.appendBatch(List.of(live("after clear")));
        });
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> {});

        onEdt(() -> {
            assertThat(model.isIndexed()).isFalse();
            assertThat(model.getRowCount()).isEqualTo(1);
            assertThat(model.getEntry(0).rawLine()).isEqualTo("after clear");
        });
    }
}
