package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns one line into a {@link LogEntry}: with the parser the user chose, or else the
 * first parser that accepts the line (auto-detection is per line, so mixed files parse).
 * Lines nothing accepts become {@code UNKNOWN} entries, which is how continuation lines
 * such as stack traces are kept. Optionally re-reads timestamps in the log's own timezone.
 * <p>
 * Stateless after construction; safe to use from any one thread at a time.
 */
public final class LineParser {

    private final List<LogParser> candidates;
    private final ZoneId sourceZone;

    /**
     * @param chosen     the parser the user picked, or empty to auto-detect
     * @param all        every available parser, in detection order
     * @param sourceZone the timezone the log was written in, or null to leave timestamps
     *                   as the parser produced them (in the system zone)
     */
    public LineParser(Optional<LogParser> chosen, List<LogParser> all, ZoneId sourceZone) {
        this.candidates = chosen.map(List::of).orElseGet(() -> List.copyOf(all));
        this.sourceZone = sourceZone;
    }

    /** Never null. */
    public LogEntry parse(String line) {
        for (LogParser p : candidates) {
            if (p.canParse(line)) return inZone(p.parse(line), sourceZone);
        }
        return new LogEntry(null, LogEntry.LogLevel.UNKNOWN, null, null, line, line, Map.of());
    }

    /**
     * Parsers read timestamps as system-zone wall time. If the log was written in another
     * zone, re-read the same wall time in that zone.
     */
    static LogEntry inZone(LogEntry e, ZoneId zone) {
        if (zone == null || e.timestamp() == null || zone.equals(ZoneId.systemDefault())) return e;
        LocalDateTime wallTime = e.timestamp().atZone(ZoneId.systemDefault()).toLocalDateTime();
        return new LogEntry(wallTime.atZone(zone).toInstant(),
                e.level(), e.logger(), e.thread(), e.message(), e.rawLine(), e.fields());
    }
}
