package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.droidenx.clouseau.api.LogParser;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A user-defined log parser backed by a regex with named capture groups.
 *
 * <p>The following group names map to {@link LogEntry} fields:
 * {@code timestamp}, {@code level}, {@code logger}, {@code thread}, {@code message}.
 * Any other named group is stored in {@link LogEntry#fields()}.
 *
 * <p>All groups are optional — omit any you don't need.
 *
 * <p>Timestamp formats are tried in order; commas are normalised to dots before
 * parsing so that {@code yyyy-MM-dd HH:mm:ss.SSS} matches both {@code .123}
 * and {@code ,123} variants. Use {@code "ISO_OFFSET_DATE_TIME"} to match the
 * built-in {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} (handles variable
 * fractional seconds and timezone offsets).
 *
 * <p>Group names may have underscores ({@code (?<request_id>…)}), as log fields often do,
 * though Java's regex takes only letters and digits: compile a parser's regex with
 * {@link #compile}, which renames such a group for Java, and the field keeps its own name.
 */
public final class RegexLogParser implements LogParser {

    private static final Pattern GROUP_NAME_RE = Pattern.compile("\\(\\?<(\\w+)>");
    /** A group name, or a back-reference to one, as written in a parser's regex. */
    private static final Pattern NAME_IN_REGEX = Pattern.compile("(\\(\\?<|\\\\k<)([A-Za-z_][A-Za-z0-9_]*)>");
    /** What a name Java can't take becomes: this, then the name's UTF-8 bytes in hex. */
    private static final String RENAMED = "clouseauHex";

    private static final Set<String> STANDARD_FIELDS = Set.of(
            "timestamp", "level", "logger", "thread", "message");

    private final String            name;
    private final Pattern           pattern;
    private final DateTimeFormatter timestampFormatter;
    /** Each named group: the field's name → the group's name in the compiled pattern. */
    private final Map<String, String> namedGroups;
    /** {@link #timestampFormatter} in the zone used last, so a file's lines don't each copy it. */
    private volatile Zoned          zoned;

    private record Zoned(ZoneId zone, DateTimeFormatter formatter) {}

    public RegexLogParser(String name, Pattern pattern, String timestampFormat) {
        this.name    = name;
        this.pattern = pattern;
        this.namedGroups = extractNamedGroups(pattern);
        this.timestampFormatter = buildFormatter(timestampFormat);
    }

    @Override
    public String getName() { return name; }

    @Override
    public List<String> customFields() {
        List<String> result = new ArrayList<>();
        for (String g : namedGroups.keySet()) {
            if (!STANDARD_FIELDS.contains(g)) result.add(g);
        }
        return List.copyOf(result);
    }

    @Override
    public boolean canParse(String rawLine) {
        return rawLine != null && pattern.matcher(rawLine).matches();
    }

    @Override
    public LogEntry parse(String rawLine) {
        return parse(rawLine, ZoneId.systemDefault());
    }

    /**
     * Reads a timestamp without an offset as wall time in {@code writtenIn}; one with an
     * offset or zone in the text keeps it ({@code ISO_OFFSET_DATE_TIME}, or a pattern with
     * {@code XXX} or {@code VV}).
     */
    @Override
    public LogEntry parse(String rawLine, ZoneId writtenIn) {
        Matcher m = pattern.matcher(rawLine);
        if (!m.matches()) {
            return new LogEntry(null, LogLevel.UNKNOWN, "", "", rawLine, rawLine, Map.of());
        }

        Instant  timestamp = parseTimestamp(group(m, "timestamp"), writtenIn);
        LogLevel level     = parseLevel(group(m, "level"));
        String   logger    = trim(group(m, "logger"));
        String   thread    = trim(group(m, "thread"));
        String   message   = trim(group(m, "message"));

        Map<String, String> fields = new HashMap<>();
        for (String groupName : namedGroups.keySet()) {
            if (!STANDARD_FIELDS.contains(groupName)) {
                String value = group(m, groupName);
                if (value != null) fields.put(groupName, value);
            }
        }

        return new LogEntry(timestamp, level,
                logger  != null ? logger  : "",
                thread  != null ? thread  : "",
                message != null ? message : rawLine,
                rawLine, Map.copyOf(fields));
    }

    private static String trim(String s) {
        return s != null ? s.strip() : null;
    }

    private String group(Matcher m, String name) {
        String inPattern = namedGroups.get(name);
        if (inPattern == null) return null;
        try {
            return m.group(inPattern);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Instant parseTimestamp(String raw, ZoneId zone) {
        if (raw == null || timestampFormatter == null) return null;
        try {
            return formatterIn(zone).parse(raw.replace(',', '.'), Instant::from);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** The formatter in {@code zone}: an offset or zone in the text still wins over it. */
    private DateTimeFormatter formatterIn(ZoneId zone) {
        Zoned z = zoned;
        if (z == null || !z.zone().equals(zone)) {
            z = new Zoned(zone, timestampFormatter.withZone(zone));
            zoned = z;
        }
        return z.formatter();
    }

    private static LogLevel parseLevel(String raw) {
        if (raw == null) return LogLevel.UNKNOWN;
        try {
            return LogLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LogLevel.UNKNOWN;
        }
    }

    private static DateTimeFormatter buildFormatter(String format) {
        if ("ISO_OFFSET_DATE_TIME".equals(format)) return DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        // Normalize comma decimal separator to dot — Logback uses comma (HH:mm:ss,SSS)
        // but parseTimestamp normalizes the raw value the same way, so both must agree.
        return DateTimeFormatter.ofPattern(format.replace(',', '.'));
    }

    private static Map<String, String> extractNamedGroups(Pattern p) {
        Map<String, String> names = new LinkedHashMap<>();
        Matcher m = GROUP_NAME_RE.matcher(p.pattern());
        while (m.find()) names.put(fieldName(m.group(1)), m.group(1));
        return Collections.unmodifiableMap(names);
    }

    /**
     * Compiles a parser's regex, renaming each group (and back-reference) whose name Java
     * wouldn't take, such as {@code request_id}, so that it compiles; the parser gives the
     * field its own name back. A regex without such names compiles as written.
     *
     * @throws java.util.regex.PatternSyntaxException if the regex is invalid
     */
    public static Pattern compile(String regex) {
        Matcher m = NAME_IN_REGEX.matcher(regex);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + groupName(name) + ">"));
        }
        m.appendTail(sb);
        return Pattern.compile(sb.toString());
    }

    /** The group name Java takes for a field: the field's own name when it can. */
    static String groupName(String field) {
        if (field.matches("[A-Za-z][A-Za-z0-9]*") && !field.startsWith(RENAMED)) return field;
        return RENAMED + HexFormat.of().formatHex(field.getBytes(StandardCharsets.UTF_8));
    }

    /** The field a group stands for: {@link #groupName} undone. */
    static String fieldName(String group) {
        if (!group.startsWith(RENAMED)) return group;
        String hex = group.substring(RENAMED.length());
        if (hex.isEmpty() || hex.length() % 2 != 0 || !hex.matches("[0-9a-f]+")) return group;
        return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    }
}
