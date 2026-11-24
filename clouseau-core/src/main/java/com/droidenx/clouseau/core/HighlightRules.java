package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The enabled auto-highlight rules, ready to match (UC-3, D-29). Immutable and safe to share
 * between threads.
 * <p>
 * An entry gets the color of the first rule that matches it. A continuation line under it (a
 * stack trace line) is matched as text, looking in Any or Message, against the level of the
 * entry it belongs to: the first rule it matches colors the line, and the entry too if no
 * rule colored the entry itself, so the entry is found in a table that hides its trace.
 * {@link Scan} applies this to entries in order.
 */
public final class HighlightRules {

    /** No rules: matches nothing. */
    public static final HighlightRules NONE = new HighlightRules(List.of());

    private final List<Compiled> rules;

    private HighlightRules(List<Compiled> rules) {
        this.rules = rules;
    }

    /** The enabled, complete rules of {@code all}, in order; the others are left out. */
    public static HighlightRules of(List<HighlightRule> all) {
        List<Compiled> compiled = new ArrayList<>();
        for (HighlightRule r : all) {
            if (r != null && r.enabled() && r.isComplete()) compiled.add(new Compiled(r));
        }
        return compiled.isEmpty() ? NONE : new HighlightRules(List.copyOf(compiled));
    }

    /** True if no rule applies, so nothing ever matches. */
    public boolean isEmpty() { return rules.isEmpty(); }

    /** The palette slot of the first rule {@code entry} matches by itself, or -1. */
    public int slotOf(LogEntry entry) {
        for (Compiled r : rules) {
            if (r.matches(entry)) return r.rule.color();
        }
        return -1;
    }

    /** The slot a continuation {@code line} gives the {@code head} entry it belongs to, or -1. */
    int continuationSlot(LogEntry head, LogEntry line) {
        for (Compiled r : rules) {
            if (r.matchesContinuation(head, line)) return r.rule.color();
        }
        return -1;
    }

    /** True if {@code e} continues the entry before it (a line no parser accepted). */
    public static boolean isContinuation(LogEntry e) {
        return e.timestamp() == null || e.level() == LogLevel.UNKNOWN;
    }

    /** Receives a matched entry: its ordinal and palette slot. */
    @FunctionalInterface
    public interface Hit {
        void hit(int ordinal, int slot);
    }

    /** Starts matching entries in ordinal order. */
    public Scan scan() { return new Scan(this); }

    /**
     * Matches entries handed in ordinal order, remembering the entry whose continuation lines
     * are still coming. Within one run, hits come out in ascending ordinal order, each ordinal
     * at most once: an entry when it's read or at the first continuation line that marks it
     * (just before that line). Not thread-safe; one thread at a time.
     */
    public static final class Scan {
        private final HighlightRules rules;
        private int headOrdinal = -1;
        private LogEntry head;
        private int headSlot = -1;

        private Scan(HighlightRules rules) { this.rules = rules; }

        /** The ordinals start again from elsewhere (another file of a merged view). */
        public void startRun() {
            headOrdinal = -1;
            head = null;
            headSlot = -1;
        }

        /** The next entry in order. */
        public void accept(int ordinal, LogEntry e, Hit out) {
            if (head == null || !isContinuation(e)) {
                headOrdinal = ordinal;
                head = e;
                headSlot = rules.slotOf(e);
                if (headSlot >= 0) out.hit(ordinal, headSlot);
            } else {
                int slot = rules.continuationSlot(head, e);
                if (slot < 0) return;
                if (headSlot < 0) {
                    headSlot = slot;
                    out.hit(headOrdinal, slot);
                }
                out.hit(ordinal, slot);
            }
        }
    }

    // ── Matching ──────────────────────────────────────────────────────────────

    private static final class Compiled {
        final HighlightRule rule;
        final Pattern pattern;
        /** The text lower-cased, for a case-insensitive plain match. */
        final String lower;

        Compiled(HighlightRule rule) {
            this.rule = rule;
            String text = rule.text();
            if (text.isEmpty()) {
                pattern = null;
                lower = null;
            } else if (rule.regex()) {
                pattern = Pattern.compile(text, rule.matchCase() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                lower = null;
            } else {
                pattern = null;
                lower = rule.matchCase() ? null : text.toLowerCase(java.util.Locale.ROOT);
            }
        }

        boolean levelOk(LogEntry e) {
            LogLevel min = rule.minLevel();
            if (min == null) return true;
            LogLevel l = e.level();
            return l != null && l != LogLevel.UNKNOWN && l.ordinal() >= min.ordinal();
        }

        boolean matches(LogEntry e) {
            if (!levelOk(e)) return false;
            if (rule.text().isEmpty()) return true;
            return switch (rule.column()) {
                case MESSAGE -> has(e.message());
                case LOGGER  -> has(e.logger());
                case THREAD  -> has(e.thread());
                case FIELD   -> e.fields() != null && has(e.fields().get(rule.field()));
                case ANY     -> has(e.message()) || has(e.logger()) || has(e.thread())
                                || (e.level() != null && has(e.level().name())) || anyField(e.fields());
            };
        }

        boolean matchesContinuation(LogEntry head, LogEntry line) {
            if (rule.text().isEmpty()) return false;   // a level-only rule judged the entry already
            if (rule.column() != HighlightRule.Column.ANY && rule.column() != HighlightRule.Column.MESSAGE) return false;
            return levelOk(head) && has(line.message());
        }

        private boolean anyField(Map<String, String> fields) {
            if (fields == null) return false;
            for (String v : fields.values()) if (has(v)) return true;
            return false;
        }

        private boolean has(String s) {
            if (s == null) return false;
            if (pattern != null) return pattern.matcher(s).find();
            if (lower == null) return s.contains(rule.text());
            return containsIgnoreCase(s, lower);
        }
    }

    /** {@code s} contains {@code lower} (already lower-cased), ignoring case, without copying {@code s}. */
    static boolean containsIgnoreCase(String s, String lower) {
        int n = lower.length();
        if (n == 0) return true;
        for (int i = 0, last = s.length() - n; i <= last; i++) {
            if (s.regionMatches(true, i, lower, 0, n)) return true;
        }
        return false;
    }
}
