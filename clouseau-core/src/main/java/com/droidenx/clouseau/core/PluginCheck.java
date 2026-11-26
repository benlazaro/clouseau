package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.api.LogSyntaxHighlighter.Span;
import com.droidenx.clouseau.api.SourceSetting;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * D-35: checks a plugin the way Clouseau would use it, without the window, for {@code --check-plugin}.
 * Its JAR (manifest, extension index, what it bundles), then each extension against a sample log:
 * what a parser accepts and makes of it, whether it is thread-safe and reads timezones as
 * {@link LogParser#parse(String, ZoneId)} says, which lines auto-detection gives to a built-in
 * parser first, and what formatters and highlighters do with the sample's messages. A source's
 * settings are checked, but it is never created or opened (that may do I/O).
 *
 * <p>The result is a plain-text {@link Report} whose lines start with {@code OK}, {@code WARN},
 * {@code FAIL} or {@code NOTE}, written to be read by a person or a coding assistant. Extensions
 * are called directly, unguarded, so every failure is seen and reported rather than logged.
 */
public final class PluginCheck {

    /** Lines of the sample used at most; enough to see a format, quick to check. */
    public static final int MAX_SAMPLE_LINES = 5_000;
    /** Messages handed to formatters and highlighters at most. */
    static final int MAX_MESSAGES = 500;
    /** A formatter or highlighter slower than this on one message is reported: it runs on the event thread. */
    static final double SLOW_MS = 20;
    /** canParse slower than this per line is reported: auto-detection calls it on every line. */
    static final double SLOW_CAN_PARSE_MICROS = 10;

    /** A time of day followed by an offset or Z: a timestamp that carries its own zone. */
    private static final Pattern TIME_WITH_OFFSET =
            Pattern.compile("\\d{2}:\\d{2}(:\\d{2}([.,]\\d+)?)?\\s?(Z|[+-]\\d{2}:?\\d{2})(?![\\d:])");
    private static final ZoneId ZONE_A = ZoneId.of("UTC");
    private static final ZoneId ZONE_B = ZoneId.of("Asia/Tokyo");

    private PluginCheck() {}

    /** What one plugin provides, as PF4J found it. */
    public record Extensions(List<LogParser> parsers, List<LogFormatter> formatters,
                             List<LogSyntaxHighlighter> highlighters, List<LogSourceProvider> sources) {
        public int count() { return parsers.size() + formatters.size() + highlighters.size() + sources.size(); }
    }

    /**
     * What Clouseau already has, which a plugin's extensions meet: the built-in parsers in
     * detection order, and the names of the built-in formatters and highlighters.
     */
    public record BuiltIns(List<LogParser> parsers, Set<String> formatNames) {}

    // ── The report ───────────────────────────────────────────────────────────

    /** The check's findings, as text. Not thread-safe. */
    public static final class Report {
        public enum Level { OK, WARN, FAIL, NOTE }

        private final StringBuilder text = new StringBuilder();
        private int failures, warnings;

        public Report section(String title) {
            if (!text.isEmpty()) text.append('\n');
            text.append(title).append('\n');
            return this;
        }

        public Report ok(String s)   { return line(Level.OK, s); }
        public Report warn(String s) { warnings++; return line(Level.WARN, s); }
        public Report fail(String s) { failures++; return line(Level.FAIL, s); }
        public Report note(String s) { return line(Level.NOTE, s); }

        /** An indented line under the one before: an example, a value. */
        public Report detail(String s) {
            text.append("        ").append(s).append('\n');
            return this;
        }

        private Report line(Level level, String s) {
            text.append("  ").append(String.format("%-6s", level.name())).append(s).append('\n');
            return this;
        }

        public int failures() { return failures; }
        public int warnings() { return warnings; }

        /** The report, ending with the totals. */
        public String text() {
            return text + "\nResult: " + plural(failures, "problem") + ", " + plural(warnings, "warning") + "\n";
        }
    }

    // ── The JAR ──────────────────────────────────────────────────────────────

    /** Checks the JAR's manifest, extension index and contents, before PF4J loads it. */
    public static void checkJar(Path jar, Report r) {
        r.section("Plugin file: " + jar.getFileName());
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            Attributes a = manifest != null ? manifest.getMainAttributes() : new Attributes();
            String id = trimmed(a.getValue("Plugin-Id"));
            String version = trimmed(a.getValue("Plugin-Version"));
            String requires = trimmed(a.getValue("Plugin-Requires"));
            if (id == null) {
                r.fail("The manifest has no Plugin-Id, so Clouseau doesn't see a plugin. Set it in the jar task's manifest.");
            } else if (!PluginCatalog.isPluginName(id)) {
                r.warn("Plugin-Id \"" + id + "\" isn't named <name>-clouseau-plugin; Browse and the plugin list"
                        + " find plugins by that name.");
            } else {
                r.ok("Plugin-Id " + id);
            }
            if (version == null) r.fail("The manifest has no Plugin-Version (a semantic version such as 1.0.0).");
            else r.ok("Plugin-Version " + version);
            if (requires == null) {
                r.warn("The manifest has no Plugin-Requires. Set it to the API you built against, such as >="
                        + com.droidenx.clouseau.api.ClouseauApi.VERSION + ", so an older Clouseau says why it can't load it.");
            } else {
                r.ok("Plugin-Requires " + requires);
            }
            if (id != null && version != null && !jar.getFileName().toString().equals(id + "-" + version + ".jar")) {
                r.warn("The file is named " + jar.getFileName() + ", not " + id + "-" + version + ".jar; the Plugin"
                        + " Manager reads the installed version from that name to offer updates.");
            }

            if (file.getEntry("META-INF/extensions.idx") == null) {
                r.fail("No META-INF/extensions.idx, so PF4J finds no extensions. Add PF4J as an annotationProcessor"
                        + " and annotate each extension class with @Extension.");
            } else {
                r.ok("META-INF/extensions.idx lists " + plural(extensionIndex(file).size(), "class", "classes"));
            }
            boolean bundlesApi = file.stream().anyMatch(e -> e.getName().startsWith("com/droidenx/clouseau/api/"));
            boolean bundlesPf4j = file.stream().anyMatch(e -> e.getName().startsWith("org/pf4j/"));
            if (bundlesApi || bundlesPf4j) {
                r.fail("The JAR contains " + (bundlesApi ? "clouseau-api" : "PF4J") + (bundlesApi && bundlesPf4j ? " and PF4J" : "")
                        + " classes. Clouseau provides both; depend on them as compileOnly and don't build a fat JAR,"
                        + " or Clouseau won't recognize your extensions.");
            }
        } catch (IOException e) {
            r.fail("Can't read it as a JAR: " + e.getMessage());
        }
    }

    /** The class names META-INF/extensions.idx lists. */
    static List<String> extensionIndex(JarFile file) throws IOException {
        var entry = file.getEntry("META-INF/extensions.idx");
        if (entry == null) return List.of();
        try (var in = file.getInputStream(entry)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        }
    }

    // ── The extensions ───────────────────────────────────────────────────────

    /** Checks every extension; {@code sample} may be empty (then only names and settings are checked). */
    public static void checkExtensions(Extensions ext, BuiltIns builtIns, List<String> sample, Report r) {
        List<String> lines = sample.size() > MAX_SAMPLE_LINES ? sample.subList(0, MAX_SAMPLE_LINES) : sample;

        List<LogParser> parsers = new ArrayList<>();
        Set<String> parserNames = new HashSet<>();
        builtIns.parsers().forEach(p -> parserNames.add(p.getName()));
        for (LogParser p : ext.parsers()) {
            String name = name(p::getName, p, r, "Parser");
            if (name == null) continue;
            if (!parserNames.add(name)) {
                r.section("Parser \"" + name + "\"");
                r.fail("A parser named \"" + name + "\" already exists, so Clouseau skips this one. Give it another name.");
                continue;
            }
            parsers.add(p);
        }

        // Auto-detection's order: built-ins (and parsers.json) first, then plugin parsers.
        List<LogParser> detection = new ArrayList<>(builtIns.parsers());
        detection.addAll(parsers);
        Map<Integer, LogParser> winners = new LinkedHashMap<>();
        List<String> messages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LogEntry entry = null;
            for (LogParser p : detection) {
                entry = quietParse(p, line);
                if (entry != null) { winners.put(i, p); break; }
            }
            String message = entry != null && entry.message() != null ? entry.message() : line;
            if (messages.size() < MAX_MESSAGES && !message.isBlank() && seen.add(message)) messages.add(message);
        }

        for (LogParser p : parsers) checkParser(p, lines, winners, r);
        for (LogFormatter f : ext.formatters()) checkFormatter(f, messages, builtIns, ext, lines.isEmpty(), r);
        if (!ext.highlighters().isEmpty()) {
            List<String> formatted = formatted(messages, ext.formatters());
            for (LogSyntaxHighlighter h : ext.highlighters()) checkHighlighter(h, formatted, builtIns, ext, lines.isEmpty(), r);
        }
        for (LogSourceProvider s : ext.sources()) checkSource(s, r);
    }

    private static void checkParser(LogParser p, List<String> lines, Map<Integer, LogParser> winners, Report r) {
        String name = p.getName();
        r.section("Parser \"" + name + "\"");
        List<String> declared;
        try {
            declared = Objects.requireNonNullElse(p.customFields(), List.of());
            if (!declared.isEmpty()) r.ok("customFields(): " + String.join(", ", declared));
        } catch (RuntimeException e) {
            r.fail("customFields() throws " + describe(e));
            declared = List.of();
        }
        if (lines.isEmpty()) {
            r.note("No sample log given, so the parser wasn't run. Pass one after the JAR.");
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        List<String> accepted = new ArrayList<>();
        List<LogEntry> entries = new ArrayList<>();
        String rejectedExample = null;
        int canParseFailures = 0, parseFailures = 0, nulls = 0;
        String canParseError = null, parseError = null, nullExample = null;

        // Twice: the first pass warms the JIT, the second is timed.
        long canParseNanos = 0;
        for (int pass = 0; pass < 2; pass++) {
            long start = System.nanoTime();
            for (String line : lines) {
                try { p.canParse(line); } catch (RuntimeException ignored) { /* counted below */ }
            }
            canParseNanos = System.nanoTime() - start;
        }

        int orphans = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean can;
            try {
                can = p.canParse(line);
            } catch (RuntimeException e) {
                if (canParseFailures++ == 0) canParseError = describe(e) + " on: " + clip(line);
                continue;
            }
            if (!can) {
                // A line no other parser reads either becomes a continuation line in a tab of this format.
                if (!winners.containsKey(i) && !line.isBlank()) {
                    orphans++;
                    if (rejectedExample == null) rejectedExample = line;
                }
                continue;
            }
            try {
                LogEntry e = p.parse(line, zone);
                if (e == null) {
                    if (nulls++ == 0) nullExample = line;
                } else {
                    accepted.add(line);
                    entries.add(e);
                }
            } catch (RuntimeException e) {
                if (parseFailures++ == 0) parseError = describe(e) + " on: " + clip(line);
            }
        }

        double micros = canParseNanos / 1000.0 / lines.size();
        String speed = String.format(java.util.Locale.ROOT, "canParse takes %.2f microseconds a line", micros);
        if (accepted.isEmpty()) {
            r.fail("Reads none of the sample's " + plural(lines.size(), "line") + "; " + speed + ".");
            if (rejectedExample != null) r.detail("canParse says no to: " + clip(rejectedExample));
        } else {
            r.ok("Reads " + accepted.size() + " of " + plural(lines.size(), "line") + "; " + speed + ".");
            if (rejectedExample != null) {
                r.note(plural(orphans, "line") + " that no parser reads " + (orphans == 1 ? "shows" : "show")
                        + " as continuation lines of the entry"
                        + " before them (right for stack traces and wrapped text), such as:");
                r.detail(clip(rejectedExample));
            }
        }
        if (micros > SLOW_CAN_PARSE_MICROS) {
            r.warn("canParse is slow: auto-detection calls it on every line of every file. Look at the line's start, don't parse it.");
        }
        if (canParseFailures > 0) {
            r.fail("canParse throws on " + plural(canParseFailures, "line") + "; Clouseau counts that as no. First: " + canParseError);
        }
        if (parseFailures > 0) {
            r.fail("parse throws on " + plural(parseFailures, "line") + " canParse accepted; they show as unparsed. First: " + parseError);
        }
        if (nulls > 0) {
            r.fail("parse returns null for " + plural(nulls, "line") + " canParse accepted; they show as unparsed. First:");
            r.detail(clip(nullExample));
        }
        if (entries.isEmpty()) return;

        checkEntries(entries, accepted, declared, r);
        checkTimezones(p, accepted, entries, r);
        checkThreads(p, accepted, entries, zone, r);
        checkDetection(p, lines, winners, r);

        r.note("The first entries it reads:");
        for (int i = 0; i < Math.min(3, entries.size()); i++) r.detail(describe(entries.get(i)));
    }

    private static void checkEntries(List<LogEntry> entries, List<String> accepted, List<String> declared, Report r) {
        if (entries.stream().allMatch(e -> e.timestamp() == null)) {
            r.warn("No entry has a timestamp, so the time filter, sorting by time and merged views can't use them.");
        }
        if (entries.stream().allMatch(e -> e.level() == LogEntry.LogLevel.UNKNOWN)) {
            r.warn("Every entry's level is UNKNOWN, so the level filter and colors can't use them.");
        }
        boolean wholeLine = true;
        for (int i = 0; i < entries.size() && wholeLine; i++) {
            wholeLine = Objects.equals(entries.get(i).message(), accepted.get(i));
        }
        if (wholeLine) {
            r.note("Every entry's message is the whole line. Set .message(...) to the text after the line's header.");
        }
        Set<String> used = new LinkedHashSet<>();
        entries.forEach(e -> used.addAll(e.fields().keySet()));
        Set<String> undeclared = new LinkedHashSet<>(used);
        undeclared.removeAll(declared);
        if (!undeclared.isEmpty()) {
            r.note("Entries have fields customFields() doesn't list: " + String.join(", ", undeclared) + ". They get"
                    + " columns once rows with them are read; list them to have the columns from the start, in your order.");
        }
        Set<String> unused = new LinkedHashSet<>(declared);
        unused.removeAll(used);
        if (!unused.isEmpty()) r.note("customFields() lists fields no entry of the sample has: " + String.join(", ", unused));
    }

    /** {@link LogParser#parse(String, ZoneId)}: zoneless times move with writtenIn, times with an offset don't. */
    private static void checkTimezones(LogParser p, List<String> accepted, List<LogEntry> entries, Report r) {
        String withOffset = null, zoneless = null;
        boolean offsetMoved = false, zonelessStill = false, checkedOffset = false, checkedZoneless = false;
        for (int i = 0; i < accepted.size() && i < 200; i++) {
            if (entries.get(i).timestamp() == null) continue;
            String line = accepted.get(i);
            Instant a, b;
            try {
                LogEntry ea = p.parse(line, ZONE_A), eb = p.parse(line, ZONE_B);
                if (ea == null || eb == null || ea.timestamp() == null || eb.timestamp() == null) continue;
                a = ea.timestamp();
                b = eb.timestamp();
            } catch (RuntimeException e) {
                continue;   // reported by the main pass
            }
            if (TIME_WITH_OFFSET.matcher(line).find()) {
                checkedOffset = true;
                if (!a.equals(b) && !offsetMoved) { offsetMoved = true; withOffset = line; }
            } else {
                checkedZoneless = true;
                if (a.equals(b) && !zonelessStill) { zonelessStill = true; zoneless = line; }
            }
        }
        if (offsetMoved) {
            r.fail("A time that has its own offset moves with the log's timezone. Override parse(line, writtenIn) and"
                    + " parse with DateTimeFormatter.withZone(writtenIn), whose offset in the text wins:");
            r.detail(clip(withOffset));
        } else if (checkedOffset) {
            r.ok("Times with an offset keep it, whatever the log's timezone.");
        }
        if (zonelessStill) {
            r.note("This time doesn't change with the log's timezone. Right for epoch times; for a wall-clock time"
                    + " with no offset, read it in parse(line, writtenIn)'s writtenIn:");
            r.detail(clip(zoneless));
        } else if (checkedZoneless) {
            r.ok("Times without an offset are read in the log's timezone.");
        }
    }

    /** One instance parses from several threads at once: the results must match parsing on one. */
    private static void checkThreads(LogParser p, List<String> accepted, List<LogEntry> expected, ZoneId zone, Report r) {
        int n = Math.min(accepted.size(), 2_000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads, run -> {
            Thread t = new Thread(run, "plugin-check");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int offset = t * 7;
                results.add(pool.submit(() -> {
                    for (int round = 0; round < 3; round++) {
                        for (int k = 0; k < n; k++) {
                            int i = (k + offset) % n;
                            LogEntry e;
                            try {
                                e = p.parse(accepted.get(i), zone);
                            } catch (RuntimeException ex) {
                                return describe(ex) + " on: " + clip(accepted.get(i));
                            }
                            if (!Objects.equals(e, expected.get(i))) return "different result for: " + clip(accepted.get(i));
                        }
                    }
                    return null;
                }));
            }
            for (Future<String> f : results) {
                String problem = f.get();
                if (problem != null) {
                    r.fail("Not thread-safe: parsing from several threads at once gives " + problem
                            + ". Keep the parser stateless (DateTimeFormatter, not SimpleDateFormat; no shared Matcher).");
                    return;
                }
            }
            r.ok("Thread-safe: " + threads + " threads parsing at once get the same entries.");
        } catch (Exception e) {
            r.warn("Couldn't check thread safety: " + describe(e));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Which built-in parsers take the lines this parser reads before auto-detection reaches it. */
    private static void checkDetection(LogParser p, List<String> lines, Map<Integer, LogParser> winners, Report r) {
        int won = 0;
        Map<String, Integer> lostTo = new LinkedHashMap<>();
        String lostExample = null;
        for (var e : winners.entrySet()) {
            LogParser winner = e.getValue();
            if (winner == p) { won++; continue; }
            if (quietParse(p, lines.get(e.getKey())) == null) continue;
            lostTo.merge(winner.getName(), 1, Integer::sum);
            if (lostExample == null) lostExample = lines.get(e.getKey());
        }
        if (lostTo.isEmpty()) {
            r.ok("Auto-detection gives it all " + plural(won, "line") + " it reads.");
            return;
        }
        StringBuilder who = new StringBuilder();
        lostTo.forEach((name, count) -> who.append(who.isEmpty() ? "" : ", ").append('"').append(name).append("\" ").append(count));
        r.warn("Auto-detection gives " + lostTo.values().stream().mapToInt(Integer::intValue).sum() + " of the lines it reads"
                + " to built-in parsers first (" + who + "): they come before plugin parsers. Users can still choose"
                + " this parser for the tab. First:");
        r.detail(clip(lostExample));
    }

    private static void checkFormatter(LogFormatter f, List<String> messages, BuiltIns builtIns, Extensions ext,
                                       boolean noSample, Report r) {
        String name = name(f::getName, f, r, "Formatter");
        if (name == null) return;
        r.section("Formatter \"" + name + "\"");
        if (builtIns.formatNames().contains(name)) {
            r.warn("Shares its name, and so its detail-panel button, with a built-in format. Pick another name.");
        }
        if (ext.highlighters().stream().anyMatch(h -> name.equals(safeName(h::getName)))) {
            r.ok("Shares one detail-panel button with the highlighter of the same name.");
        }
        if (noSample) { r.note("No sample log given, so it wasn't run. Pass one after the JAR."); return; }

        int accepted = 0, changed = 0, failures = 0, nulls = 0;
        String error = null, before = null, after = null;
        double slowest = 0;
        for (String m : messages) {
            long start = System.nanoTime();
            try {
                if (!f.canFormat(m)) continue;
                accepted++;
                String out = f.format(m);
                if (out == null) { nulls++; continue; }
                if (!out.equals(m)) { changed++; if (before == null) { before = m; after = out; } }
            } catch (RuntimeException e) {
                if (failures++ == 0) error = describe(e) + " on: " + clip(m);
            } finally {
                slowest = Math.max(slowest, (System.nanoTime() - start) / 1e6);
            }
        }
        reportTextExtension(accepted, messages.size(), failures, error, slowest, "format", r);
        if (nulls > 0) r.fail("format returns null for " + plural(nulls, "message") + "; return the input unchanged instead.");
        if (accepted > 0 && changed == 0) r.warn("Accepts " + plural(accepted, "message") + " but changes none of them.");
        if (before != null) {
            r.note("For example, it turns:");
            r.detail(clip(before));
            r.note("into:");
            after.lines().limit(12).forEach(l -> r.detail(clip(l)));
        }
    }

    private static void checkHighlighter(LogSyntaxHighlighter h, List<String> texts, BuiltIns builtIns, Extensions ext,
                                         boolean noSample, Report r) {
        String name = name(h::getName, h, r, "Highlighter");
        if (name == null) return;
        r.section("Highlighter \"" + name + "\"");
        if (builtIns.formatNames().contains(name)) {
            r.warn("Shares its name, and so its detail-panel button, with a built-in format. Pick another name.");
        }
        if (noSample) { r.note("No sample log given, so it wasn't run. Pass one after the JAR."); return; }

        int accepted = 0, failures = 0, nulls = 0, pastEnd = 0, nullSpans = 0;
        String error = null, example = null;
        List<Span> exampleSpans = null;
        Map<LogSyntaxHighlighter.Role, Integer> roles = new EnumMap<>(LogSyntaxHighlighter.Role.class);
        double slowest = 0;
        for (String t : texts) {
            long start = System.nanoTime();
            try {
                if (!h.canHighlight(t)) continue;
                accepted++;
                List<Span> spans = h.highlight(t);
                if (spans == null) { nulls++; continue; }
                for (Span s : spans) {
                    if (s == null) { nullSpans++; continue; }
                    if (s.end() > t.length()) pastEnd++;
                    roles.merge(s.role(), 1, Integer::sum);
                }
                if (example == null && !spans.isEmpty()) { example = t; exampleSpans = spans; }
            } catch (RuntimeException e) {
                if (failures++ == 0) error = describe(e) + " on: " + clip(t);
            } finally {
                slowest = Math.max(slowest, (System.nanoTime() - start) / 1e6);
            }
        }
        reportTextExtension(accepted, texts.size(), failures, error, slowest, "highlight", r);
        if (nulls > 0) r.warn("highlight returns null for " + plural(nulls, "message") + "; return an empty list.");
        if (nullSpans > 0) r.warn("highlight's lists hold " + plural(nullSpans, "null span") + ", which are skipped.");
        if (pastEnd > 0) r.warn(plural(pastEnd, "span") + " end past the text and are cut. Spans are [start, end) in the text given.");
        if (!roles.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            roles.forEach((role, count) -> sb.append(sb.isEmpty() ? "" : ", ").append(role).append(' ').append(count));
            r.ok("Roles used: " + sb);
        } else if (accepted > 0) {
            r.warn("Accepts " + plural(accepted, "message") + " but marks nothing in them.");
        }
        if (example != null) {
            String text = example;
            r.note("For example, in: " + clip(text));
            exampleSpans.stream().filter(Objects::nonNull).limit(10).forEach(s -> r.detail(String.format("%-11s [%d, %d) %s",
                    s.role(), s.start(), s.end(), quote(text.substring(Math.min(s.start(), text.length()),
                            Math.min(s.end(), text.length()))))));
        }
    }

    private static void reportTextExtension(int accepted, int total, int failures, String error, double slowestMs,
                                            String method, Report r) {
        String can = "format".equals(method) ? "canFormat" : "canHighlight";
        if (accepted == 0) {
            r.note(can + " accepts none of the sample's " + plural(total, "message")
                    + ". Pass a sample whose messages it's meant for.");
        } else {
            r.ok(can + " accepts " + accepted + " of the sample's " + plural(total, "message") + ".");
        }
        if (failures > 0) {
            r.fail(can + " or " + method + " throws on " + plural(failures, "message")
                    + "; Clouseau then shows the message without it. First: " + error);
        }
        if (slowestMs > SLOW_MS) {
            r.warn(String.format(java.util.Locale.ROOT, "Takes up to %.0f ms on one message. It runs on the event"
                    + " thread, so the window waits.", slowestMs));
        }
    }

    private static void checkSource(LogSourceProvider s, Report r) {
        String name = name(s::getName, s, r, "Log source");
        if (name == null) return;
        r.section("Log source \"" + name + "\"");
        List<SourceSetting> settings;
        try {
            settings = s.settings();
        } catch (RuntimeException e) {
            r.fail("settings() throws " + describe(e) + "; Clouseau can't show its form.");
            return;
        }
        if (settings == null) { r.fail("settings() returns null; return List.of() for none."); return; }
        Set<String> keys = new HashSet<>();
        boolean bad = false;
        for (SourceSetting setting : settings) {
            if (setting == null) { r.fail("settings() holds a null setting."); bad = true; continue; }
            if (setting.key() == null || setting.key().isBlank()) { r.fail("A setting has no key."); bad = true; }
            else if (!keys.add(setting.key())) { r.fail("Two settings have the key \"" + setting.key() + "\"."); bad = true; }
            if (setting.label() == null || setting.label().isBlank()) {
                r.fail("Setting \"" + setting.key() + "\" has no label for the form."); bad = true;
            }
        }
        if (!bad) r.ok("File > Open " + name + "... shows " + plural(settings.size(), "setting") + ":");
        for (SourceSetting setting : settings) {
            if (setting == null) continue;
            r.detail(String.format("%-14s %-9s %s%s%s", setting.key(), setting.type(), quote(setting.label()),
                    setting.required() ? "" : ", optional",
                    setting.defaultValue() != null ? ", default " + quote(setting.defaultValue()) : ""));
        }
        r.note("create() and open() weren't called: they may connect somewhere. Try it in Clouseau.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The message after the plugin's formatters, as its highlighters would get it. */
    private static List<String> formatted(List<String> messages, List<LogFormatter> formatters) {
        List<String> out = new ArrayList<>(messages.size());
        for (String m : messages) {
            String text = m;
            for (LogFormatter f : formatters) {
                try {
                    if (f.canFormat(text)) {
                        String next = f.format(text);
                        if (next != null) text = next;
                    }
                } catch (RuntimeException ignored) {
                    // reported by the formatter's own check
                }
            }
            out.add(text);
        }
        return out;
    }

    private static LogEntry quietParse(LogParser p, String line) {
        try {
            return p.canParse(line) ? p.parse(line, ZoneId.systemDefault()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The extension's name, or null after reporting why it has none. */
    private static String name(java.util.function.Supplier<String> getName, Object ext, Report r, String kind) {
        String simple = ext.getClass().getSimpleName();
        try {
            String n = getName.get();
            if (n != null && !n.isBlank()) return n;
            r.section(kind + " " + simple);
            r.fail("getName() returns " + (n == null ? "null" : "a blank name") + "; Clouseau skips it.");
        } catch (RuntimeException e) {
            r.section(kind + " " + simple);
            r.fail("getName() throws " + describe(e) + "; Clouseau skips it.");
        }
        return null;
    }

    private static String safeName(java.util.function.Supplier<String> getName) {
        try { return getName.get(); } catch (RuntimeException e) { return null; }
    }

    static String describe(LogEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.timestamp() != null ? e.timestamp() : "(no time)").append(' ').append(e.level());
        if (e.logger() != null) sb.append(" logger=").append(quote(e.logger()));
        if (e.thread() != null) sb.append(" thread=").append(quote(e.thread()));
        sb.append(" message=").append(quote(e.message()));
        if (!e.fields().isEmpty()) {
            sb.append(" fields={");
            int i = 0;
            for (var f : e.fields().entrySet()) sb.append(i++ > 0 ? ", " : "").append(f.getKey()).append('=').append(quote(f.getValue()));
            sb.append('}');
        }
        return clip(sb.toString(), 240);
    }

    private static String describe(Throwable e) {
        StackTraceElement at = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        return e.getClass().getSimpleName() + (e.getMessage() != null ? " (" + clip(e.getMessage(), 80) + ")" : "")
                + (at != null ? " at " + at.getClassName().replaceAll(".*\\.", "") + "." + at.getMethodName()
                + (at.getLineNumber() > 0 ? ":" + at.getLineNumber() : "") : "");
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + '"';
    }

    private static String clip(String s) { return clip(s, 160); }

    private static String clip(String s, int max) {
        if (s == null) return "null";
        String one = s.replace("\n", "\\n").replace("\t", "\\t");
        return one.length() <= max ? one : one.substring(0, max - 3) + "...";
    }

    private static String trimmed(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String plural(int n, String one) { return plural(n, one, one + "s"); }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** The class names of {@code extensions}, for comparing with the extension index. */
    public static Set<String> classNames(Collection<?> extensions) {
        Set<String> out = new HashSet<>();
        extensions.forEach(e -> out.add(e.getClass().getName()));
        return out;
    }

    /** The class names META-INF/extensions.idx of {@code jar} lists, or none if it can't be read. */
    public static List<String> extensionIndex(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return extensionIndex(file);
        } catch (IOException e) {
            return List.of();
        }
    }
}
