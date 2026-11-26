package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.api.LogSyntaxHighlighter.Role;
import com.droidenx.clouseau.api.LogSyntaxHighlighter.Span;
import com.droidenx.clouseau.api.SourceSetting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class PluginCheckTest {

    private static final List<String> SAMPLE = List.of(
            "2026-03-27T08:00:00Z INFO started",
            "2026-03-27T08:00:01+02:00 WARN disk low",
            "  a continuation line",
            "2026-03-27T08:00:02Z ERROR {\"code\":42}");

    private static final PluginCheck.BuiltIns NONE = new PluginCheck.BuiltIns(List.of(), Set.of("JSON"));

    /** Reads "<ISO time with offset> <LEVEL> <message>", parsing with writtenIn as the contract says. */
    private static class GoodParser implements LogParser {
        @Override public String getName() { return "Good"; }
        @Override public boolean canParse(String line) { return line.startsWith("2026-"); }
        @Override public LogEntry parse(String line) { return parse(line, ZoneId.systemDefault()); }
        @Override public LogEntry parse(String line, ZoneId writtenIn) {
            String[] parts = line.split(" ", 3);
            return LogEntry.builder(line).timestamp(OffsetDateTime.parse(parts[0]).toInstant())
                    .level(LogEntry.LogLevel.valueOf(parts[1])).message(parts[2]).build();
        }
    }

    private static PluginCheck.Extensions parsers(LogParser... p) {
        return new PluginCheck.Extensions(List.of(p), List.of(), List.of(), List.of());
    }

    private static String check(PluginCheck.Extensions ext, PluginCheck.BuiltIns builtIns, PluginCheck.Report r) {
        PluginCheck.checkExtensions(ext, builtIns, SAMPLE, r);
        return r.text();
    }

    @Test
    void aGoodParserPasses() {
        PluginCheck.Report r = new PluginCheck.Report();
        String text = check(parsers(new GoodParser()), NONE, r);

        assertThat(r.failures()).as(text).isZero();
        assertThat(text).contains("OK    Reads 3 of 4 lines")
                .contains("1 line that no parser reads shows as continuation lines")
                .contains("OK    Thread-safe")
                .contains("OK    Times with an offset keep it")
                .contains("2026-03-27T08:00:00Z INFO message=\"started\"");
    }

    @Test
    void aParserThatKeepsStateIsNotThreadSafe() {
        LogParser counting = new GoodParser() {
            private int n;
            @Override public LogEntry parse(String line, ZoneId writtenIn) {
                return super.parse(line, writtenIn).toBuilder().field("n", String.valueOf(n++)).build();
            }
        };
        PluginCheck.Report r = new PluginCheck.Report();
        assertThat(check(parsers(counting), NONE, r)).contains("FAIL  Not thread-safe");
    }

    @Test
    void aParserThatOnlyImplementsParseLineMovesTimesThatHaveAnOffset() {
        LogParser oneArgument = new LogParser() {
            final GoodParser good = new GoodParser();
            @Override public String getName() { return "One argument"; }
            @Override public boolean canParse(String line) { return good.canParse(line); }
            @Override public LogEntry parse(String line) { return good.parse(line); }
        };
        PluginCheck.Report r = new PluginCheck.Report();
        assertThat(check(parsers(oneArgument), NONE, r))
                .contains("FAIL  A time that has its own offset moves with the log's timezone");
    }

    @Test
    void aParserThatThrowsOrReturnsNullIsReported() {
        LogParser broken = new GoodParser() {
            @Override public LogEntry parse(String line, ZoneId writtenIn) {
                if (line.contains("WARN")) throw new IllegalArgumentException("no");
                if (line.contains("ERROR")) return null;
                return super.parse(line, writtenIn);
            }
        };
        PluginCheck.Report r = new PluginCheck.Report();
        assertThat(check(parsers(broken), NONE, r))
                .contains("FAIL  parse throws on 1 line canParse accepted")
                .contains("IllegalArgumentException (no)")
                .contains("FAIL  parse returns null for 1 line");
    }

    @Test
    void aNameTakenByABuiltInParserIsAFailure() {
        PluginCheck.BuiltIns builtIns = new PluginCheck.BuiltIns(List.of(new GoodParser()), Set.of());
        PluginCheck.Report r = new PluginCheck.Report();
        assertThat(check(parsers(new GoodParser()), builtIns, r))
                .contains("FAIL  A parser named \"Good\" already exists");
    }

    @Test
    void linesABuiltInReadsFirstAreAWarning() {
        LogParser builtIn = new GoodParser() {
            @Override public String getName() { return "Built in"; }
            @Override public boolean canParse(String line) { return line.contains("ERROR"); }
        };
        PluginCheck.BuiltIns builtIns = new PluginCheck.BuiltIns(List.of(builtIn), Set.of());
        PluginCheck.Report r = new PluginCheck.Report();
        assertThat(check(parsers(new GoodParser()), builtIns, r))
                .contains("WARN  Auto-detection gives 1 of the lines it reads to built-in parsers first (\"Built in\" 1)");
    }

    @Test
    void formattersAndHighlightersAreRunOnTheMessages() {
        LogFormatter throwing = new LogFormatter() {
            @Override public String getName() { return "Shout"; }
            @Override public boolean canFormat(String s) { return true; }
            @Override public String format(String s) {
                if (s.startsWith("{")) throw new IllegalStateException("bad");
                return s.toUpperCase(java.util.Locale.ROOT);
            }
        };
        LogSyntaxHighlighter pastEnd = new LogSyntaxHighlighter() {
            @Override public String getName() { return "JSON"; }
            @Override public boolean canHighlight(String s) { return true; }
            @Override public List<Span> highlight(String s) { return List.of(new Span(0, s.length() + 5, Role.KEY)); }
        };
        PluginCheck.Report r = new PluginCheck.Report();
        String text = check(new PluginCheck.Extensions(List.of(new GoodParser()), List.of(throwing), List.of(pastEnd), List.of()),
                NONE, r);

        assertThat(text).contains("Formatter \"Shout\"")
                .contains("FAIL  canFormat or format throws on 1 message")
                .contains("it turns:")
                .contains("Highlighter \"JSON\"")
                .contains("WARN  Shares its name, and so its detail-panel button, with a built-in format")
                .contains("end past the text and are cut")
                .contains("OK    Roles used: KEY");
    }

    @Test
    void aSourcesSettingsAreCheckedButItIsNeverCreated() {
        LogSourceProvider source = new LogSourceProvider() {
            @Override public String getName() { return "Queue"; }
            @Override public List<SourceSetting> settings() {
                return List.of(SourceSetting.text("host", "Host"), SourceSetting.number("host", "Port"));
            }
            @Override public LogSource create(Map<String, String> values) { throw new AssertionError("never created"); }
        };
        PluginCheck.Report r = new PluginCheck.Report();
        String text = check(new PluginCheck.Extensions(List.of(), List.of(), List.of(), List.of(source)), NONE, r);

        assertThat(text).contains("FAIL  Two settings have the key \"host\".")
                .contains("create() and open() weren't called");
    }

    @Test
    void theJarsManifestIndexAndContentsAreChecked(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("my-plugin.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Plugin-Id", "my-plugin");
        manifest.getMainAttributes().putValue("Plugin-Version", "1.0.0");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out, manifest)) {
            j.putNextEntry(new JarEntry("com/droidenx/clouseau/api/LogParser.class"));
            j.closeEntry();
        }
        PluginCheck.Report r = new PluginCheck.Report();
        PluginCheck.checkJar(jar, r);

        assertThat(r.text()).contains("WARN  Plugin-Id \"my-plugin\" isn't named <name>-clouseau-plugin")
                .contains("WARN  The manifest has no Plugin-Requires")
                .contains("WARN  The file is named my-plugin.jar, not my-plugin-1.0.0.jar")
                .contains("FAIL  No META-INF/extensions.idx")
                .contains("FAIL  The JAR contains clouseau-api classes")
                .contains("Result: 2 problems, 3 warnings");
    }
}
