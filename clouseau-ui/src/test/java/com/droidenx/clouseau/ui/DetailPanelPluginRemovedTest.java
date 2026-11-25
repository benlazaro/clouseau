package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import org.junit.jupiter.api.Test;

import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin removed in the Plugin Manager is unloaded, and calling its formatter afterwards throws
 * NoClassDefFoundError: an open tab's detail pane must use the app's formatters as they are now.
 */
class DetailPanelPluginRemovedTest {

    /** Stands in for an unloaded plugin's formatter: once removed, any call is the crash. */
    private static final class PluginFormatter implements LogFormatter {
        volatile boolean unloaded;
        @Override public String getName() { return "HEX2TXT"; }
        @Override public boolean canFormat(String message) { check(); return message.startsWith("0x"); }
        @Override public String format(String message) { check(); return "decoded"; }
        private void check() { if (unloaded) throw new NoClassDefFoundError("com/example/HexFormatter"); }
    }

    @Test
    void aRemovedPluginsFormatterIsNotCalledAgainAndItsToggleGoes() throws Exception {
        LogEntry entry = LogEntry.builder("0x48 0x69").message("0x48 0x69").build();
        PluginFormatter plugin = new PluginFormatter();
        AtomicReference<List<LogFormatter>> appFormatters = new AtomicReference<>(List.of(plugin));
        AtomicReference<DetailPanel> panel = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            var content = new DetailPanel.Content(entry, null, null, List.of(), 0, null, null);
            panel.set(new DetailPanel(appFormatters::get, List::<LogSyntaxHighlighter>of, () -> content));
            panel.get().refresh();
        });
        assertThat(toggles(panel.get())).containsOnlyKeys("HEX2TXT");

        // The Plugin Manager removes the plugin: the app's list changes and its classes are gone.
        plugin.unloaded = true;
        appFormatters.set(List.of());

        SwingUtilities.invokeAndWait(() -> panel.get().refresh());   // threw NoClassDefFoundError
        assertThat(toggles(panel.get())).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, JToggleButton> toggles(DetailPanel panel) throws Exception {
        var field = DetailPanel.class.getDeclaredField("formatToggles");
        field.setAccessible(true);
        return (Map<String, JToggleButton>) field.get(panel);
    }
}
