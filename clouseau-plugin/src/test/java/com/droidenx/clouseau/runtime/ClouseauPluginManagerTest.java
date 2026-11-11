package com.droidenx.clouseau.runtime;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSource;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.SourceSetting;
import com.droidenx.clouseau.core.SourceSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                    assertThat(info.name()).isEqualTo("logfmt example");
                    assertThat(info.description()).startsWith("Example plugin");
                    assertThat(info.provider()).isEqualTo("Clouseau");
                    assertThat(info.failed()).isFalse();
                    assertThat(info.version()).isEqualTo("1.0.0");
                    assertThat(info.types()).isEqualTo("Parser, Source");
                    assertThat(info.enabled()).isTrue();
                });
        assertThat(manager.getExtensionDetails(PLUGIN_ID))
                .anySatisfy(line -> assertThat(line).contains("logfmt (example plugin)"))
                .anySatisfy(line -> assertThat(line).startsWith("Source:").contains("Demo log stream"));
    }

    @Test
    void sourceProviderCreatesAWorkingSource() throws Exception {
        List<LogSourceProvider> providers = manager.getExtensions(LogSourceProvider.class);
        assertThat(providers).hasSize(1);
        LogSourceProvider provider = providers.get(0);

        assertThat(provider.settings()).extracting(SourceSetting::key)
                .containsExactly("service", "interval", "mix", "count");

        // The same path the app takes: check the form's values, create, open, parse.
        Map<String, String> entered = new HashMap<>();
        provider.settings().forEach(s -> entered.put(s.key(), s.defaultValue()));
        entered.put("interval", "10");
        entered.put("count", "2");
        SourceSettings.Result checked = SourceSettings.check(provider.settings(), entered);
        assertThat(checked.ok()).isTrue();

        LogSource source = provider.create(checked.values());
        List<String> lines = new ArrayList<>();
        source.open(lines::add);

        assertThat(lines).hasSize(2);
        LogParser parser = logfmtParser();
        assertThat(parser.canParse(lines.get(0))).isTrue();
        assertThat(parser.parse(lines.get(0)).logger()).isEqualTo("checkout");
    }

    @Test
    void aFileThatIsNotAJarIsListedAsFailedWithTheReason() throws IOException {
        Files.writeString(pluginsDir.resolve("broken-0.1.jar"), "not a jar");
        manager.refresh();

        assertThat(manager.getPluginInfos()).hasSize(2).last().satisfies(info -> {
            assertThat(info.name()).isEqualTo("broken-0.1.jar");
            assertThat(info.failed()).isTrue();
            assertThat(info.enabled()).isFalse();
            assertThat(info.error()).contains("Not a JAR");
        });
    }

    @Test
    void aJarWithoutAPluginIdSaysItIsNotAPlugin() throws IOException {
        Path jar = pluginsDir.resolve("library.jar");
        java.util.jar.Manifest m = new java.util.jar.Manifest();
        m.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), m)) { /* empty */ }
        manager.refresh();

        assertThat(manager.getPluginInfos()).last().satisfies(info -> {
            assertThat(info.failed()).isTrue();
            assertThat(info.error()).contains("no Plugin-Id");
        });
        assertThat(manager.deleteUnloadedFile(jar)).isTrue();
        assertThat(manager.getPluginInfos()).hasSize(1);
    }

    @Test
    void anOtherFileIsIgnoredAndTheFolderStampChangesWithTheFolder() throws IOException {
        String before = manager.folderStamp();
        Files.writeString(pluginsDir.resolve("README.txt"), "hello");
        assertThat(manager.getPluginInfos()).hasSize(1);
        assertThat(manager.folderStamp()).isNotEqualTo(before);
    }

    @Test
    void idsReadAsNames() {
        assertThat(ClouseauPluginManager.readableId("clouseau-loki-source-plugin")).isEqualTo("Loki source");
        assertThat(ClouseauPluginManager.readableId("my_parser")).isEqualTo("My parser");
        assertThat(ClouseauPluginManager.readableId("logfmt")).isEqualTo("Logfmt");
    }
}
