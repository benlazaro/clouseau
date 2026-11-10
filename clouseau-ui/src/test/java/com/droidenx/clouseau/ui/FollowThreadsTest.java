package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.MergeLoad;
import com.droidenx.clouseau.core.RegexLogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-22: follow loops never end, so they run on threads of their own. On
 * {@code SwingWorker.execute}'s pool of 10 threads, more than ten followed files took
 * every thread and the next file never loaded.
 */
class FollowThreadsTest {

    private static final LogParser PARSER = new RegexLogParser("test",
            Pattern.compile("(?<timestamp>\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d) (?<level>[A-Z]+) (?<message>.*)"),
            "yyyy-MM-dd HH:mm:ss");

    /** More follow loops than {@code SwingWorker}'s pool has threads. */
    private static final int FOLLOWED_TABS = 6;
    private static final int MERGED_FILES = 6;

    @TempDir Path dir;

    private static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { out.set(c.call()); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return out.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    private static LogTableModel model(TabPanel panel) {
        return (LogTableModel) panel.getLogTable().getModel();
    }

    private static final class Listener implements TabPanel.MergeListener {
        @Override public void skipped(List<MergeLoad.Skipped> s) {}
        @Override public void notMerged(List<MergeLoad.File> m) {}
        @Override public void failed() {}
        @Override public void merged(List<MergeLoad.File> m) {}
    }

    private Path write(String name, int second) throws Exception {
        return Files.writeString(dir.resolve(name), "2026-11-10 10:00:%02d INFO %s\n".formatted(second, name));
    }

    @Test
    void anotherFileLoadsWhileMoreThanTenSourcesAreFollowed() throws Exception {
        List<TabPanel> panels = new ArrayList<>();
        try {
            List<Path> tabFiles = new ArrayList<>();
            for (int i = 0; i < FOLLOWED_TABS; i++) {
                Path file = write("tab" + i + ".log", i);
                tabFiles.add(file);
                TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
                panels.add(panel);
                onEdt(() -> { panel.setFollow(true); panel.load(file, Optional.of(PARSER)); return null; });
                await(() -> model(panel).getRowCount() == 1);
            }

            List<MergeLoad.File> merged = new ArrayList<>();
            for (int i = 0; i < MERGED_FILES; i++) merged.add(new MergeLoad.File(write("merged" + i + ".log", i), null));
            TabPanel mergedPanel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
            panels.add(mergedPanel);
            onEdt(() -> {
                mergedPanel.setFollow(true);
                mergedPanel.loadMerged(merged, Optional.empty(), new Listener());
                return null;
            });
            await(() -> model(mergedPanel).getRowCount() == MERGED_FILES);

            // Twelve follow loops are running; one more file still loads.
            Path other = write("other.log", 30);
            TabPanel otherPanel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
            panels.add(otherPanel);
            onEdt(() -> { otherPanel.setFollow(false); otherPanel.load(other, Optional.of(PARSER)); return null; });
            await(() -> model(otherPanel).getRowCount() == 1);

            // And every follow loop is still reading: the last tab and the last merged file.
            Files.writeString(tabFiles.get(FOLLOWED_TABS - 1), "2026-11-10 10:01:00 WARN followed\n",
                    StandardOpenOption.APPEND);
            Files.writeString(merged.get(MERGED_FILES - 1).path(), "2026-11-10 10:01:00 WARN merged followed\n",
                    StandardOpenOption.APPEND);
            TabPanel lastTab = panels.get(FOLLOWED_TABS - 1);
            await(() -> model(lastTab).getRowCount() == 2);
            await(() -> model(mergedPanel).getRowCount() == MERGED_FILES + 1);
            assertThat(onEdt(() -> model(mergedPanel).getEntry(MERGED_FILES).message())).isEqualTo("merged followed");
        } finally {
            onEdt(() -> { panels.forEach(TabPanel::dispose); return null; });
        }
    }
}
