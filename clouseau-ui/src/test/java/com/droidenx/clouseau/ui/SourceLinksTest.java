package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.core.SourceRefs;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Open in IDE: the detail pane's stack frame links, and the links VS Code and Cursor open. */
class SourceLinksTest {

    @Test
    void stackFramesInTheMessageBecomeLinks() throws Exception {
        LogEntry entry = LogEntry.builder("2026-11-23 10:00:00 ERROR c.a.OrderService - Order failed")
                .level(LogLevel.ERROR).logger("com.acme.OrderService").message("Order failed").build();
        List<LogEntry> trace = List.of(
                LogEntry.builder("java.lang.IllegalStateException: boom").build(),
                LogEntry.builder("\tat com.acme.OrderService.place(OrderService.java:42)").build(),
                LogEntry.builder("\tat java.base/java.lang.Thread.run(Thread.java:1583)").build());
        DetailPanel.Content content = new DetailPanel.Content(entry, null, null, trace, 0, null, null);

        AtomicReference<DetailPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            DetailPanel p = new DetailPanel(List.of(), List.of(), () -> content);
            p.refresh();
            panel.set(p);
        });

        List<SourceRefs.Found> links = panel.get().sourceLinks();
        assertThat(links).extracting(SourceRefs.Found::ref).containsExactly(
                new SourceRefs.SourceRef("com/acme/OrderService.java", 42),
                new SourceRefs.SourceRef("java/lang/Thread.java", 1583));
        StyledDocument doc = panel.get().textPane().getStyledDocument();
        SourceRefs.Found first = links.get(0);
        assertThat(doc.getText(first.start(), first.end() - first.start())).isEqualTo("OrderService.java:42");
        assertThat(StyleConstants.isUnderline(doc.getCharacterElement(first.start()).getAttributes())).isTrue();
        // The text before the link isn't one.
        assertThat(StyleConstants.isUnderline(doc.getCharacterElement(first.start() - 2).getAttributes())).isFalse();
    }

    @Test
    void anEntryWithoutFramesHasNoLinks() throws Exception {
        LogEntry entry = LogEntry.builder("INFO started").message("Server at Main.java:12 started").build();
        DetailPanel.Content content = new DetailPanel.Content(entry, null, null, List.of(), 0, null, null);
        AtomicReference<DetailPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            DetailPanel p = new DetailPanel(List.of(), List.of(), () -> content);
            p.refresh();
            panel.set(p);
        });
        assertThat(panel.get().sourceLinks()).isEmpty();
    }

    @Test
    void vsCodeAndCursorLinksCarryTheFullPathAndLine() throws Exception {
        Path file = Path.of("/src/my app/com/acme/Foo.java").toAbsolutePath();
        String path = file.toString().replace('\\', '/');
        if (!path.startsWith("/")) path = "/" + path;
        String encoded = path.replace(" ", "%20");

        URI vscode = SourceOpener.editorLink(AppPrefs.SourceEditor.VSCODE, file, 42);
        assertThat(vscode.toString()).isEqualTo("vscode://file" + encoded + ":42");
        URI cursor = SourceOpener.editorLink(AppPrefs.SourceEditor.CURSOR, file, 0);
        assertThat(cursor.toString()).isEqualTo("cursor://file" + encoded);
    }
}
