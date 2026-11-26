package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LogFormatter's contract: a formatter that throws, in canFormat or format, or returns null is
 * skipped for that message, and the others still run. A plugin's bug never reaches the crash dialog.
 */
class DetailPanelFormatterFailureTest {

    private static LogFormatter formatter(String name, java.util.function.Predicate<String> can,
                                          java.util.function.UnaryOperator<String> format) {
        return new LogFormatter() {
            @Override public String getName() { return name; }
            @Override public boolean canFormat(String input) { return can.test(input); }
            @Override public String format(String input) { return format.apply(input); }
        };
    }

    @Test
    void aFailingFormatterIsSkippedAndTheRestStillRun() throws Exception {
        LogEntry entry = LogEntry.builder("hello").message("hello").build();
        List<LogFormatter> formatters = List.of(
                formatter("Throws in canFormat", s -> { throw new IllegalStateException("bug"); }, s -> s),
                formatter("Throws in format", s -> true, s -> { throw new IllegalStateException("bug"); }),
                formatter("Returns null", s -> true, s -> null),
                formatter("Upper", s -> true, s -> s.toUpperCase(java.util.Locale.ROOT)));
        AtomicReference<String> shown = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            var content = new DetailPanel.Content(entry, null, null, List.of(), 0, null, null);
            DetailPanel panel = new DetailPanel(formatters, List.<LogSyntaxHighlighter>of(), () -> content);
            panel.refresh();   // threw IllegalStateException from canFormat
            try {
                var field = DetailPanel.class.getDeclaredField("lastFormattedMessage");
                field.setAccessible(true);
                shown.set((String) field.get(panel));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });

        assertThat(shown.get()).isEqualTo("HELLO");
    }
}
