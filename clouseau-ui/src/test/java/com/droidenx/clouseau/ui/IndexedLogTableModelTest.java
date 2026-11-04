package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.EntryIndex;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

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
        Path file = Files.writeString(dir.resolve("app.log"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        EntryIndex index = new EntryIndex();
        IndexedEntries entries = IndexedEntries.open(file, index, NO_PARSERS);
        List<LogEntry> parsed = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            LineScanner.scan(in, (o, l, t) -> {
                LogEntry e = NO_PARSERS.parse(t);
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
