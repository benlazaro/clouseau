package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.api.LogParser;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in parser for syslog messages as they travel over the network: RFC 5424
 * ({@code <34>1 2003-10-11T22:14:15.003Z host app 1234 ID47 - message}) and the older BSD
 * format of RFC 3164 ({@code <34>Oct 11 22:14:15 host app[1234]: message}), including the
 * common variant with an ISO time in place of the BSD one.
 *
 * <p>The level comes from the priority's severity (emergency, alert and critical are
 * FATAL; notice is INFO), the logger is the app name or tag, the thread its process ID;
 * the host and facility are fields, and so are an RFC 5424 message ID and structured data
 * when present. A BSD time has no year: it is the current year in the log's timezone, or
 * the year before if that would put it more than a day in the future.
 *
 * <p>Only lines that start with a priority ({@code <0>} to {@code <191>}) are accepted, so
 * this never takes over another format. Stateless and thread-safe.
 */
public final class SyslogParser implements LogParser {

    public static final String NAME = "Syslog";

    public static final String HOST = "host";
    public static final String FACILITY = "facility";
    public static final String MSGID = "msgid";
    public static final String STRUCTURED_DATA = "structured-data";

    private static final Pattern PRI = Pattern.compile("^<(\\d{1,3})>");

    /** RFC 5424 after the priority: version, time, host, app, procid, msgid; then SD and the message. */
    private static final Pattern RFC5424 = Pattern.compile(
            "^(\\d{1,2}) (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) ?(.*)$", Pattern.DOTALL);

    /** RFC 3164 after the priority: "Mmm dd hh:mm:ss" (day space-padded) or an ISO time, then the rest. */
    private static final Pattern BSD = Pattern.compile(
            "^(?:([A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2})|(\\d{4}-\\d{2}-\\d{2}T\\S+)) (.*)$", Pattern.DOTALL);

    /** "app[pid]: message" or "app: message"; the tag is at most 48 characters of no space, colon or bracket. */
    private static final Pattern TAG = Pattern.compile("^([^\\s:\\[\\]]{1,48})(?:\\[([^\\]]*)\\])?: ?(.*)$", Pattern.DOTALL);

    private static final DateTimeFormatter BSD_TIME = new DateTimeFormatterBuilder()
            .appendPattern("MMM ppd HH:mm:ss").toFormatter(Locale.ENGLISH);

    private static final DateTimeFormatter ISO = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final String[] FACILITIES = {
            "kern", "user", "mail", "daemon", "auth", "syslog", "lpr", "news", "uucp", "cron",
            "authpriv", "ftp", "ntp", "security", "console", "solaris-cron",
            "local0", "local1", "local2", "local3", "local4", "local5", "local6", "local7"};

    @Override
    public String getName() { return NAME; }

    @Override
    public List<String> customFields() { return List.of(HOST, FACILITY); }

    @Override
    public boolean canParse(String rawLine) {
        return priority(rawLine) >= 0;
    }

    @Override
    public LogEntry parse(String rawLine) {
        return parse(rawLine, ZoneId.systemDefault());
    }

    /** Reads an RFC 5424 time with its offset; a BSD time, or an ISO time without one, in {@code writtenIn}. */
    @Override
    public LogEntry parse(String rawLine, ZoneId writtenIn) {
        int pri = priority(rawLine);
        if (pri < 0) return null;
        String rest = rawLine.substring(rawLine.indexOf('>') + 1);
        Map<String, String> fields = new LinkedHashMap<>();
        LogEntry.Builder b = LogEntry.builder(rawLine).level(level(pri % 8));

        Matcher m = RFC5424.matcher(rest);
        if (m.matches() && !"0".equals(m.group(1))) {
            b.timestamp("-".equals(m.group(2)) ? null : iso(m.group(2), writtenIn));
            putUnlessNil(fields, HOST, m.group(3));
            fields.put(FACILITY, facility(pri));
            b.logger(nil(m.group(4))).thread(nil(m.group(5)));
            putUnlessNil(fields, MSGID, m.group(6));
            String sdAndMessage = m.group(7);
            int sdEnd = structuredDataEnd(sdAndMessage);
            if (sdEnd > 0) {
                String sd = sdAndMessage.substring(0, sdEnd);
                if (!"-".equals(sd)) fields.put(STRUCTURED_DATA, sd);
                sdAndMessage = sdAndMessage.substring(sdEnd);
                if (sdAndMessage.startsWith(" ")) sdAndMessage = sdAndMessage.substring(1);
            }
            return b.message(stripBom(sdAndMessage)).fields(fields).build();
        }

        m = BSD.matcher(rest);
        String body = rest;
        if (m.matches()) {
            b.timestamp(m.group(1) != null ? bsd(m.group(1), writtenIn) : iso(m.group(2), writtenIn));
            body = m.group(3);
            // A host comes before the tag, unless the first word already is the tag ("app[1]:" or "app:").
            int space = body.indexOf(' ');
            if (space > 0 && !TAG.matcher(body).matches()) {
                fields.put(HOST, body.substring(0, space));
                body = body.substring(space + 1);
            }
        }
        fields.put(FACILITY, facility(pri));
        Matcher tag = TAG.matcher(body);
        if (tag.matches()) {
            b.logger(tag.group(1)).thread(tag.group(2));
            body = tag.group(3);
        }
        return b.message(body).fields(fields).build();
    }

    /** The line's priority, or -1 if it doesn't start with a valid one. */
    static int priority(String line) {
        if (line == null) return -1;
        Matcher m = PRI.matcher(line);
        if (!m.find()) return -1;
        String digits = m.group(1);
        if (digits.length() > 1 && digits.charAt(0) == '0') return -1;
        int pri = Integer.parseInt(digits);
        return pri <= 191 ? pri : -1;
    }

    static LogLevel level(int severity) {
        return switch (severity) {
            case 0, 1, 2 -> LogLevel.FATAL;
            case 3 -> LogLevel.ERROR;
            case 4 -> LogLevel.WARN;
            case 5, 6 -> LogLevel.INFO;
            default -> LogLevel.DEBUG;
        };
    }

    static String facility(int pri) {
        int f = pri / 8;
        return f < FACILITIES.length ? FACILITIES[f] : String.valueOf(f);
    }

    /**
     * Where RFC 5424 structured data ends: after "-", or after the last of one or more
     * {@code [id name="value"…]} elements (a value may hold {@code \]} and {@code \"}).
     * 0 if {@code s} starts with neither.
     */
    static int structuredDataEnd(String s) {
        if (s.startsWith("-") && (s.length() == 1 || s.charAt(1) == ' ')) return 1;
        int i = 0;
        while (i < s.length() && s.charAt(i) == '[') {
            boolean quoted = false;
            int j = i + 1;
            for (; j < s.length(); j++) {
                char c = s.charAt(j);
                if (quoted && c == '\\') { j++; continue; }
                if (c == '"') quoted = !quoted;
                else if (c == ']' && !quoted) break;
            }
            if (j >= s.length()) return i;   // unterminated: what came before, if anything
            i = j + 1;
        }
        return i;
    }

    private static Instant iso(String text, ZoneId zone) {
        try {
            var parsed = ISO.parseBest(text, OffsetDateTime::from, LocalDateTime::from);
            if (parsed instanceof OffsetDateTime odt) return odt.toInstant();
            return ((LocalDateTime) parsed).atZone(zone).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Instant bsd(String text, ZoneId zone) {
        try {
            var t = BSD_TIME.parse(text);
            MonthDay day = MonthDay.from(t);
            ZonedDateTime now = ZonedDateTime.now(zone);
            int year = now.getYear();
            while (!day.isValidYear(year)) year--;   // Feb 29: the last leap year
            ZonedDateTime at = LocalDateTime.of(year, day.getMonth(), day.getDayOfMonth(),
                    t.get(ChronoField.HOUR_OF_DAY), t.get(ChronoField.MINUTE_OF_HOUR),
                    t.get(ChronoField.SECOND_OF_MINUTE)).atZone(zone);
            if (at.isAfter(now.plusDays(1))) at = at.minusYears(1);
            return at.toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String nil(String s) {
        return "-".equals(s) ? "" : s;
    }

    private static void putUnlessNil(Map<String, String> fields, String key, String value) {
        if (!"-".equals(value)) fields.put(key, value);
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }
}
