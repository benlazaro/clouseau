package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The find bar and highlight navigation bar, split out of TabPanel (G-9). */
class NavBarsTest {

    private LogTableModel model;
    private JTable table;

    @BeforeEach
    void setUp() throws Exception {
        onEdt(() -> {
            model = new LogTableModel();
            List<LogEntry> entries = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String msg = (i == 3 || i == 8) ? "found the needle " + i : "hay " + i;
                entries.add(new LogEntry(Instant.ofEpochSecond(i), LogLevel.INFO, "app.Logger", "main",
                        msg, msg, Map.of()));
            }
            model.appendBatch(entries);
            table = new JTable(model);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> model.dispose());
    }

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { out.set(c.call()); } catch (Exception e) { throw new RuntimeException(e); }
        });
        return out.get();
    }

    private int selectedRow() throws Exception {
        return onEdt(() -> table.getSelectedRow());
    }

    // ── Find bar ──────────────────────────────────────────────────────────────

    @Test
    void matchesAnyColumnIgnoringCase() {
        LogEntry e = new LogEntry(Instant.EPOCH, LogLevel.WARN, "com.Example", "worker-7",
                "Disk Almost Full", "raw", Map.of("traceId", "abc123"));

        assertThat(FindBar.matches(e, "almost")).isTrue();
        assertThat(FindBar.matches(e, "com.ex")).isTrue();
        assertThat(FindBar.matches(e, "worker-7")).isTrue();
        assertThat(FindBar.matches(e, "warn")).isTrue();
        assertThat(FindBar.matches(e, "abc1")).isTrue();
        assertThat(FindBar.matches(e, "nothing")).isFalse();
        assertThat(FindBar.matches(new LogEntry(null, null, null, null, null, "raw", null), "x")).isFalse();
    }

    @Test
    void matchCountReadsNaturally() {
        String pattern = Messages.get("find.count");
        assertThat(MessageFormat.format(pattern, 0)).isEqualTo("0 matches");
        assertThat(MessageFormat.format(pattern, 1)).isEqualTo("1 match");
        assertThat(MessageFormat.format(pattern, 12)).isEqualTo("12 matches");
    }

    @Test
    void findSelectsMatchesInTurnAndWraps() throws Exception {
        FindBar bar = onEdt(() -> new FindBar(table, FindBar.searchIn(model)));
        onEdt(() -> bar.search("NEEDLE"));

        // The search runs in the background; the first match is selected when it finishes.
        long deadline = System.currentTimeMillis() + 5_000;
        while (!"1 / 2".equals(onEdt(bar::counterText)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(onEdt(bar::counterText)).isEqualTo("1 / 2");
        assertThat(selectedRow()).isEqualTo(3);
        assertThat(onEdt(() -> bar.isMatch(3) && bar.isMatch(8) && !bar.isMatch(4))).isTrue();

        onEdt(() -> bar.navigate(+1));
        assertThat(selectedRow()).isEqualTo(8);
        onEdt(() -> bar.navigate(+1));
        assertThat(selectedRow()).isEqualTo(3);
        onEdt(() -> bar.navigate(-1));
        assertThat(selectedRow()).isEqualTo(8);

        onEdt(bar::clearMatches);
        assertThat(onEdt(() -> bar.isMatch(3))).isFalse();
        assertThat(onEdt(bar::counterText)).isEmpty();
    }

    // ── Highlight navigation bar ─────────────────────────────────────────────

    @Test
    void highlightBarShowsOnlyWhileRowsAreHighlighted() throws Exception {
        HighlightNavBar bar = onEdt(() -> new HighlightNavBar(table, model));
        onEdt(bar::refresh);
        assertThat(onEdt(bar::isVisible)).isFalse();

        onEdt(() -> { model.setHighlight(2, Color.RED); bar.refresh(); });
        assertThat(onEdt(bar::isVisible)).isTrue();

        onEdt(() -> { model.clearAllHighlights(); bar.refresh(); });
        assertThat(onEdt(bar::isVisible)).isFalse();
    }

    @Test
    void highlightBarStepsThroughHighlightedRowsAndWraps() throws Exception {
        HighlightNavBar bar = onEdt(() -> new HighlightNavBar(table, model));
        onEdt(() -> {
            model.setHighlight(2, Color.RED);
            model.setHighlight(5, Color.RED);
            model.setHighlight(7, Color.BLUE);
            bar.refresh();
            table.setRowSelectionInterval(3, 3);
            bar.updatePosition();
        });
        assertThat(onEdt(bar::positionText)).isEqualTo("1 / 3");

        onEdt(() -> bar.navigate(+1));
        assertThat(selectedRow()).isEqualTo(5);
        assertThat(onEdt(bar::positionText)).isEqualTo("2 / 3");
        onEdt(() -> bar.navigate(+1));
        assertThat(selectedRow()).isEqualTo(7);
        onEdt(() -> bar.navigate(+1));
        assertThat(selectedRow()).isEqualTo(2);
        onEdt(() -> bar.navigate(-1));
        assertThat(selectedRow()).isEqualTo(7);
    }
}
