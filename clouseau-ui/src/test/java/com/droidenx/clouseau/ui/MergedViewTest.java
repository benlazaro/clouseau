package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.core.RegexLogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** A tab showing several files merged into one timeline (UC-12, D-17 step 3). */
class MergedViewTest {

    private static final LogParser PARSER = new RegexLogParser("test",
            Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) (?<level>[A-Z]+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss");

    @TempDir Path dir;

    private static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { out.set(c.call()); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return out.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 5_000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    /** Records what the merged load tells the window. */
    private static final class Listener implements LogPanel.MergeListener {
        final List<MergeLoad.Skipped> skipped = new ArrayList<>();
        List<MergeLoad.File> notMerged;
        boolean failed;
        @Override public void skipped(List<MergeLoad.Skipped> s) { skipped.addAll(s); }
        @Override public void notMerged(List<MergeLoad.File> m) { notMerged = m; }
        @Override public void failed() { failed = true; }
        @Override public void merged(List<MergeLoad.File> m) {}
    }

    private MergeLoad.File write(String name, String text) throws Exception {
        return new MergeLoad.File(Files.writeString(dir.resolve(name), text), null);
    }

    @Test
    void showsBothFilesInOneTimelineWithTheirSource() throws Exception {
        MergeLoad.File app = write("app.log", """
                2026-11-04 10:00:01 INFO app starts
                2026-11-04 10:00:03 ERROR app fails
                """);
        MergeLoad.File db = write("db.log", "2026-11-04 10:00:02 WARN db slow\n");
        MergeLoad.File notes = write("notes.txt", "no format here\n");
        LogPanel panel = onEdt(() -> new LogPanel(List.of(PARSER), List.of(), List.of()));
        Listener listener = new Listener();
        try {
            onEdt(() -> {
                panel.setFollow(false);
                panel.loadMerged(List.of(app, db, notes), Optional.empty(), listener);
                return null;
            });
            LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
            await(() -> model.getTotalCount() == 3 && model.getRowCount() == 3);

            assertThat(onEdt(panel::isMerged)).isTrue();
            assertThat(listener.skipped).extracting(MergeLoad.Skipped::file).containsExactly(notes);
            int sourceCol = onEdt(() -> {
                for (int c = 0; c < model.getColumnCount(); c++) {
                    if (Messages.get("table.col.source").equals(model.getColumnName(c))) return c;
                }
                return -1;
            });
            assertThat(sourceCol).isPositive();
            List<String> rows = onEdt(() -> {
                List<String> out = new ArrayList<>();
                for (int r = 0; r < model.getRowCount(); r++) {
                    out.add(model.getValueAt(r, sourceCol) + " " + model.getEntry(r).message());
                }
                return out;
            });
            assertThat(rows).containsExactly("app.log app starts", "db.log db slow", "app.log app fails");
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }

    @Test
    void oneMergeableFileIsHandedBackToOpenOnItsOwn() throws Exception {
        MergeLoad.File app = write("app.log", "2026-11-04 10:00:01 INFO app starts\n");
        MergeLoad.File notes = write("notes.txt", "no format here\n");
        LogPanel panel = onEdt(() -> new LogPanel(List.of(PARSER), List.of(), List.of()));
        Listener listener = new Listener();
        try {
            onEdt(() -> {
                panel.loadMerged(List.of(app, notes), Optional.empty(), listener);
                return null;
            });
            await(() -> listener.notMerged != null);
            assertThat(listener.notMerged).containsExactly(app);
            assertThat(listener.skipped).hasSize(1);
            assertThat(listener.failed).isFalse();
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }
}
