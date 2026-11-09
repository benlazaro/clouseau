package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.core.EntryFilter;
import com.droidenx.clouseau.core.Session;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The time range behind the filter bar's Time button. */
class FilterBarTimeTest {

    private static void onEdt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void theButtonShowsTheRangeWithTimesOnlyWhenBothEndsAreOnOneDay() throws Exception {
        onEdt(() -> {
            AtomicInteger changes = new AtomicInteger();
            FilterBar bar = new FilterBar(changes::incrementAndGet);
            assertThat(bar.timeButtonText()).isEqualTo("Time  ▾");

            bar.setTimeRange("2026-03-27 10:00:00", "2026-03-27 10:15:00");
            assertThat(bar.timeButtonText()).isEqualTo("10:00:00 → 10:15:00  ▾");
            assertThat(changes).hasValue(1);

            bar.setTimeRange("2026-03-27 10:00:00", "2026-03-27 10:15:00");   // unchanged: no refilter
            assertThat(changes).hasValue(1);

            bar.setTimeRange("2026-03-27 10:00:00", "");
            assertThat(bar.timeButtonText()).isEqualTo("2026-03-27 10:00:00 → …  ▾");

            bar.setTimeRange("2026-03-27 23:00:00", "2026-03-28 01:00:00");
            assertThat(bar.timeButtonText()).isEqualTo("2026-03-27 23:00:00 → 2026-03-28 01:00:00  ▾");
        });
    }

    @Test
    void sessionsSaveAndRestoreTheRangeAndClearAllFiltersRemovesIt() throws Exception {
        onEdt(() -> {
            FilterBar bar = new FilterBar(() -> {});
            bar.setTimeRange("10:00:00", "");
            Session.Filters saved = bar.state();
            assertThat(saved).isNotNull();
            assertThat(saved.from()).isEqualTo("10:00:00");
            assertThat(saved.to()).isEmpty();

            FilterBar restored = new FilterBar(() -> {});
            restored.restore(saved);
            assertThat(restored.timeButtonText()).isEqualTo("10:00:00 → …  ▾");
            assertThat(restored.state()).isEqualTo(saved);

            restored.clear();
            assertThat(restored.timeButtonText()).isEqualTo("Time  ▾");
            assertThat(restored.state()).isNull();
        });
    }

    @Test
    void anUnchangedBarHasNoState() throws Exception {
        onEdt(() -> assertThat(new FilterBar(() -> {}).state()).isNull());
        assertThat(FilterBar.isDefault(new Session.Filters(
                List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"), List.of(), Map.of(), "", "", "", List.of())))
                .isTrue();
    }
    private static LogEntry at(String time) {
        Instant t = LocalDateTime.parse(time).atZone(AppPrefs.getDisplayTimezone()).toInstant();
        return new LogEntry(t, LogEntry.LogLevel.INFO, "a.B", "main", "m", "raw", Map.of());
    }

    @Test
    void anEndCanCountFromTheOtherAndHoursAndMinutesAreEnough() throws Exception {
        onEdt(() -> {
            FilterBar bar = new FilterBar(() -> {});
            bar.setTimeRange("2026-03-27 10:00", "+5m");
            EntryFilter f = bar.buildFilter();
            assertThat(f.test(at("2026-03-27T09:59:59"))).isFalse();
            assertThat(f.test(at("2026-03-27T10:04:59"))).isTrue();
            assertThat(f.test(at("2026-03-27T10:05:01"))).isFalse();

            bar.setTimeRange("-90s", "2026-03-27 10:00:00");
            f = bar.buildFilter();
            assertThat(f.test(at("2026-03-27T09:58:29"))).isFalse();
            assertThat(f.test(at("2026-03-27T09:58:31"))).isTrue();
            assertThat(f.test(at("2026-03-27T10:00:01"))).isFalse();
        });
        assertThat(FilterBar.relative("+5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(FilterBar.relative("-250ms")).isEqualTo(Duration.ofMillis(-250));
        assertThat(FilterBar.relative("+2H")).isEqualTo(Duration.ofHours(2));
        assertThat(FilterBar.relative("5m")).isNull();
        assertThat(FilterBar.relative("10:00")).isNull();
    }

    @Test
    void aRowSetsOneEndAndKeepsTheOtherWithTheRowItselfIncluded() throws Exception {
        onEdt(() -> {
            FilterBar bar = new FilterBar(() -> {});
            LogEntry row = at("2026-03-27T10:00:00.250");
            bar.setTimeRange("", "+1m");
            bar.showFrom(row.timestamp());
            assertThat(bar.state().from()).isEqualTo("2026-03-27 10:00:00.250");
            assertThat(bar.state().to()).isEqualTo("+1m");
            assertThat(bar.buildFilter().test(row)).isTrue();
            assertThat(bar.buildFilter().test(at("2026-03-27T10:00:00.249"))).isFalse();

            bar.showUpTo(at("2026-03-27T10:30:00").timestamp());
            assertThat(bar.state().from()).isEqualTo("2026-03-27 10:00:00.250");
            assertThat(bar.state().to()).isEqualTo("2026-03-27 10:30:00.000");
            assertThat(bar.timeButtonText()).isEqualTo("10:00:00.250 \u2192 10:30:00.000  \u25BE");
        });
    }
}
