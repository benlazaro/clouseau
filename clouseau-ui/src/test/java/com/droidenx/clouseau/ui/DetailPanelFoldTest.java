package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.ToStringFormatter;
import com.droidenx.clouseau.core.ToStringHighlighter;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The detail pane collapses a formatted message's structures (D-34). */
class DetailPanelFoldTest {

    private static final String JSON = "Sent {\"user\":{\"id\":1,\"name\":\"Bob\"},\"tags\":[\"x\",\"y\"],\"ok\":true}";

    private final AtomicReference<DetailPanel.Content> current = new AtomicReference<>();

    private DetailPanel panel(String message) throws Exception {
        DetailPanel[] panel = new DetailPanel[1];
        edt(() -> {
            show(message);
            panel[0] = new DetailPanel(List.of(new JsonMessageFormatter(), new ToStringFormatter()),
                    List.of(new JsonSyntaxHighlighter(), new ToStringHighlighter()), current::get);
            panel[0].refresh();
        });
        return panel[0];
    }

    private void show(String message) {
        LogEntry entry = LogEntry.builder(message).message(message).build();
        current.set(new DetailPanel.Content(entry, null, null, List.of(), 0, null, null));
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static String text(DetailPanel panel) throws Exception {
        var doc = panel.textPane().getDocument();
        return doc.getText(0, doc.getLength());
    }

    @Test
    void jsonObjectsAndArraysOverSeveralLinesCanCollapse() throws Exception {
        DetailPanel panel = panel(JSON);
        assertThat(panel.foldOpens()).hasSize(3);     // the message, "user" and "tags"
        int user = panel.foldOpens().get(1);
        edt(() -> panel.toggleFoldAt(user));
        String shown = text(panel);
        assertThat(shown).contains("\"user\": { … 2 keys },").doesNotContain("\"name\"").contains("\"tags\": [");
        edt(() -> panel.toggleFoldAt(user));
        assertThat(text(panel)).contains("\"name\": \"Bob\"");
    }

    @Test
    void collapsingTheOuterOneHidesItAllAndCopyingGivesTheWholeText() throws Exception {
        DetailPanel panel = panel(JSON);
        edt(panel::collapseAll);
        String shown = text(panel);
        assertThat(shown).contains("Sent \n{ … 3 keys }");
        int length = panel.textPane().getDocument().getLength();
        String copied = panel.expandedText(0, length);
        assertThat(copied).contains("\"name\": \"Bob\"").contains("\"tags\": [").doesNotContain("…");
        // Opening the outer one shows its parts still collapsed, to open one by one.
        edt(() -> panel.toggleFoldAt(panel.foldOpens().get(0)));
        assertThat(text(panel)).contains("\"user\": { … 2 keys },").contains("\"tags\": [ … 2 items ],");
        edt(panel::expandAll);
        assertThat(text(panel)).doesNotContain("…");
    }

    @Test
    void aNewEntryOpensExpandedTheSameEntryStaysCollapsed() throws Exception {
        DetailPanel panel = panel(JSON);
        edt(panel::collapseAll);
        edt(panel::refresh);
        assertThat(panel.collapsedOpens()).hasSize(3);
        edt(() -> { show(JSON.replace("Bob", "Ann")); panel.selectionChanged(); });
        assertThat(panel.collapsedOpens()).isEmpty();
        assertThat(text(panel)).contains("\"name\": \"Ann\"");
    }

    @Test
    void findOpensWhatHidesAMatch() throws Exception {
        DetailPanel panel = panel(JSON);
        edt(() -> panel.toggleFoldAt(panel.foldOpens().get(1)));
        edt(() -> panel.findBar().open("Bob"));
        assertThat(panel.collapsedOpens()).isEmpty();
        assertThat(panel.findBar().matchCount()).isEqualTo(1);
    }

    @Test
    void findCountsCollapsedMatchesAndOpensOnlyWhereItSteps() throws Exception {
        DetailPanel panel = panel(JSON);
        int user = panel.foldOpens().get(1), tags = panel.foldOpens().get(2);
        edt(() -> { panel.toggleFoldAt(user); panel.toggleFoldAt(tags); });
        // "x" is in "tags" (collapsed) only; "Bob" in "user" (collapsed) only; "ok" stays shown.
        edt(() -> panel.findBar().open("\"ok\""));
        assertThat(panel.collapsedOpens()).containsExactlyInAnyOrder(user, tags);   // nothing hidden to go to
        edt(() -> panel.findBar().open("\""));    // every quote: some shown, some collapsed
        int count = panel.findBar().matchCount();
        assertThat(count).isEqualTo(JSON.chars().filter(c -> c == '"').count());
        // The first match is "user", which shows; stepping on reaches "id" inside it, which opens it.
        edt(() -> panel.findBar().step(+1));
        edt(() -> panel.findBar().step(+1));
        assertThat(panel.collapsedOpens()).containsExactly(tags);
        assertThat(text(panel)).contains("\"name\": \"Bob\"").contains("\"tags\": [ … 2 items ]");
        assertThat(panel.findBar().matchCount()).isEqualTo(count);
    }

    @Test
    void collapsingWhileFindingKeepsItCollapsedAndCounted() throws Exception {
        DetailPanel panel = panel(JSON);
        edt(() -> panel.findBar().open("Bob"));
        assertThat(panel.findBar().counterText()).isEqualTo("1 / 1");
        int user = panel.foldOpens().get(1);
        edt(() -> panel.toggleFoldAt(user));
        assertThat(panel.collapsedOpens()).containsExactly(user);
        assertThat(panel.findBar().counterText()).isEqualTo("1 / 1");
        assertThat(panel.textPane().getHighlighter().getHighlights())
                .noneMatch(h -> h.getPainter().getClass().getSimpleName().equals("MarkPainter"));
        edt(() -> panel.findBar().step(+1));    // going to it again opens it
        assertThat(panel.collapsedOpens()).isEmpty();
    }

    @Test
    void anObjectsToStringCollapsesToItsFieldCount() throws Exception {
        String msg = "Saved Order(id=12, customer=Customer(name=Bob Smith, email=bob@example.com, phone=555-0100), "
                + "status=SHIPPED, note=left at the front door) in 5ms";
        DetailPanel panel = panel(msg);
        assertThat(panel.foldOpens()).isNotEmpty();
        edt(() -> panel.toggleFoldAt(panel.foldOpens().get(0)));
        assertThat(text(panel)).contains("Saved Order( … 4 fields ) in 5ms");
    }

    @Test
    void aOneLineMessageHasNothingToCollapse() throws Exception {
        DetailPanel panel = panel("Saved Point[x=1, y=2]");
        assertThat(panel.foldOpens()).isEmpty();
    }
}
