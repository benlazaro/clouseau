package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.api.ClouseauApi;
import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import com.droidenx.clouseau.core.LogReaders;
import com.droidenx.clouseau.core.ParserList;
import com.droidenx.clouseau.core.PluginCheck;
import com.droidenx.clouseau.core.StackTraceHighlighter;
import com.droidenx.clouseau.core.ToStringHighlighter;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * D-35: {@code clouseau --check-plugin <plugin.jar> [<sample.log>]}. Loads the JAR through PF4J
 * from a folder of its own, as the app would load it from the plugins folder, and prints
 * {@link PluginCheck}'s report. No window opens, and it runs alongside a Clouseau that's open.
 * Exit code 0 when nothing failed (warnings allowed), 1 when something did, 2 for bad arguments.
 */
@Slf4j
final class PluginCheckCommand {

    static final String FLAG = "--check-plugin";

    private PluginCheckCommand() {}

    static boolean handles(String[] args) {
        return args.length > 0 && FLAG.equals(args[0]);
    }

    static int run(String[] args, PrintStream out) {
        if (args.length < 2 || args.length > 3) {
            out.println(Messages.format("check.plugin.usage"));
            return 2;
        }
        Path jar = Path.of(args[1]).toAbsolutePath();
        Path sampleFile = args.length > 2 ? Path.of(args[2]).toAbsolutePath() : null;
        if (!Files.isRegularFile(jar)) {
            out.println(Messages.format("check.plugin.no.file", jar));
            return 2;
        }
        List<String> sample = List.of();
        if (sampleFile != null) {
            try {
                sample = readSample(sampleFile);
            } catch (IOException e) {
                out.println(Messages.format("check.plugin.no.sample", sampleFile, e.getMessage()));
                return 2;
            }
        }

        PluginCheck.Report report = new PluginCheck.Report();
        out.println("Clouseau plugin check (" + AppPrefs.getAppVersion() + ", plugin API " + ClouseauApi.VERSION + ")");
        if (sampleFile != null) {
            out.println("Sample: " + sampleFile.getFileName() + ", " + sample.size() + " lines"
                    + (sample.size() >= PluginCheck.MAX_SAMPLE_LINES ? " (the first " + PluginCheck.MAX_SAMPLE_LINES + ")" : ""));
        }
        out.println();
        PluginCheck.checkJar(jar, report);

        Path folder = null;
        ClouseauPluginManager plugins = null;
        try {
            folder = Files.createTempDirectory("clouseau-plugin-check");
            Files.copy(jar, folder.resolve(jar.getFileName()));
            plugins = new ClouseauPluginManager(folder);
            plugins.loadAll();
            if (load(plugins, jar, report)) {
                PluginCheck.Extensions ext = new PluginCheck.Extensions(
                        plugins.getExtensions(LogParser.class), plugins.getExtensions(LogFormatter.class),
                        plugins.getExtensions(LogSyntaxHighlighter.class), plugins.getExtensions(LogSourceProvider.class));
                if (found(ext, jar, report)) PluginCheck.checkExtensions(ext, builtIns(), sample, report);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            log.warn("Plugin check failed", e);
            report.fail("The check stopped: " + e);
        } finally {
            if (plugins != null) plugins.stopAll();
            delete(folder);
        }
        out.print(report.text());
        out.flush();
        return report.failures() > 0 ? 1 : 0;
    }

    /** Reports whether PF4J loaded and started it; false if it didn't. */
    private static boolean load(ClouseauPluginManager plugins, Path jar, PluginCheck.Report r) {
        r.section("Loading in Clouseau");
        List<ClouseauPluginManager.PluginInfo> infos = plugins.getPluginInfos();
        if (infos.isEmpty()) {
            r.fail("PF4J didn't load it. Is it a plugin JAR with a Plugin-Id in its manifest?");
            return false;
        }
        ClouseauPluginManager.PluginInfo info = infos.get(0);
        if (info.failed()) {
            r.fail(info.error() != null ? info.error() : "It failed to load; clouseau.log in the data folder has the details.");
            return false;
        }
        r.ok("Loads as \"" + info.name() + "\" " + info.version() + ", " + info.state().toLowerCase(java.util.Locale.ROOT)
                + (info.description().isEmpty() ? "" : ": " + info.description()));
        return info.enabled();
    }

    /** Reports the extensions PF4J found, against the extension index; false if there are none. */
    private static boolean found(PluginCheck.Extensions ext, Path jar, PluginCheck.Report r) {
        Set<String> loaded = new LinkedHashSet<>();
        loaded.addAll(PluginCheck.classNames(ext.parsers()));
        loaded.addAll(PluginCheck.classNames(ext.formatters()));
        loaded.addAll(PluginCheck.classNames(ext.highlighters()));
        loaded.addAll(PluginCheck.classNames(ext.sources()));
        List<String> missing = new ArrayList<>(PluginCheck.extensionIndex(jar));
        missing.removeAll(loaded);
        if (!missing.isEmpty()) {
            r.fail("Listed in META-INF/extensions.idx but not loaded as a parser, formatter, highlighter or source: "
                    + String.join(", ", missing) + ". Does it implement one of them, with a public no-argument"
                    + " constructor, and are the classes it needs in the JAR?");
        }
        if (ext.count() == 0) {
            r.fail("It has no extensions Clouseau can use.");
            return false;
        }
        r.ok("Provides " + count(ext.parsers().size(), "parser") + ", " + count(ext.formatters().size(), "formatter")
                + ", " + count(ext.highlighters().size(), "highlighter") + ", " + count(ext.sources().size(), "log source"));
        return true;
    }

    /** What every plugin meets: the bundled parsers (not the user's own) and the built-in format names. */
    private static PluginCheck.BuiltIns builtIns() {
        return new PluginCheck.BuiltIns(ParserList.withBuiltIn(UserParsersLoader.loadBundled()),
                Set.of(new JsonMessageFormatter().getName(), ToStringHighlighter.NAME, StackTraceHighlighter.NAME));
    }

    private static List<String> readSample(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = LogReaders.open(file, null)) {
            String line;
            while (lines.size() < PluginCheck.MAX_SAMPLE_LINES && (line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static String count(int n, String one) {
        return n + " " + one + (n == 1 ? "" : "s");
    }

    /** Best effort: on Windows the plugin's class loader may still hold the JAR open. */
    private static void delete(Path folder) {
        if (folder == null) return;
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    p.toFile().deleteOnExit();
                }
            });
        } catch (IOException e) {
            log.debug("Could not delete {}", folder, e);
        }
    }
}
