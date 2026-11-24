package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry.LogLevel;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One auto-highlight rule (UC-3, D-29): entries whose {@code column} contains {@code text}
 * (or matches it as a regular expression), at {@code minLevel} or above, get the highlight
 * palette slot {@code color}. Empty text matches on the level alone; a rule with neither
 * text nor a level matches nothing. Rules are global: every tab applies every enabled one.
 *
 * @param enabled   off keeps the rule but doesn't apply it
 * @param column    where the text is looked for
 * @param field     the field's name when {@code column} is {@link Column#FIELD}, else null
 * @param text      what to look for; empty for any text
 * @param matchCase case-sensitive
 * @param regex     {@code text} is a regular expression, found anywhere in the column
 * @param minLevel  the lowest level that matches, or null for any (continuation lines included)
 * @param color     the highlight palette slot, 0 to 7
 */
public record HighlightRule(boolean enabled, Column column, String field, String text,
                            boolean matchCase, boolean regex, LogLevel minLevel, int color) {

    /** Where a rule looks for its text. */
    public enum Column { ANY, MESSAGE, LOGGER, THREAD, FIELD }

    public HighlightRule {
        if (column == null) column = Column.ANY;
        if (column != Column.FIELD) field = null;
        if (text == null) text = "";
        if (minLevel == LogLevel.UNKNOWN) minLevel = null;
    }

    /** True if the rule can match something: some text or a level, a valid pattern, a field's name. */
    public boolean isComplete() {
        if (text.isEmpty() && minLevel == null) return false;
        if (column == Column.FIELD && (field == null || field.isBlank())) return false;
        return patternError() == null;
    }

    /** The regular expression's syntax error, or null if it has none (or isn't a regex). */
    public String patternError() {
        if (!regex || text.isEmpty()) return null;
        try {
            Pattern.compile(text);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    /** This rule turned on or off. */
    public HighlightRule withEnabled(boolean on) {
        return new HighlightRule(on, column, field, text, matchCase, regex, minLevel, color);
    }
}
