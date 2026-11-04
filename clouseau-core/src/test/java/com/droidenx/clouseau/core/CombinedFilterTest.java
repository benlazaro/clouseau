package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter bar's filter must decide the same on the index as on the entry (D-16 step 3):
 * {@code testIndex} never drops a row {@code test} keeps, and when no text is needed it
 * is exact.
 */
class CombinedFilterTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final LogLevel[] LEVELS = {
        LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL,
        LogLevel.UNKNOWN, null
    };

    /** Varied entries: every level (and none), loggers with gaps, sub-millisecond times, fields. */
    private static List<LogEntry> entries() {
        List<LogEntry> list = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            Instant ts = i % 7 == 0 ? null : T0.plusNanos(i * 1_000_250L);   // not whole millis
            String logger = i % 5 == 0 ? null : "com.acme." + (char) ('a' + i % 4);
            Map<String, String> fields = new HashMap<>();
            if (i % 3 != 0) fields.put("app", i % 2 == 0 ? "web" : "  ");      // blank counts as empty
            fields.put("req", "r" + i);                                        // too many values to index
            String message = "event " + i + (i % 11 == 0 ? " Timeout" : "") + (i % 13 == 0 ? " (x)" : "");
            list.add(new LogEntry(ts, LEVELS[i % LEVELS.length], logger, "t" + (i % 3), message, message, fields));
        }
        return list;
    }

    private static CombinedFilter filter(Set<LogLevel> levels, Set<String> loggers, boolean noLoggers,
                                         Map<String, Set<String>> fields, Instant from, Instant to, String text) {
        return new CombinedFilter(levels, loggers, noLoggers, fields, from, to, text);
    }

    private static List<CombinedFilter> filters() {
        Set<LogLevel> all = EnumSet.allOf(LogLevel.class);
        return List.of(
            filter(all, Set.of(), false, Map.of(), null, null, null),
            filter(EnumSet.of(LogLevel.WARN, LogLevel.ERROR), Set.of(), false, Map.of(), null, null, null),
            filter(EnumSet.complementOf(EnumSet.of(LogLevel.UNKNOWN)), Set.of(), false, Map.of(), null, null, null),
            filter(all, Set.of("com.acme.a", "com.acme.c"), false, Map.of(), null, null, null),
            filter(all, Set.of("com.acme.a"), true, Map.of(), null, null, null),
            filter(all, Set.of(), false, Map.of("app", Set.of(CombinedFilter.EMPTY_VALUE)), null, null, null),
            filter(all, Set.of(), false, Map.of("app", Set.of("web")), null, null, null),
            filter(all, Set.of(), false, Map.of("missing", Set.of(CombinedFilter.EMPTY_VALUE)), null, null, null),
            filter(all, Set.of(), false, Map.of(), T0.plusMillis(500), T0.plusMillis(1_500), null),
            filter(all, Set.of(), false, Map.of(), T0.plusMillis(2_000), null, null),
            filter(all, Set.of(), false, Map.of(), null, null, "timeout"),
            filter(all, Set.of(), false, Map.of(), null, null, "event 1[0-9]$"),
            filter(all, Set.of(), false, Map.of(), null, null, "(x"),                   // not a regex: literal
            filter(all, Set.of(), false, Map.of("req", Set.of("r10", "r11")), null, null, null),
            filter(EnumSet.of(LogLevel.INFO), Set.of("com.acme.b"), false,
                    Map.of("app", Set.of("web")), T0, T0.plusMillis(2_500), "event"));
    }

    @Test
    void indexDecidesLikeTheEntry() {
        List<LogEntry> entries = entries();
        EntryIndex index = new EntryIndex(6);
        for (int i = 0; i < entries.size(); i++) index.add(i * 100L, 99, entries.get(i));

        List<CombinedFilter> filters = filters();
        for (int k = 0; k < filters.size(); k++) {
            CombinedFilter f = filters.get(k);
            boolean needsText = f.needsText(index);
            int passed = 0;
            for (int row = 0; row < entries.size(); row++) {
                LogEntry e = entries.get(row);
                boolean expected = f.test(e);
                boolean onIndex = f.testIndex(index, row);
                if (expected) assertThat(onIndex).as("row %d kept by the entry", row).isTrue();
                boolean result = onIndex && (!needsText || f.test(e));
                assertThat(result).as("row %d", row).isEqualTo(expected);
                if (!needsText) assertThat(onIndex).as("row %d, exact", row).isEqualTo(expected);
                if (expected) passed++;
            }
            // 4 excludes every logger; 7 excludes "empty" on a field no entry has
            if (k == 4 || k == 7) assertThat(passed).as("filter %d keeps nothing", k).isZero();
            else assertThat(passed).as("filter %d keeps something", k).isPositive();
        }
    }

    @Test
    void onlyTextAndUnindexableFieldsNeedText() {
        EntryIndex index = new EntryIndex();
        List<LogEntry> entries = entries();
        for (int i = 0; i < entries.size(); i++) index.add(i, 1, entries.get(i));
        Set<LogLevel> all = EnumSet.allOf(LogLevel.class);

        assertThat(filter(EnumSet.of(LogLevel.INFO), Set.of("x"), false, Map.of("app", Set.of("web")),
                T0, null, " ").needsText(index)).isFalse();
        assertThat(filter(all, Set.of(), false, Map.of(), null, null, "timeout").needsText(index)).isTrue();
        assertThat(filter(all, Set.of(), false, Map.of("req", Set.of("r1")), null, null, null).needsText(index)).isTrue();
        assertThat(filter(all, Set.of(), false, Map.of("req", Set.of()), null, null, null).needsText(index)).isFalse();
    }

    @Test
    void entriesWithoutALevelLoggerOrTimeAlwaysPass() {
        LogEntry bare = new LogEntry(null, null, null, null, "m", "m", Map.of());
        CombinedFilter f = filter(EnumSet.of(LogLevel.ERROR), Set.of("a"), false, Map.of(),
                T0, T0.plusSeconds(1), null);
        EntryIndex index = new EntryIndex();
        index.add(0, 1, bare);

        assertThat(f.test(bare)).isTrue();
        assertThat(f.testIndex(index, 0)).isTrue();
        assertThat(index.level(0)).isNull();
    }
}
