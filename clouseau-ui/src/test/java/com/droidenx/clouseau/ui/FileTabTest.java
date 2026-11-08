package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.RegexLogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** A tab showing one file (D-16, G-11), loaded as a {@code FileContent} (D-21). */
class FileTabTest {

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
        long end = System.currentTimeMillis() + 10_000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    /**
     * D-21: reloading while the first load is still running. Only the second load's rows
     * show (once each), and only its follower runs: a stale one would add a followed line
     * twice, or to the wrong index.
     */
    @Test
    void reloadingDuringALoadShowsOnlyTheNewLoad() throws Exception {
        int lines = 20_000;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++) text.append("2026-11-07 10:00:00 INFO line ").append(i).append('\n');
        Path file = Files.writeString(dir.resolve("app.log"), text);
        TabPanel panel = onEdt(() -> new TabPanel(List.of(PARSER), List.of(), List.of()));
        LogTableModel model = (LogTableModel) panel.getLogTable().getModel();
        try {
            onEdt(() -> {
                panel.setFollow(true);
                panel.load(file, Optional.of(PARSER));
                panel.reload();
                return null;
            });
            await(() -> model.getTotalCount() == lines && model.getRowCount() == lines);
            onEdt(() -> { panel.reload(); return null; });   // and once more after loading
            await(() -> model.getRowCount() == lines);
            assertThat(onEdt(panel::getFile)).isEqualTo(file.toAbsolutePath());

            Files.writeString(file, "2026-11-07 10:00:01 WARN followed\n", StandardOpenOption.APPEND);
            await(() -> model.getTotalCount() == lines + 1 && model.getRowCount() == lines + 1);
            Thread.sleep(300);   // a second follower would have added it again by now
            assertThat(onEdt(model::getTotalCount)).isEqualTo(lines + 1);
            assertThat(onEdt(() -> model.getEntry(model.getRowCount() - 1).message())).isEqualTo("followed");
        } finally {
            onEdt(() -> { panel.dispose(); return null; });
        }
    }
}
