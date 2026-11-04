package com.droidenx.clouseau.runtime;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real example plugin JAR (built by :clouseau-example-plugin) from a plugins
 * folder through PF4J, the same way the app does at startup.
 */
class ClouseauPluginManagerTest {

    private static final String PLUGIN_ID = "clouseau-example-logfmt";

    @TempDir
    Path pluginsDir;

    private ClouseauPluginManager manager;

    @BeforeEach
    void installExamplePlugin() throws IOException {
        Path jar = Path.of(System.getProperty("clouseau.examplePlugin.jar"));
        Files.copy(jar, pluginsDir.resolve(jar.getFileName()));
        manager = new ClouseauPluginManager(pluginsDir);
        manager.loadAll();
    }

    @AfterEach
    void stop() {
        manager.stopAll();
    }

    private LogParser logfmtParser() {
        List<LogParser> parsers = manager.getExtensions(LogParser.class);
        assertThat(parsers).hasSize(1);
        return parsers.get(0);
    }

    @Test
    void discoversTheParserFromTheJar() {
        LogParser parser = logfmtParser();

        assertThat(parser.getName()).isEqualTo("logfmt (example plugin)");
        // Loaded by the plugin's own class loader, not from the test classpath.
        assertThat(parser.getClass().getClassLoader()).isNotSameAs(LogParser.class.getClassLoader());
    }

    @Test
    void parserWorksThroughTheApiInterface() {
        LogParser parser = logfmtParser();
        String line = "time=2026-03-27T08:00:00Z level=error msg=\"payment failed\" order=ORD-7";

        assertThat(parser.canParse(line)).isTrue();
        LogEntry entry = parser.parse(line);
        assertThat(entry.level()).isEqualTo(LogEntry.LogLevel.ERROR);
        assertThat(entry.message()).isEqualTo("payment failed");
        assertThat(entry.fields()).containsEntry("order", "ORD-7");
    }

    @Test
    void pluginManagerDescribesThePlugin() {
        assertThat(manager.getPluginInfos())
                .singleElement()
                .satisfies(info -> {
                    assertThat(info.id()).isEqualTo(PLUGIN_ID);
                    assertThat(info.version()).isEqualTo("1.0.0");
                    assertThat(info.types()).isEqualTo("Parser");
                    assertThat(info.enabled()).isTrue();
                });
        assertThat(manager.getExtensionDetails(PLUGIN_ID))
                .anySatisfy(line -> assertThat(line).contains("logfmt (example plugin)"));
    }
}
