package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Entry-limit behavior (D-7): static loads keep every line, live sessions are capped. */
class LogTableModelTest {

    private LogTableModel model;
    private int next;

    @BeforeEach
    void setUp() {
        model = new LogTableModel();
        model.setMaxEntries(100);
    }

    @AfterEach
    void tearDown() {
        model.dispose();
    }

    private List<LogEntry> entries(int count) {
        List<LogEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String line = "line " + next++;
            list.add(new LogEntry(null, LogEntry.LogLevel.UNKNOWN, null, null, line, line, Map.of()));
        }
        return list;
    }

    private String firstMessage() {
        return model.getEntry(0).message();
    }

    @Test
    void staticLoadIsNeverTrimmed() {
        model.appendBatch(entries(150));
        model.appendBatch(entries(150));

        assertThat(model.getTotalCount()).isEqualTo(300);
        assertThat(model.getTrimmedCount()).isZero();
        assertThat(firstMessage()).isEqualTo("line 0");
    }

    @Test
    void liveSessionIsCappedAtTheLimit() {
        model.appendLive(entries(60));
        model.appendLive(entries(60));

        assertThat(model.getTotalCount()).isEqualTo(100);
        assertThat(model.getRowCount()).isEqualTo(100);
        assertThat(model.getTrimmedCount()).isEqualTo(20);
        assertThat(firstMessage()).isEqualTo("line 20");
    }

    @Test
    void followingABigFileKeepsWhatWasLoaded() {
        model.appendBatch(entries(300));   // larger than the limit
        model.appendLive(entries(10));

        // The cap is the loaded size, so only as many old lines go as new ones arrive.
        assertThat(model.getTotalCount()).isEqualTo(300);
        assertThat(model.getTrimmedCount()).isEqualTo(10);
        assertThat(firstMessage()).isEqualTo("line 10");
    }

    @Test
    void followingASmallFileUsesTheLimit() {
        model.appendBatch(entries(50));
        model.appendLive(entries(80));

        assertThat(model.getTotalCount()).isEqualTo(100);
        assertThat(model.getTrimmedCount()).isEqualTo(30);
    }

    @Test
    void unlimitedNeverTrims() {
        model.setMaxEntries(0);
        model.appendLive(entries(500));

        assertThat(model.getTotalCount()).isEqualTo(500);
        assertThat(model.getTrimmedCount()).isZero();
    }

    @Test
    void clearResetsTrimmedCountAndCap() {
        model.appendBatch(entries(300));
        model.appendLive(entries(10));
        model.clear();
        drainEdt();   // off the EDT, clear() is posted there with invokeLater
        assertThat(model.getTrimmedCount()).isZero();

        model.appendLive(entries(150));
        assertThat(model.getTotalCount()).isEqualTo(100);   // back to the plain limit
    }

    @Test
    void ordinalsSurviveTrimming() {
        model.appendLive(entries(120));   // 20 dropped

        assertThat(model.ordinalAt(0)).isEqualTo(20);
        assertThat(model.entryAtOrdinal(20).message()).isEqualTo("line 20");
        assertThat(model.entryAtOrdinal(19)).isNull();
        assertThat(model.rowOfOrdinal(25)).isEqualTo(5);
        assertThat(model.nextOrdinal()).isEqualTo(120);
    }

    @Test
    void highlightsFollowTheirEntryAndGoWhenItIsDropped() {
        model.appendLive(entries(100));
        model.setHighlight(10, java.awt.Color.RED);   // ordinal 10
        model.setHighlight(50, java.awt.Color.BLUE);  // ordinal 50

        model.appendLive(entries(20));                // ordinals 0..19 dropped

        assertThat(model.getHighlight(model.rowOfOrdinal(50))).isEqualTo(java.awt.Color.BLUE);
        assertThat(model.getHighlightedModelRows(null)).containsExactly(30);
        model.appendLive(entries(40));                // ordinal 50 dropped too
        assertThat(model.hasHighlights()).isFalse();
    }

    @Test
    void filteredRowsMapBackToOrdinals() throws Exception {
        model.appendBatch(entries(10));
        model.applyFilter(e -> e.message().endsWith("5") || e.message().endsWith("7"));
        // The filter runs on a background thread and posts its result to the EDT.
        for (int i = 0; i < 50 && model.getRowCount() != 2; i++) {
            Thread.sleep(20);
            drainEdt();
        }

        assertThat(model.getRowCount()).isEqualTo(2);
        assertThat(model.ordinalAt(1)).isEqualTo(7);
        assertThat(model.rowOfOrdinal(5)).isZero();
        assertThat(model.rowOfOrdinal(6)).isEqualTo(-1);
    }

    @Test
    void clearingOneColorLeavesTheOthersAndReachesHiddenRows() throws Exception {
        model.appendBatch(entries(10));
        model.setHighlight(2, java.awt.Color.RED);
        model.setHighlight(5, java.awt.Color.RED);
        model.setHighlight(7, java.awt.Color.BLUE);
        model.applyFilter(e -> e.message().endsWith("5"));
        for (int i = 0; i < 50 && model.getRowCount() != 1; i++) {
            Thread.sleep(20);
            drainEdt();
        }

        assertThat(model.highlightOrdinals(java.awt.Color.RED)).containsExactlyInAnyOrder(2, 5);
        model.clearHighlights(java.awt.Color.RED);   // row 2 is hidden by the filter, and goes too

        assertThat(model.highlightCounts()).containsOnlyKeys(java.awt.Color.BLUE);
        assertThat(model.highlightOrdinals(java.awt.Color.RED)).isEmpty();
    }

    @Test
    void highlightCountsKeepTheRowsTheFiltersHide() throws Exception {
        model.appendBatch(entries(10));
        model.setHighlight(2, java.awt.Color.RED);
        model.setHighlight(5, java.awt.Color.RED);
        model.setHighlight(7, java.awt.Color.BLUE);
        model.applyFilter(e -> e.message().endsWith("5"));
        for (int i = 0; i < 50 && model.getRowCount() != 1; i++) {
            Thread.sleep(20);
            drainEdt();
        }

        java.util.Map<java.awt.Color, int[]> counts = model.highlightCounts();
        assertThat(counts.get(java.awt.Color.RED)).containsExactly(2, 1);    // one of two shown
        assertThat(counts.get(java.awt.Color.BLUE)).containsExactly(1, 0);   // all hidden, still counted
        assertThat(model.getHighlightedModelRows(null)).containsExactly(0);
        assertThat(model.getHighlightedModelRows(java.awt.Color.BLUE)).isEmpty();
    }

    @Test
    void theNumberColumnShowsTheEntrysNumberInTheFileWhateverIsFiltered() throws Exception {
        model.appendBatch(entries(10));
        assertThat(model.getValueAt(7, 0)).isEqualTo(8);
        model.applyFilter(e -> e.message().endsWith("7"));
        for (int i = 0; i < 50 && model.getRowCount() != 1; i++) {
            Thread.sleep(20);
            drainEdt();
        }
        assertThat(model.getRowCount()).isEqualTo(1);
        assertThat(model.getValueAt(0, 0)).isEqualTo(8);      // not 1: its place in the file
        assertThat(model.lineNumber(0)).isEqualTo(8);
    }

    @Test
    void aFileHeldInMemoryCountsItsBlankLinesToo() {
        com.droidenx.clouseau.core.BlankLines blanks = new com.droidenx.clouseau.core.BlankLines();
        blanks.add(0);      // a blank first line
        blanks.add(2);      // and one after the second entry
        model.setMemoryBlanks(blanks);
        model.appendBatch(entries(4));
        assertThat(model.getColumnName(0)).isEqualTo("Line");
        assertThat(java.util.stream.IntStream.range(0, 4).map(model::numberAt).toArray())
                .containsExactly(2, 3, 5, 6);
        assertThat(model.lineCount()).isEqualTo(6);
    }

    @Test
    void mergedAndLiveTabsNumberRowsByPosition() {
        model.setNumbering(LogTableModel.Numbering.RECEIVED);
        model.appendLive(entries(3));
        assertThat(model.getColumnName(0)).isEqualTo("#");
        assertThat(model.getValueAt(2, 0)).isEqualTo(3);
    }

    @Test
    void aLiveTabKeepsCountingAfterOldEntriesAreDropped() {
        model.appendLive(entries(120));   // 20 dropped
        assertThat(model.getValueAt(0, 0)).isEqualTo(21);
    }

    private static void drainEdt() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
