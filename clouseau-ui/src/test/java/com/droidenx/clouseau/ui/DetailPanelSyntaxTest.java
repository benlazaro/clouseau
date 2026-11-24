package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogSyntaxHighlighter.Role;
import com.droidenx.clouseau.core.StackTraceHighlighter;
import com.droidenx.clouseau.core.ToStringFormatter;
import com.droidenx.clouseau.core.ToStringHighlighter;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import org.junit.jupiter.api.Test;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The detail pane draws a message formatted and in its highlighters' colors. */
class DetailPanelSyntaxTest {

    @Test
    void anObjectsToStringIsLaidOutAndColored() throws Exception {
        String msg = "Saved Order(id=12, customer=Customer(name=Bob Smith, email=null), "
                + "items=[Item(sku=A-1, qty=2, price=9.99)], status=SHIPPED) in 5ms";
        LogEntry entry = LogEntry.builder(msg).message(msg).build();
        AtomicReference<StyledDocument> doc = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            DetailPanel.Content content = new DetailPanel.Content(entry, null, null, List.of(), 0, null, null);
            DetailPanel panel = new DetailPanel(List.of(new ToStringFormatter()),
                    List.of(new StackTraceHighlighter(), new ToStringHighlighter()), () -> content);
            panel.refresh();
            try {
                var area = DetailPanel.class.getDeclaredField("detailArea");
                area.setAccessible(true);
                doc.set(((JTextPane) area.get(panel)).getStyledDocument());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        String text = doc.get().getText(0, doc.get().getLength());
        assertThat(text).contains("Saved Order(\n").contains("\n) in 5ms");
        assertThat(colorAt(doc.get(), text, "Order(")).isEqualTo(ClouseauColors.syntaxColor(Role.ACCENT));
        assertThat(colorAt(doc.get(), text, "id=")).isEqualTo(ClouseauColors.syntaxColor(Role.KEY));
        assertThat(colorAt(doc.get(), text, "12,")).isEqualTo(ClouseauColors.syntaxColor(Role.NUMBER));
        assertThat(colorAt(doc.get(), text, "Bob Smith")).isEqualTo(ClouseauColors.syntaxColor(Role.STRING));
    }

    private static Color colorAt(StyledDocument doc, String text, String what) {
        return StyleConstants.getForeground(doc.getCharacterElement(text.indexOf(what)).getAttributes());
    }
}
