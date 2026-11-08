package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.RegexLogParser;
import com.droidenx.clouseau.api.LogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** A file no parser recognizes, shown as plain text (UC-13). */
class PlainTextViewTest {

    @TempDir Path dir;

    private static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { out.set(c.call()); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return out.get();
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    /** Waits up to five seconds for {@code condition}, checked on the EDT. */
    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 5_000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    /** A parser for lines starting with {@code prefix}. */
    private static LogParser parserFor(String prefix) {
        return new RegexLogParser(prefix, Pattern.compile(Pattern.quote(prefix) + "(?<message>.*)"),
                "yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void showsEveryLineAndCopiesOnlyTheText() throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "alpha\n\nbeta\tx\nlast", StandardCharsets.UTF_8);
        PlainTextView view = onEdt(() -> new PlainTextView(() -> {}, on -> {}, () -> {}));
        try {
            onEdt(() -> view.load(file, null, null, false, "banner"));
            await(() -> view.lineCount() == 4);
            onEdt(() -> view.table().setRowSelectionInterval(0, 2));
            assertThat(onEdt(view::selectedText)).isEqualTo("alpha\n\nbeta\tx");
            assertThat(onEdt(() -> view.table().getValueAt(2, 0))).isEqualTo(3);
            assertThat(onEdt(() -> view.table().getValueAt(2, 1))).isEqualTo("beta    x");
        } finally {
            onEdt(view::dispose);
        }
    }

    @Test
    void findsMatchingLinesInTheBackground() throws Exception {
        Path file = dir.resolve("find.txt");
        Files.writeString(file, "one\nNeedle two\nthree\nfour needle\n");
        PlainTextView view = onEdt(() -> new PlainTextView(() -> {}, on -> {}, () -> {}));
        try {
            onEdt(() -> view.load(file, null, null, false, ""));
            await(() -> view.lineCount() == 4);
            onEdt(() -> view.findBar().search("needle"));
            await(() -> "1 / 2".equals(view.findBar().counterText()));
            assertThat(onEdt(() -> view.table().getSelectedRow())).isEqualTo(1);
            onEdt(() -> view.findBar().navigate(+1));
            assertThat(onEdt(() -> view.table().getSelectedRow())).isEqualTo(3);
        } finally {
            onEdt(view::dispose);
        }
    }

    @Test
    void followsLinesAppendedToTheFile() throws Exception {
        Path file = dir.resolve("grow.txt");
        Files.writeString(file, "first\n");
        PlainTextView view = onEdt(() -> new PlainTextView(() -> {}, on -> {}, () -> {}));
        try {
            onEdt(() -> view.load(file, null, null, true, ""));
            await(() -> view.lineCount() == 1);
            Files.writeString(file, "\nthird\n", StandardOpenOption.APPEND);
            await(() -> view.lineCount() == 3);
            assertThat(onEdt(() -> view.table().getValueAt(2, 1))).isEqualTo("third");
        } finally {
            onEdt(view::dispose);
        }
    }

    @Test
    void tabSwitchesToPlainTextAndBackOnceAParserRecognizesTheFile() throws Exception {
        Path file = dir.resolve("custom.log");
        Files.writeString(file, "@@ started\n@@ working\n");
        TabPanel panel = onEdt(() -> new TabPanel(List.of(parserFor("XX")), List.of(), List.of()));
        AtomicReference<List<String>> samples = new AtomicReference<>();
        try {
            onEdt(() -> {
                panel.setFollow(false);
                panel.setCreateParserAction(samples::set);
                panel.load(file, Optional.empty());
            });
            await(panel::isPlainText);

            onEdt(() -> panel.retryWithParsers(List.of(parserFor("XX"))));
            assertThat(onEdt(panel::isPlainText)).as("still unrecognized").isTrue();

            onEdt(() -> panel.retryWithParsers(List.of(parserFor("@@"))));
            await(() -> !panel.isPlainText());
            await(() -> ((LogTableModel) panel.getLogTable().getModel()).getTotalCount() == 2);
        } finally {
            onEdt(panel::dispose);
        }
    }

    @Test
    void aChosenParserThatDoesNotMatchAlsoOpensAsPlainText() throws Exception {
        Path file = dir.resolve("other.log");
        Files.writeString(file, "@@ hello\n");
        LogParser xx = parserFor("XX");
        TabPanel panel = onEdt(() -> new TabPanel(List.of(xx, parserFor("@@")), List.of(), List.of()));
        try {
            onEdt(() -> {
                panel.setFollow(false);
                panel.load(file, Optional.of(xx));
            });
            await(panel::isPlainText);
        } finally {
            onEdt(panel::dispose);
        }
    }
}
