package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The filter bar's filters, ANDed: levels, excluded loggers, excluded custom-field
 * values, a time range and message text. Everything but the text, and fields with too
 * many values to index, is decided from the index columns (D-16).
 * <p>
 * The time range is compared in whole milliseconds, which is what the index keeps and
 * what the table shows.
 */
public final class CombinedFilter implements EntryFilter {

    /** The field value that stands for "no value" (missing or blank). */
    public static final String EMPTY_VALUE = "(empty)";

    private final Set<LogLevel> levels;            // null = every level
    private final Set<String> excludedLoggers;     // empty = none
    private final boolean noLoggers;
    private final Map<String, Set<String>> excludedFieldValues;
    private final long fromMillis, toMillis;
    private final Pattern text;                    // null = none
    private final Set<Integer> excludedSources;    // empty = none

    /**
     * @param levels          the levels to show; entries with no level always show
     * @param excludedLoggers loggers to hide; entries with no logger always show
     * @param noLoggers       hide every entry (every logger is excluded)
     * @param excludedFieldValues per field, the values to hide ({@link #EMPTY_VALUE} for none)
     * @param from            earliest time to show, or null; entries without one always show
     * @param to              latest time to show, or null
     * @param text            message text, a case-insensitive regex if it compiles, else
     *                        literal; null or blank for none
     */
    public CombinedFilter(Set<LogLevel> levels, Set<String> excludedLoggers, boolean noLoggers,
                          Map<String, Set<String>> excludedFieldValues, Instant from, Instant to, String text) {
        this(levels, excludedLoggers, noLoggers, excludedFieldValues, from, to, text, Set.of());
    }

    /**
     * As above, also hiding whole sources of a merged view (D-17).
     *
     * @param excludedSources the sources to hide, by their index in the tab
     */
    public CombinedFilter(Set<LogLevel> levels, Set<String> excludedLoggers, boolean noLoggers,
                          Map<String, Set<String>> excludedFieldValues, Instant from, Instant to, String text,
                          Set<Integer> excludedSources) {
        this.excludedSources = Set.copyOf(excludedSources);
        this.levels = levels == null || levels.containsAll(EnumSet.allOf(LogLevel.class))
                ? null : Set.copyOf(levels);
        this.excludedLoggers = Set.copyOf(excludedLoggers);
        this.noLoggers = noLoggers;
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        excludedFieldValues.forEach((k, v) -> { if (!v.isEmpty()) fields.put(k, Set.copyOf(v)); });
        this.excludedFieldValues = fields;
        this.fromMillis = from != null ? from.toEpochMilli() : Long.MIN_VALUE;
        this.toMillis = to != null ? to.toEpochMilli() : Long.MAX_VALUE;
        this.text = compile(text);
    }

    private static Pattern compile(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        try {
            return Pattern.compile(t, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException ex) {
            return Pattern.compile(Pattern.quote(t), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
    }

    @Override
    public boolean test(LogEntry e) {
        if (noLoggers) return false;
        if (levels != null && e.level() != null && !levels.contains(e.level())) return false;
        if (e.logger() != null && excludedLoggers.contains(e.logger())) return false;
        if (e.timestamp() != null && !inRange(e.timestamp().toEpochMilli())) return false;
        for (Map.Entry<String, Set<String>> f : excludedFieldValues.entrySet()) {
            String v = e.fields() != null ? e.fields().get(f.getKey()) : null;
            if (excluded(f.getValue(), v)) return false;
        }
        return text == null || text.matcher(e.message() != null ? e.message() : "").find();
    }

    @Override
    public boolean testIndex(EntryIndex index, int row) {
        if (noLoggers) return false;
        if (levels != null) {
            LogLevel level = index.level(row);
            if (level != null && !levels.contains(level)) return false;
        }
        if (!excludedLoggers.isEmpty()) {
            String logger = index.loggers().valueOf(index.loggerId(row));
            if (logger != null && excludedLoggers.contains(logger)) return false;
        }
        long ts = index.timestamp(row);
        if (ts != EntryIndex.NO_TIMESTAMP && !inRange(ts)) return false;
        for (Map.Entry<String, Set<String>> f : excludedFieldValues.entrySet()) {
            String name = f.getKey();
            if (index.hasTooManyValues(name)) continue;       // decided from text
            EntryIndex.Dictionary values = index.fieldValues(name);
            String v = values != null ? values.valueOf(index.fieldId(name, row)) : null;
            if (excluded(f.getValue(), v)) return false;
        }
        return true;
    }

    @Override
    public boolean needsText(EntryIndex index) {
        if (text != null) return true;
        for (String name : excludedFieldValues.keySet()) {
            if (index.hasTooManyValues(name)) return true;
        }
        return false;
    }

    @Override
    public boolean testSource(int source) {
        return !excludedSources.contains(source);
    }

    /** True if this filter has message text, which can only be tested by reading entries. */
    public boolean hasText() { return text != null; }

    private boolean inRange(long millis) {
        return millis >= fromMillis && millis <= toMillis;
    }

    private static boolean excluded(Set<String> excluded, String value) {
        boolean empty = value == null || value.isBlank();
        return empty ? excluded.contains(EMPTY_VALUE) : excluded.contains(value);
    }
}
