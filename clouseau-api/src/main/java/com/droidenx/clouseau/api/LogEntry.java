package com.droidenx.clouseau.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One parsed log line: when, how severe, from where, what it says, and any other fields
 * the format has. Immutable.
 *
 * <p>Build one with {@link #builder(String)}, setting only what your format has:
 * <pre>{@code
 * LogEntry.builder(rawLine)
 *         .timestamp(time)
 *         .level(LogEntry.LogLevel.WARN)
 *         .logger("com.acme.Billing")
 *         .message(text)
 *         .field("traceId", traceId)
 *         .build();
 * }</pre>
 * The builder is how entries grow: a value added in a later API version gets a builder
 * method and a default, so a plugin that doesn't set it keeps working. The seven-value
 * constructor stays as it is, for plugins built against API 0.2, but never gains values.
 *
 * <p>Any value but {@link #rawLine()} may be missing: {@code null} for the strings and the
 * timestamp, {@link LogLevel#UNKNOWN} for the level, an empty map for the fields.
 */
public final class LogEntry {

    /** How severe an entry is; {@link #UNKNOWN} when the line doesn't say, or isn't an entry of its own. */
    public enum LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN }

    private final Instant timestamp;
    private final LogLevel level;
    private final String logger;
    private final String thread;
    private final String message;
    private final String rawLine;
    private final Map<String, String> fields;

    /**
     * An entry with these values; {@code fields} is used as given. Prefer
     * {@link #builder(String)}: this constructor is kept for plugins built against API 0.2
     * and won't take values added later.
     */
    public LogEntry(Instant timestamp, LogLevel level, String logger, String thread,
                    String message, String rawLine, Map<String, String> fields) {
        this.timestamp = timestamp;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.message = message;
        this.rawLine = rawLine;
        this.fields = fields;
    }

    /** A builder for the entry of {@code rawLine}: the line as it is in the log, without its line terminator. */
    public static Builder builder(String rawLine) {
        return new Builder(rawLine);
    }

    /** A builder that starts with this entry's values, to make a changed copy. */
    public Builder toBuilder() {
        Builder b = new Builder(rawLine);
        b.timestamp = timestamp;
        b.level = level;
        b.logger = logger;
        b.thread = thread;
        b.message = message;
        b.fields = fields;
        return b;
    }

    /** This entry with another timestamp. */
    public LogEntry withTimestamp(Instant timestamp) {
        return toBuilder().timestamp(timestamp).build();
    }

    /** When it was logged, or null if the line has no time. */
    public Instant timestamp() { return timestamp; }

    /** How severe it is; {@link LogLevel#UNKNOWN} if the line doesn't say. */
    public LogLevel level() { return level; }

    /** The logger or category, often a class name; may be null or empty. */
    public String logger() { return logger; }

    /** The thread that logged it; may be null or empty. */
    public String thread() { return thread; }

    /** What it says. */
    public String message() { return message; }

    /** The whole line as it is in the log, without its line terminator. */
    public String rawLine() { return rawLine; }

    /** The format's other values (a trace id, a request path), by name; shown as extra columns. */
    public Map<String, String> fields() { return fields; }

    @Override
    public boolean equals(Object o) {
        return o instanceof LogEntry e
                && Objects.equals(timestamp, e.timestamp) && level == e.level
                && Objects.equals(logger, e.logger) && Objects.equals(thread, e.thread)
                && Objects.equals(message, e.message) && Objects.equals(rawLine, e.rawLine)
                && Objects.equals(fields, e.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, level, logger, thread, message, rawLine, fields);
    }

    @Override
    public String toString() {
        return "LogEntry[timestamp=" + timestamp + ", level=" + level + ", logger=" + logger
                + ", thread=" + thread + ", message=" + message + ", rawLine=" + rawLine
                + ", fields=" + fields + "]";
    }

    /**
     * Builds a {@link LogEntry}. Starts with no timestamp, level {@link LogLevel#UNKNOWN},
     * no logger or thread, the raw line as the message, and no fields.
     */
    public static final class Builder {
        private final String rawLine;
        private Instant timestamp;
        private LogLevel level = LogLevel.UNKNOWN;
        private String logger;
        private String thread;
        private String message;
        private Map<String, String> fields = Map.of();

        private Builder(String rawLine) {
            this.rawLine = Objects.requireNonNull(rawLine, "rawLine");
            this.message = rawLine;
        }

        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }

        /** The level; null means {@link LogLevel#UNKNOWN}. */
        public Builder level(LogLevel level) { this.level = level != null ? level : LogLevel.UNKNOWN; return this; }

        public Builder logger(String logger) { this.logger = logger; return this; }

        public Builder thread(String thread) { this.thread = thread; return this; }

        /** What the entry says; null keeps the raw line as the message. */
        public Builder message(String message) { this.message = message != null ? message : rawLine; return this; }

        /** Replaces the fields with a copy of {@code fields} (null means none; null values are left out). */
        public Builder fields(Map<String, String> fields) {
            this.fields = fields == null ? Map.of() : fields;
            return this;
        }

        /** Adds one field; a null value is left out. */
        public Builder field(String name, String value) {
            if (value == null) return this;
            Map<String, String> copy = new LinkedHashMap<>(fields);
            copy.put(Objects.requireNonNull(name, "name"), value);
            this.fields = copy;
            return this;
        }

        public LogEntry build() {
            Map<String, String> kept = new LinkedHashMap<>();
            fields.forEach((k, v) -> { if (k != null && v != null) kept.put(k, v); });
            return new LogEntry(timestamp, level, logger, thread, message, rawLine,
                    kept.isEmpty() ? Map.of() : Map.copyOf(kept));
        }
    }
}
