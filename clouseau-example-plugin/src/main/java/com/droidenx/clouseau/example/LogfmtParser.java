package com.droidenx.clouseau.example;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import org.pf4j.Extension;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses <a href="https://brandur.org/logfmt">logfmt</a> lines, as written by Go's
 * {@code log/slog}, Heroku, Grafana and others:
 *
 * <pre>time=2026-03-27T08:00:00Z level=info msg="order created" order=ORD-42 user="ann lee"</pre>
 *
 * <p>Values may be bare or double-quoted, with {@code \"} and {@code \\} escapes. That
 * quoting is awkward to express as a regex, which is why this is a plugin rather than a
 * {@code parsers.json} entry.
 *
 * <p>{@code time}/{@code ts}, {@code level}/{@code lvl}, {@code msg}/{@code message},
 * {@code logger} and {@code thread} map to the standard columns; every other key becomes
 * a custom field, which Clouseau turns into a table column.
 *
 * <p>The {@link Extension} annotation is all PF4J needs: the annotation processor lists
 * this class in {@code META-INF/extensions.idx}.
 */
@Extension
public class LogfmtParser implements LogParser {

    @Override
    public String getName() {
        return "logfmt (example plugin)";
    }

    /**
     * Cheap check, called for every line during auto-detection: a {@code msg=} key and a
     * {@code level=} or {@code lvl=} key, each at the start of the line or after a space.
     */
    @Override
    public boolean canParse(String rawLine) {
        return hasKey(rawLine, "msg=") && (hasKey(rawLine, "level=") || hasKey(rawLine, "lvl="));
    }

    private static boolean hasKey(String line, String keyEq) {
        return line.startsWith(keyEq) || line.contains(" " + keyEq);
    }

    @Override
    public LogEntry parse(String rawLine) {
        Map<String, String> pairs = tokenize(rawLine);

        String time = take(pairs, "time", "ts");
        Instant timestamp = parseTime(time);
        if (timestamp == null && time != null) pairs.put("time", time); // keep an unreadable time visible

        LogEntry.LogLevel level = parseLevel(take(pairs, "level", "lvl"));
        String message = take(pairs, "msg", "message");
        String logger  = pairs.remove("logger");
        String thread  = pairs.remove("thread");

        return new LogEntry(timestamp, level, logger, thread,
                message != null ? message : rawLine, rawLine, Map.copyOf(pairs));
    }

    /** Removes all of {@code keys} from {@code pairs} and returns the first one's value, or null. */
    private static String take(Map<String, String> pairs, String... keys) {
        String found = null;
        for (String key : keys) {
            String value = pairs.remove(key);
            if (found == null) found = value;
        }
        return found;
    }

    /**
     * Splits a line into key/value pairs, in order. A key without {@code =} gets an empty
     * value; a quoted value runs to the closing quote (or the end of the line).
     */
    static Map<String, String> tokenize(String line) {
        Map<String, String> pairs = new LinkedHashMap<>();
        int i = 0, n = line.length();
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') i++;
            int keyStart = i;
            while (i < n && line.charAt(i) != '=' && line.charAt(i) != ' ') i++;
            String key = line.substring(keyStart, i);
            if (key.isEmpty()) { i++; continue; }

            String value = "";
            if (i < n && line.charAt(i) == '=') {
                i++;
                if (i < n && line.charAt(i) == '"') {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < n && line.charAt(i) != '"') {
                        char c = line.charAt(i);
                        if (c == '\\' && i + 1 < n) {
                            char next = line.charAt(++i);
                            sb.append(switch (next) {
                                case 'n' -> '\n';
                                case 't' -> '\t';
                                default  -> next;   // \" \\ and anything else: the char itself
                            });
                        } else {
                            sb.append(c);
                        }
                        i++;
                    }
                    i++; // closing quote
                    value = sb.toString();
                } else {
                    int valueStart = i;
                    while (i < n && line.charAt(i) != ' ') i++;
                    value = line.substring(valueStart, i);
                }
            }
            pairs.putIfAbsent(key, value);
        }
        return pairs;
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Instant.parse(value);   // ISO-8601, with Z or an offset
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LogEntry.LogLevel parseLevel(String value) {
        if (value == null) return LogEntry.LogLevel.INFO;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "trace"                            -> LogEntry.LogLevel.TRACE;
            case "debug", "dbug"                    -> LogEntry.LogLevel.DEBUG;
            case "info"                             -> LogEntry.LogLevel.INFO;
            case "warn", "warning"                  -> LogEntry.LogLevel.WARN;
            case "error", "err", "eror"             -> LogEntry.LogLevel.ERROR;
            case "fatal", "crit", "critical", "panic" -> LogEntry.LogLevel.FATAL;
            default                                 -> LogEntry.LogLevel.UNKNOWN;
        };
    }
}
