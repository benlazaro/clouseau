package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.core.EntryFilter;
import com.droidenx.clouseau.core.EntryRows;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model over {@link EntryRows} that scan in several runs, as a merged view does
 * (D-17 step 2): text filters and Find must give the same answer as one ascending pass.
 */
class EntryRowsRunsTest {

    /** Entries in memory, scanned second half first, as two sources would be. */
    private record TwoRuns(List<LogEntry> entries) implements EntryRows {
        @Override public LogEntry get(int ordinal) { return entries.get(ordinal); }
        @Override public boolean isContinuation(int ordinal) { return cont(entries.get(ordinal)); }
        @Override public boolean testIndex(EntryFilter filter, int ordinal) { return true; }
        @Override public boolean needsText(EntryFilter filter) { return true; }
        @Override public void close() {}

        @Override
        public boolean scan(int from, int to, Visitor visitor, BooleanSupplier cancelled) {
            int mid = Math.max(from, Math.min(to, 3));
            run(mid, to, visitor);
            run(from, mid, visitor);
            return true;
        }

        private void run(int from, int to, Visitor visitor) {
            visitor.startRun();
            for (int o = from; o < to; o++) {
                if (visitor.wanted(o, isContinuation(o))) visitor.accept(o, entries.get(o));
            }
        }
    }

    private static boolean cont(LogEntry e) {
        return e.timestamp() == null || e.level() == LogLevel.UNKNOWN;
    }

    private static LogEntry entry(String message) {
        return new LogEntry(Instant.EPOCH, LogLevel.INFO, null, null, message, message, Map.of());
    }

    private static LogEntry continuation(String message) {
        return new LogEntry(null, LogLevel.UNKNOWN, null, null, message, message, Map.of());
    }

    @Test
    void textFilterAndFindWorkAcrossRuns() throws Exception {
        List<LogEntry> entries = List.of(
                entry("a"), continuation("needle in a's trace"),
                entry("b"),
                entry("c needle"), entry("d"), continuation("needle in d's trace"));
        LogTableModel[] model = new LogTableModel[1];
        SwingUtilities.invokeAndWait(() -> {
            model[0] = new LogTableModel();
            model[0].beginIndexedLoad(new TwoRuns(entries));
            model[0].appendBatch(entries);
            model[0].applyFilter(e -> !cont(e));
        });
        try {
            await(() -> model[0].getRowCount() == 4 && "a".equals(model[0].getEntry(0).message()));
            List<String> shown = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                for (int r = 0; r < model[0].getRowCount(); r++) shown.add(model[0].getEntry(r).message());
            });
            assertThat(shown).containsExactly("a", "b", "c needle", "d");

            AtomicReference<int[]> found = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
                    model[0].find(e -> e.message().contains("needle"), p -> {}, found::set));
            await(() -> found.get() != null);
            assertThat(found.get()).containsExactly(0, 3, 4);
        } finally {
            SwingUtilities.invokeAndWait(() -> model[0].dispose());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        boolean[] ok = {false};
        for (int i = 0; i < 250; i++) {
            SwingUtilities.invokeAndWait(() -> ok[0] = condition.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }
}
