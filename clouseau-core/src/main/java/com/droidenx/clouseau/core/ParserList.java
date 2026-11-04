package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the parser list the UI offers and auto-detects with: JSON-defined parsers
 * (built-in and user) first, then parsers contributed by plugins.
 *
 * <p>Auto-detection uses the first parser whose {@code canParse} accepts a line, so
 * putting plugins last means installing one never takes over a format that already
 * works. A plugin parser whose name is already taken is skipped. Plugin parsers are
 * wrapped so that a bug in one can't abort a file load (see {@link #guard}).
 */
@Slf4j
public final class ParserList {

    private ParserList() {}

    /** JSON-defined parsers in their order, then each uniquely named plugin parser, guarded. */
    public static List<LogParser> combine(List<LogParser> jsonParsers, List<LogParser> pluginParsers) {
        List<LogParser> result = new ArrayList<>(jsonParsers);
        Set<String> names = new HashSet<>();
        jsonParsers.forEach(p -> names.add(p.getName()));
        for (LogParser p : pluginParsers) {
            String name;
            try {
                name = p.getName();
            } catch (RuntimeException e) {
                log.warn("Skipping plugin parser {}: getName() failed", p.getClass().getName(), e);
                continue;
            }
            if (name == null || name.isBlank()) {
                log.warn("Skipping plugin parser {}: it has no name", p.getClass().getName());
            } else if (!names.add(name)) {
                log.warn("Skipping plugin parser '{}' ({}): a parser with that name already exists",
                        name, p.getClass().getName());
            } else {
                result.add(guard(p, name));
            }
        }
        return result;
    }

    /**
     * Wraps a plugin parser so its exceptions don't escape: a failing {@code canParse}
     * counts as "no", and a failing {@code parse} turns the line into an UNKNOWN entry.
     * The first failure of each kind is logged with its stack trace.
     */
    static LogParser guard(LogParser parser, String name) {
        return new Guarded(parser, name);
    }

    private static final class Guarded implements LogParser {
        private final LogParser     delegate;
        private final String        name;
        private final AtomicBoolean canParseFailed = new AtomicBoolean();
        private final AtomicBoolean parseFailed    = new AtomicBoolean();

        Guarded(LogParser delegate, String name) {
            this.delegate = delegate;
            this.name     = name;
        }

        @Override public String getName() { return name; }

        @Override
        public boolean canParse(String rawLine) {
            try {
                return delegate.canParse(rawLine);
            } catch (RuntimeException e) {
                if (canParseFailed.compareAndSet(false, true))
                    log.warn("Plugin parser '{}' failed in canParse; treating as no match", name, e);
                return false;
            }
        }

        @Override
        public LogEntry parse(String rawLine) {
            try {
                LogEntry entry = delegate.parse(rawLine);
                if (entry != null) return entry;
                throw new IllegalStateException("parse returned null");
            } catch (RuntimeException e) {
                if (parseFailed.compareAndSet(false, true))
                    log.warn("Plugin parser '{}' failed in parse; showing the line as UNKNOWN", name, e);
                return new LogEntry(null, LogEntry.LogLevel.UNKNOWN, null, null, rawLine, rawLine, Map.of());
            }
        }

        @Override
        public List<String> customFields() {
            try {
                List<String> fields = delegate.customFields();
                return fields != null ? fields : List.of();
            } catch (RuntimeException e) {
                log.warn("Plugin parser '{}' failed in customFields", name, e);
                return List.of();
            }
        }

        @Override public String toString() { return name; }
    }
}
