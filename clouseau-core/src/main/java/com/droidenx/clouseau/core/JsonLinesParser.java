package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.api.LogParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in parser for JSON lines: one JSON object per line, as written by Logback's and
 * Log4j's JSON layouts, ECS, Serilog's compact format, pino, bunyan, zap, structlog,
 * Python's JSON formatters and Docker's {@code json-file} driver.
 *
 * <p>The usual names of the standard columns are recognized whatever their case
 * ({@code @timestamp}, {@code time}, {@code ts}; {@code level}, {@code severity},
 * {@code @l}; {@code message}, {@code msg}, {@code log}…), at the top level or, ECS style,
 * nested ({@code "log": {"level": …}}) or dotted ({@code "log.level"}). Every other
 * top-level key becomes a field, nested values as compact JSON. A stack trace in its own
 * key ({@code stack_trace}, {@code exception}…) is added to the message, so the detail pane
 * shows it and links its frames.
 *
 * <p>A line is accepted when it is a JSON object with a timestamp, level or message under
 * one of those names. A record without a level is INFO: it is a whole entry, not a
 * continuation line, which is what an UNKNOWN level means (4.2). Stateless and thread-safe.
 */
public final class JsonLinesParser implements LogParser {

    public static final String NAME = "JSON Lines";

    private static final List<String> TIME_KEYS = List.of(
            "@timestamp", "timestamp", "time", "ts", "@t", "datetime", "date", "asctime", "eventtime");
    private static final List<String> LEVEL_KEYS = List.of(
            "level", "log.level", "severity", "@l", "loglevel", "lvl", "levelname", "level_name", "severity_text");
    private static final List<String> MESSAGE_KEYS = List.of(
            "message", "msg", "@m", "@mt", "log", "text", "body");
    private static final List<String> LOGGER_KEYS = List.of(
            "logger", "log.logger", "logger_name", "loggername", "category", "sourcecontext", "caller", "name");
    private static final List<String> THREAD_KEYS = List.of(
            "thread", "thread_name", "threadname", "process.thread.name");
    private static final List<String> STACK_KEYS = List.of(
            "stack_trace", "stacktrace", "error.stack_trace", "exception", "@x", "exc_info", "stack", "err.stack");

    /** ISO-8601 with or without an offset, and with a space for the {@code T}, as Python's asctime writes. */
    private static final DateTimeFormatter LOCAL = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("uuuu-MM-dd")
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .appendPattern("HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .optionalStart().appendLiteral(',').appendValue(ChronoField.MILLI_OF_SECOND, 3).optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .toFormatter(Locale.ROOT);

    @Override
    public String getName() { return NAME; }

    @Override
    public boolean canParse(String rawLine) {
        JsonObject o = object(rawLine);
        if (o == null) return false;
        Map<String, JsonElement> flat = flatten(o);
        return find(flat, TIME_KEYS) != null || find(flat, LEVEL_KEYS) != null || find(flat, MESSAGE_KEYS) != null;
    }

    @Override
    public LogEntry parse(String rawLine) {
        return parse(rawLine, ZoneId.systemDefault());
    }

    /** Reads a time without an offset in {@code writtenIn}; one with an offset or as epoch time keeps it. */
    @Override
    public LogEntry parse(String rawLine, ZoneId writtenIn) {
        JsonObject o = object(rawLine);
        if (o == null) return null;
        Map<String, JsonElement> flat = flatten(o);

        Hit time = find(flat, TIME_KEYS);
        Hit level = find(flat, LEVEL_KEYS);
        Hit message = find(flat, MESSAGE_KEYS);
        Hit logger = find(flat, LOGGER_KEYS);
        Hit thread = find(flat, THREAD_KEYS);
        Hit stack = find(flat, STACK_KEYS);
        Set<String> used = new java.util.HashSet<>();
        for (Hit h : new Hit[] {time, level, message, logger, thread, stack}) if (h != null) used.add(h.key());

        String text = message != null ? text(message.value()) : rawLine.strip();
        if (message != null && "log".equals(message.key())) text = stripNewline(text);   // Docker's json-file
        if (stack != null) {
            String trace = text(stack.value());
            if (!trace.isBlank() && !text.contains(trace)) text = text + "\n" + trace;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String key = e.getKey();
            if (used.contains(key)) continue;
            JsonElement v = e.getValue();
            if (v.isJsonObject()) {
                // ECS: keep what's left of an object a standard column came from.
                JsonObject rest = v.getAsJsonObject().deepCopy();
                for (String u : used) if (u.startsWith(key + ".")) rest.remove(u.substring(key.length() + 1));
                if (rest.isEmpty()) continue;
                v = rest;
            }
            if (v.isJsonNull()) continue;
            fields.put(key, text(v));
        }

        return LogEntry.builder(rawLine)
                .timestamp(time != null ? timestamp(time.value(), writtenIn) : null)
                .level(level != null ? level(level.value()) : LogLevel.INFO)
                .logger(logger != null ? text(logger.value()) : "")
                .thread(thread != null ? text(thread.value()) : "")
                .message(text)
                .fields(fields)
                .build();
    }

    // ── Reading the object ───────────────────────────────────────────────────

    private record Hit(String key, JsonElement value) {}

    /** The line as a JSON object, or null if it isn't one. */
    private static JsonObject object(String line) {
        if (line == null) return null;
        String s = line.strip();
        if (s.length() < 2 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') return null;
        try {
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException | StackOverflowError e) {
            return null;
        }
    }

    /** Top-level keys, plus each key of a nested object as {@code parent.child} (one level). */
    private static Map<String, JsonElement> flatten(JsonObject o) {
        Map<String, JsonElement> flat = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            flat.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> c : e.getValue().getAsJsonObject().entrySet()) {
                flat.putIfAbsent(e.getKey() + "." + c.getKey(), c.getValue());
            }
        }
        return flat;
    }

    /**
     * The first of {@code names} present with a usable value (a string, number or boolean;
     * a stack trace may also be an object or array), ignoring case.
     */
    private static Hit find(Map<String, JsonElement> flat, List<String> names) {
        for (String name : names) {
            for (Map.Entry<String, JsonElement> e : flat.entrySet()) {
                if (!matches(e.getKey(), name)) continue;
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() || (STACK_KEYS.contains(name) && !v.isJsonNull())) {
                    return new Hit(e.getKey(), v);
                }
            }
        }
        return null;
    }

    /**
     * Whether {@code key} is {@code name}, ignoring case, except for {@code name} itself
     * (Python's logger), which must be lower case: {@code Name} is an ordinary property in
     * Serilog and the like.
     */
    private static boolean matches(String key, String name) {
        return "name".equals(name) ? key.equals(name) : key.equalsIgnoreCase(name);
    }

    /** A value as text: a string as it is, anything else as compact JSON. */
    private static String text(JsonElement v) {
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) return v.getAsString();
        return v.toString();
    }

    private static String stripNewline(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    // ── Values ───────────────────────────────────────────────────────────────

    /**
     * An ISO-8601 time (an offset in the text wins; none means {@code zone}), or epoch time
     * as a number or numeric string: seconds (with a fraction, as zap and structlog write),
     * milliseconds (pino, bunyan), microseconds or nanoseconds, told apart by size.
     */
    static Instant timestamp(JsonElement v, ZoneId zone) {
        if (!v.isJsonPrimitive()) return null;
        JsonPrimitive p = v.getAsJsonPrimitive();
        String s = p.getAsString().strip();
        if (s.isEmpty()) return null;
        if (p.isNumber() || s.matches("\\d{9,}(\\.\\d+)?")) return epoch(s);
        try {
            var parsed = LOCAL.parseBest(s, OffsetDateTime::from, LocalDateTime::from);
            if (parsed instanceof OffsetDateTime odt) return odt.toInstant();
            return ((LocalDateTime) parsed).atZone(zone).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Instant epoch(String s) {
        try {
            BigDecimal n = new BigDecimal(s);
            BigDecimal abs = n.abs();
            BigDecimal nanos;
            if (abs.compareTo(BigDecimal.valueOf(1e17)) >= 0) nanos = n;
            else if (abs.compareTo(BigDecimal.valueOf(1e14)) >= 0) nanos = n.movePointRight(3);
            else if (abs.compareTo(BigDecimal.valueOf(1e11)) >= 0) nanos = n.movePointRight(6);
            else nanos = n.movePointRight(9);
            BigDecimal[] parts = nanos.divideAndRemainder(BigDecimal.valueOf(1_000_000_000L));
            return Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValue());
        } catch (ArithmeticException | NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    /**
     * A level by name ({@code warning}, {@code err}, {@code critical} and Serilog's
     * {@code Verbose}/{@code Information} included), or a pino/bunyan number (10 trace …
     * 60 fatal). Anything else is INFO, since the line is still a whole record.
     */
    static LogLevel level(JsonElement v) {
        if (!v.isJsonPrimitive()) return LogLevel.INFO;
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isNumber()) {
            int n = p.getAsInt();
            if (n >= 60) return LogLevel.FATAL;
            if (n >= 50) return LogLevel.ERROR;
            if (n >= 40) return LogLevel.WARN;
            if (n >= 30) return LogLevel.INFO;
            if (n >= 20) return LogLevel.DEBUG;
            return LogLevel.TRACE;
        }
        return switch (p.getAsString().strip().toLowerCase(Locale.ROOT)) {
            case "trace", "verbose", "finest", "finer" -> LogLevel.TRACE;
            case "debug", "dbg", "fine", "config" -> LogLevel.DEBUG;
            case "warn", "warning", "wrn" -> LogLevel.WARN;
            case "error", "err", "eror", "severe" -> LogLevel.ERROR;
            case "fatal", "critical", "crit", "panic", "dpanic", "emerg", "emergency", "alert", "ftl" -> LogLevel.FATAL;
            default -> LogLevel.INFO;
        };
    }
}
