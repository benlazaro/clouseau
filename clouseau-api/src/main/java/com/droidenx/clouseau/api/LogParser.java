package com.droidenx.clouseau.api;

import org.pf4j.ExtensionPoint;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Implement this to support a new log format.
 * Annotate your implementation with @Extension and drop the JAR in the plugins folder.
 *
 * <p>One instance serves every tab, and Clouseau calls it from several background threads
 * at once (loading, following, merging), never the Swing event thread. Keep it stateless,
 * or make it thread-safe.
 *
 * <p>Clouseau calls {@link #canParse} and, if it says yes, {@link #parse(String, ZoneId)}.
 * If either throws, the line is treated as not parsed; so is a {@code null} entry.
 */
public interface LogParser extends ExtensionPoint {

    /** Human-readable name shown in the UI (e.g. "Log4j Pattern"). */
    String getName();

    /** Returns true if this parser can handle the given raw line. */
    boolean canParse(String rawLine);

    /**
     * Parses a raw line into a {@link LogEntry}, reading a timestamp that has no offset or
     * zone of its own as wall time in the system's timezone.
     *
     * <p>Implement this one. Clouseau calls {@link #parse(String, ZoneId)}, whose default
     * calls this and moves the time to the timezone the log was written in.
     */
    LogEntry parse(String rawLine);

    /**
     * Parses a line of a log written in {@code writtenIn}, the timezone the user set for it
     * (the system's timezone unless they changed it). A timestamp with no offset or zone of
     * its own is wall time in {@code writtenIn}; one that has an offset or zone ({@code Z},
     * {@code +02:00}, {@code Europe/Paris}) keeps it, whatever {@code writtenIn} is.
     *
     * <p>The default calls {@link #parse(String)} and re-reads its timestamp's system-zone
     * wall time in {@code writtenIn}. That's right for a format whose timestamps never carry
     * an offset. If yours can, override this method and parse with {@code writtenIn}
     * directly (for example {@code DateTimeFormatter.withZone(writtenIn)}: an offset in the
     * text wins over it), or the default will move those times.
     *
     * @param writtenIn the log's timezone; never null
     * @since 0.3.0
     */
    default LogEntry parse(String rawLine, ZoneId writtenIn) {
        LogEntry entry = parse(rawLine);
        ZoneId system = ZoneId.systemDefault();
        if (entry == null || entry.timestamp() == null || writtenIn == null || writtenIn.equals(system)) return entry;
        LocalDateTime wallTime = LocalDateTime.ofInstant(entry.timestamp(), system);
        return entry.withTimestamp(wallTime.atZone(writtenIn).toInstant());
    }

    /**
     * Returns the names of custom fields this parser captures beyond the standard set
     * (timestamp, level, logger, thread, message). These are offered as extra table columns.
     * The default implementation returns an empty list.
     */
    default List<String> customFields() { return List.of(); }
}
