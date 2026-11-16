package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.EntryFilter;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The row menu's Hide Logger, Show Only Logger and Show All Loggers. */
class FilterBarLoggersTest {

    private static LogEntry from(String logger) {
        return new LogEntry(Instant.parse("2026-03-27T10:00:00Z"), LogEntry.LogLevel.INFO, logger, "main", "m", "raw", Map.of());
    }

    private static final List<LogEntry> ENTRIES = List.of(from("a.Gateway"), from("a.Router"), from("b.Cache"));

    private static List<String> shown(FilterBar bar) {
        EntryFilter f = bar.buildFilter();
        return ENTRIES.stream().filter(f).map(LogEntry::logger).toList();
    }

    @Test
    void hideShowOnlyAndShowAll() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger changes = new AtomicInteger();
            FilterBar bar = new FilterBar(changes::incrementAndGet);
            bar.updateLoggers(ENTRIES);

            bar.hideLoggers(Set.of("a.Router"));
            assertThat(shown(bar)).containsExactly("a.Gateway", "b.Cache");
            assertThat(bar.hiddenLoggerCount()).isEqualTo(1);

            bar.showOnlyLoggers(Set.of("b.Cache"));
            assertThat(shown(bar)).containsExactly("b.Cache");
            assertThat(bar.showsOnly(Set.of("b.Cache"))).isTrue();
            assertThat(bar.showsOnly(Set.of("a.Router"))).isFalse();
            assertThat(bar.hiddenLoggerCount()).isEqualTo(2);

            bar.showAllLoggers();
            assertThat(shown(bar)).hasSize(3);
            assertThat(bar.hiddenLoggerCount()).isZero();
            assertThat(changes).hasValue(3);
        });
    }

    @Test
    void showAllLeavesTheOtherFiltersAlone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FilterBar bar = new FilterBar(() -> {});
            bar.updateLoggers(ENTRIES);
            bar.setTimeRange("2026-03-27 11:00:00", "");
            bar.hideLoggers(Set.of("a.Router"));
            bar.showAllLoggers();
            assertThat(bar.timeButtonText()).isNotEqualTo("Time");
        });
    }

    @Test
    void hiddenLoggersAndFilesSurviveAReload() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FilterBar bar = new FilterBar(() -> {});
            bar.setSources(List.of("app.log", "db.log"));
            bar.updateLoggers(ENTRIES);
            bar.hideLoggers(Set.of("a.Router"));
            bar.setTimeRange("2026-03-26 00:00:00", "");   // local time: a day early anywhere

            bar.keepThroughReload();
            bar.clearLoggers();   // what every load does first
            // Still hidden before the reloaded rows name the logger, and after.
            assertThat(shown(bar)).containsExactly("a.Gateway", "b.Cache");
            bar.setSources(List.of("app.log", "db.log"));
            bar.updateLoggers(ENTRIES);
            assertThat(shown(bar)).containsExactly("a.Gateway", "b.Cache");
            assertThat(bar.hiddenLoggerCount()).isEqualTo(1);
            assertThat(bar.timeButtonText()).isNotEqualTo("Time");

            bar.showAllLoggers();
            assertThat(shown(bar)).hasSize(3);
        });
    }

    @Test
    void menuLabelsUseTheLoggersLastPart() {
        assertThat(RowMenu.shortLogger("com.example.gateway.route.RouteResolver")).isEqualTo("RouteResolver");
        assertThat(RowMenu.shortLogger("main")).isEqualTo("main");
        assertThat(RowMenu.shortLogger("trailing.")).isEqualTo("trailing.");
    }
}
