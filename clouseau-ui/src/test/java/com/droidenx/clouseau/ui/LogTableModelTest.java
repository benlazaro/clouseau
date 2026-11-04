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

    private static void drainEdt() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
