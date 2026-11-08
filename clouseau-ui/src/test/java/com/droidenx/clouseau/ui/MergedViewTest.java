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
    private static final class Listener implements TabPanel.MergeListener {
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
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
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

    private static List<String> messages(LogTableModel model) throws Exception {
        return onEdt(() -> {
            List<String> out = new ArrayList<>();
            for (int r = 0; r < model.getRowCount(); r++) out.add(model.getEntry(r).message());
            return out;
        });
    }

    private static void append(MergeLoad.File f, String line) throws Exception {
        Files.writeString(f.path(), line + "\n", java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    void followsEveryFileAndPlacesLateLinesInTime() throws Exception {
        MergeLoad.File app = write("app.log", "2026-11-04 10:00:01 INFO app 1\n");
        MergeLoad.File db = write("db.log", "2026-11-04 10:00:02 INFO db 2\n");
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        try {
            onEdt(() -> {
                panel.setFollow(true);
                panel.loadMerged(List.of(app, db), Optional.empty(), new Listener());
                return null;
            });
            LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
            await(() -> model.getRowCount() == 2);

            append(db, "2026-11-04 10:00:04 INFO db 4");
            append(app, "2026-11-04 10:00:03 INFO app 3");
            await(() -> model.getRowCount() == 4);
            assertThat(messages(model)).containsExactly("app 1", "db 2", "app 3", "db 4");

            // app 9 shows after the hold-back and is selected; then db 8 arrives too late.
            append(app, "2026-11-04 10:00:09 INFO app 9");
            await(() -> model.getRowCount() == 5);
            onEdt(() -> { panel.getLogTable().setRowSelectionInterval(4, 4); return null; });
            append(db, "2026-11-04 10:00:08 INFO db 8, late");
            await(() -> model.getRowCount() == 6);
            assertThat(messages(model)).containsExactly("app 1", "db 2", "app 3", "db 4", "db 8, late", "app 9");
            int selected = onEdt(() -> panel.getLogTable().getSelectedRow());
            assertThat(selected).isEqualTo(5);           // still on app 9
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }

    @Test
    void aTimeOffsetMovesTheFileAndKeepsTheSelection() throws Exception {
        MergeLoad.File app = write("app.log", """
                2026-11-04 10:00:01 INFO app 1
                2026-11-04 10:00:03 INFO app 3
                2026-11-04 10:00:05 INFO app 5
                """);
        MergeLoad.File db = write("db.log", "2026-11-04 10:00:02 INFO db 2\n");
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        try {
            onEdt(() -> {
                panel.setFollow(false);
                panel.loadMerged(List.of(app, db), Optional.empty(), new Listener());
                return null;
            });
            LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
            await(() -> model.getRowCount() == 4);
            assertThat(messages(model)).containsExactly("app 1", "db 2", "app 3", "app 5");
            onEdt(() -> { panel.getLogTable().setRowSelectionInterval(1, 1); return null; });

            onEdt(() -> { panel.applyTimeOffset(1, 2_500); return null; });   // db 2 is really 10:00:04.5
            await(() -> "db 2".equals(model.getEntry(2).message()));
            assertThat(messages(model)).containsExactly("app 1", "app 3", "db 2", "app 5");
            assertThat(onEdt(() -> panel.getLogTable().getSelectedRow())).isEqualTo(2);
            assertThat(onEdt(() -> model.getValueAt(2, 6))).isEqualTo("db.log (+2.500s)");
            assertThat(onEdt(() -> model.getEntry(2).rawLine())).startsWith("2026-11-04 10:00:02");
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }

    @Test
    void aMergedReviewBundleOpensWithItsOffsets() throws Exception {
        MergeLoad.File app = write("app.log", """
                2026-11-04 10:00:01 INFO app 1
                2026-11-04 10:00:03 INFO app 3
                """);
        MergeLoad.File db = write("db.log", "2026-11-04 10:00:02 INFO db 2\n");
        Path bundle = dir.resolve("review.zip");
        String noNotes = com.droidenx.clouseau.core.AnnotationStore.inMemory().toJson();
        com.droidenx.clouseau.core.ReviewBundle.writeMerged(List.of(
                new com.droidenx.clouseau.core.ReviewBundle.Part(app.path(), null, noNotes, null, "UTC", 0),
                new com.droidenx.clouseau.core.ReviewBundle.Part(db.path(), null, noNotes, null, "UTC", 2_000)),
                "test", bundle);

        var manifest = com.droidenx.clouseau.core.ReviewBundle.read(bundle).orElseThrow();
        java.util.Map<MergeLoad.File, MergeLoad.PerFile> setup = new java.util.HashMap<>();
        List<MergeLoad.File> files = new ArrayList<>();
        for (var s : manifest.sources()) {
            MergeLoad.File f = new MergeLoad.File(bundle, s.log());
            files.add(f);
            setup.put(f, new MergeLoad.PerFile(java.time.ZoneId.of(s.timezone()), s.offsetMillis()));
        }
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        try {
            onEdt(() -> {
                panel.setFollow(false);
                panel.presetMerged(setup);
                panel.loadMerged(files, Optional.empty(), new Listener());
                return null;
            });
            LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
            await(() -> model.getRowCount() == 3);
            assertThat(messages(model)).containsExactly("app 1", "app 3", "db 2");   // db 2 is 10:00:04
            assertThat(onEdt(() -> model.getValueAt(2, 6))).isEqualTo("db.log (+2s)");
            assertThat(onEdt(panel::reviewParts)).extracting(com.droidenx.clouseau.core.ReviewBundle.Part::offsetMillis)
                    .containsExactly(0L, 2_000L);
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }

    @Test
    void aFileReadWithItsOwnParserIsMergedAndExportedWithIt() throws Exception {
        LogParser pipes = new RegexLogParser("pipes",
                Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\|(?<level>[A-Z]+)\\|(?<message>.*)"),
                "yyyy-MM-dd HH:mm:ss");
        MergeLoad.File app = write("app.log", """
                2026-11-04 10:00:01 INFO app 1
                2026-11-04 10:00:03 INFO app 3
                """);
        MergeLoad.File dev = write("device.log", "2026-11-04 10:00:02|INFO|device 2\n");
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER, pipes), List.of(), List.of()));
        try {
            onEdt(() -> {
                panel.setFollow(false);
                panel.presetMerged(java.util.Map.of(dev, new MergeLoad.PerFile(null, 0, pipes)));
                // The tab's parser doesn't recognize device.log; its own parser does.
                panel.loadMerged(List.of(app, dev), Optional.of(PARSER), new Listener());
                return null;
            });
            LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
            await(() -> model.getRowCount() == 3);
            assertThat(messages(model)).containsExactly("app 1", "device 2", "app 3");
            assertThat(onEdt(panel::reviewParts)).extracting(com.droidenx.clouseau.core.ReviewBundle.Part::parser)
                    .containsExactly("test", "pipes");
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }

    @Test
    void oneMergeableFileIsHandedBackToOpenOnItsOwn() throws Exception {
        MergeLoad.File app = write("app.log", "2026-11-04 10:00:01 INFO app starts\n");
        MergeLoad.File notes = write("notes.txt", "no format here\n");
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
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
