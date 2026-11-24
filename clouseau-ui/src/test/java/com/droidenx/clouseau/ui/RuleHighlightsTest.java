package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.core.EntryIndex;
import com.droidenx.clouseau.core.HighlightRule;
import com.droidenx.clouseau.core.HighlightRule.Column;
import com.droidenx.clouseau.core.HighlightRules;
import com.droidenx.clouseau.core.IndexedEntries;
import com.droidenx.clouseau.core.LineParser;
import com.droidenx.clouseau.core.LineScanner;
import com.droidenx.clouseau.ui.theme.ClouseauColors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Auto-highlight rules in a tab's model (D-29): matched as rows arrive, again when the rules change. */
class RuleHighlightsTest {

    @TempDir Path dir;
    private LogTableModel model;
    private final Color[] palette = ClouseauColors.highlightColors();

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            model = new LogTableModel();
            model.setMaxEntries(20);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        SwingUtilities.invokeAndWait(() -> model.dispose());
    }

    private interface EdtAction { void run() throws Exception; }

    private static void onEdt(EdtAction action) throws Exception {
        Exception[] failure = {null};
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); } catch (Exception e) { failure[0] = e; }
        });
        if (failure[0] != null) throw failure[0];
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            boolean[] done = {false};
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) return;
            Thread.sleep(20);
        }
    }

    private static LogEntry entry(LogLevel level, String message) {
        return LogEntry.builder(message).timestamp(Instant.EPOCH).level(level).message(message).build();
    }

    private static LogEntry trace(String line) {
        return LogEntry.builder(line).build();
    }

    private static HighlightRules rules(HighlightRule... rules) {
        return HighlightRules.of(List.of(rules));
    }

    private static HighlightRule text(String text, int color) {
        return new HighlightRule(true, Column.ANY, null, text, false, false, null, color);
    }

    @Test
    void rowsArePaintedAsTheyArriveAndATraceMarksItsEntryLater() throws Exception {
        onEdt(() -> {
            model.setHighlightRules(rules(text("timeout", 2)));
            model.appendLive(List.of(entry(LogLevel.INFO, "start"), entry(LogLevel.ERROR, "request failed")));
            assertThat(model.getRowColor(1)).isNull();
            // The trace comes in the next batch, and marks the entry already shown.
            model.appendLive(List.of(trace("  caused by SocketTimeout"), entry(LogLevel.INFO, "Timeout again")));

            assertThat(model.getRowColor(0)).isNull();
            assertThat(model.getRowColor(1)).isEqualTo(palette[2]);
            assertThat(model.getRowColor(2)).isEqualTo(palette[2]);
            assertThat(model.getRowColor(3)).isEqualTo(palette[2]);
            assertThat(model.highlightCounts().get(palette[2])).containsExactly(3, 3, 3);
            assertThat(model.highlightedRows(null)).containsExactly(1, 2, 3);
        });
    }

    @Test
    void aChipsTooltipSaysHowManyAutoHighlightsColored() {
        assertThat(HighlightNavBar.chipTip(5, 4, 4, 0)).isEqualTo("Blue: 4 highlighted");
        assertThat(HighlightNavBar.chipTip(5, 4, 3, 3)).isEqualTo("Blue: 4 highlighted, 3 by auto-highlights (1 hidden by filters)");
        assertThat(HighlightNavBar.chipTip(0, 6, 6, 6)).isEqualTo("Red: 6 highlighted, all by auto-highlights");
        assertThat(HighlightNavBar.chipTip(0, 2, 0, 1)).isEqualTo("Red: 2 highlighted, 1 by auto-highlights, all hidden by filters");
    }

    @Test
    void theUsersOwnHighlightWinsAndClearingItLeavesTheRule() throws Exception {
        onEdt(() -> {
            model.setHighlightRules(rules(text("disk", 0)));
            model.appendLive(List.of(entry(LogLevel.WARN, "disk full"), entry(LogLevel.INFO, "ok")));
            model.setHighlight(0, palette[5]);

            assertThat(model.getRowColor(0)).isEqualTo(palette[5]);
            assertThat(model.getHighlight(0)).isEqualTo(palette[5]);
            assertThat(model.highlightCounts()).containsOnlyKeys(palette[5]);

            model.clearAllHighlights();
            assertThat(model.getRowColor(0)).isEqualTo(palette[0]);
            assertThat(model.getHighlight(0)).isNull();
            assertThat(model.hasHighlights()).isFalse();
            assertThat(model.hasRuleHighlights()).isTrue();
        });
    }

    @Test
    void changedRulesAreMatchedAgainAndTurningThemOffClearsTheColors() throws Exception {
        onEdt(() -> model.appendLive(List.of(entry(LogLevel.INFO, "alpha"), entry(LogLevel.ERROR, "beta"),
                entry(LogLevel.WARN, "gamma"))));
        onEdt(() -> model.setHighlightRules(rules(
                new HighlightRule(true, Column.ANY, null, "", false, false, LogLevel.WARN, 4))));
        await(() -> model.hasRuleHighlights());
        onEdt(() -> {
            assertThat(model.highlightedRows(palette[4])).containsExactly(1, 2);
            model.setHighlightRules(HighlightRules.NONE);
            assertThat(model.hasRuleHighlights()).isFalse();
            assertThat(model.getRowColor(1)).isNull();
            // With no rules, new rows aren't matched.
            model.appendLive(List.of(entry(LogLevel.ERROR, "delta")));
            assertThat(model.getRowColor(3)).isNull();
        });
    }

    @Test
    void entriesDroppedFromALiveTabTakeTheirRuleColorWithThem() throws Exception {
        onEdt(() -> {
            model.setHighlightRules(rules(text("x", 1)));
            List<LogEntry> batch = new ArrayList<>();
            for (int i = 0; i < 30; i++) batch.add(entry(LogLevel.INFO, i % 2 == 0 ? "x" + i : "y" + i));
            model.appendLive(batch);   // the limit is 20: the 10 oldest go

            assertThat(model.getTotalCount()).isEqualTo(20);
            assertThat(model.highlightCounts().get(palette[1])).containsExactly(10, 10, 10);
            assertThat(model.getEntry(0).message()).isEqualTo("x10");
            assertThat(model.getRowColor(0)).isEqualTo(palette[1]);
        });
    }

    @Test
    void anIndexedFileIsMatchedFromDiskWhenTheRulesChange() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 500; i++) lines.add(i % 100 == 7 ? "boom " + i : "line " + i);
        Path file = Files.writeString(dir.resolve("app.log"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        LineParser parser = new LineParser(Optional.empty(), List.of(), null);
        EntryIndex index = new EntryIndex();
        IndexedEntries entries = IndexedEntries.open(file, index, parser);
        List<LogEntry> parsed = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            LineScanner.scan(in, (o, l, t) -> {
                LogEntry e = parser.parse(t);
                index.add(o, l, e);
                parsed.add(e);
            }, () -> false);
        }
        onEdt(() -> {
            model.clear();
            model.beginIndexedLoad(entries);
            model.appendBatch(parsed);
        });
        await(() -> model.getRowCount() == 500);

        onEdt(() -> model.setHighlightRules(rules(text("BOOM", 6))));
        await(() -> model.hasRuleHighlights());
        onEdt(() -> {
            // No parser: every line is a continuation of the first, and each match is colored,
            // the first line too as the entry they belong to.
            assertThat(model.highlightedRows(palette[6])).containsExactly(0, 7, 107, 207, 307, 407);
            assertThat(model.getRowColor(107)).isEqualTo(palette[6]);
        });
    }
}
