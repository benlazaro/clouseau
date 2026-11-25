package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.PluginCatalog.Entry;
import com.droidenx.clouseau.core.PluginCatalog.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginCatalogTest {

    @Test
    void namesFollowTheConvention() {
        assertThat(PluginCatalog.isPluginName("logfmt-clouseau-plugin")).isTrue();
        assertThat(PluginCatalog.isPluginName("loki-source-clouseau-plugin")).isTrue();
        assertThat(PluginCatalog.isPluginName("clouseau-loki-plugin")).isTrue();
        assertThat(PluginCatalog.isPluginName("Logfmt-Clouseau-Plugin")).isTrue();
        assertThat(PluginCatalog.isPluginName("clouseau-api")).isFalse();
        assertThat(PluginCatalog.isPluginName("clouseau-plugin")).isFalse();
        assertThat(PluginCatalog.isPluginName("-clouseau-plugin")).isFalse();
        assertThat(PluginCatalog.isPluginName("logfmt-plugin")).isFalse();
        assertThat(PluginCatalog.isPluginName("clouseau_2.12")).isFalse();
        assertThat(PluginCatalog.isPluginName(null)).isFalse();
    }

    @Test
    void officialMeansOurGroupAndOurPattern() {
        assertThat(PluginCatalog.isOfficial("com.droidenx.clouseau", "clouseau-loki-plugin")).isTrue();
        assertThat(PluginCatalog.isOfficial("com.example", "clouseau-loki-plugin")).isFalse();
        assertThat(PluginCatalog.isOfficial("com.droidenx.clouseau", "loki-clouseau-plugin")).isFalse();
    }

    @Test
    void parsesTheList() {
        List<Entry> list = PluginCatalog.parse("""
                {"plugins": [
                  {"groupId": "io.example", "artifactId": "logfmt-clouseau-plugin", "name": "logfmt",
                   "description": "Reads logfmt lines.", "provides": ["parser", "SOURCE", "rocket", "parser"],
                   "homepage": "https://example.io/logfmt"},
                  {"groupId": "io.example", "artifactId": "plain-clouseau-plugin"}
                ]}
                """);
        assertThat(list).hasSize(2);
        Entry first = list.get(0);
        assertThat(first.name()).isEqualTo("logfmt");
        assertThat(first.description()).isEqualTo("Reads logfmt lines.");
        assertThat(first.provides()).containsExactly(Kind.PARSER, Kind.SOURCE);
        assertThat(first.homepage()).isEqualTo("https://example.io/logfmt");
        assertThat(first.official()).isFalse();
        Entry second = list.get(1);
        assertThat(second.name()).isEqualTo("plain-clouseau-plugin");
        assertThat(second.description()).isEmpty();
        assertThat(second.provides()).isEmpty();
        assertThat(second.homepage()).isNull();
    }

    @Test
    void leavesOutEntriesThatBreakTheRules() {
        List<Entry> list = PluginCatalog.parse("""
                {"plugins": [
                  {"groupId": "io.example", "artifactId": "commons-lang3"},
                  {"groupId": "io.example", "artifactId": "clouseau-fake-plugin"},
                  {"artifactId": "nogroup-clouseau-plugin"},
                  "not an object",
                  {"groupId": "com.droidenx.clouseau", "artifactId": "clouseau-loki-plugin"}
                ]}
                """);
        assertThat(list).extracting(Entry::artifactId).containsExactly("clouseau-loki-plugin");
        assertThat(list.get(0).official()).isTrue();
    }

    @Test
    void anEmptyListIsFine() {
        assertThat(PluginCatalog.parse("{\"version\": 1, \"plugins\": []}")).isEmpty();
    }

    @Test
    void rejectsSomethingThatIsntAList() {
        assertThatThrownBy(() -> PluginCatalog.parse("<html>404</html>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PluginCatalog.parse("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PluginCatalog.parse("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void latestVersionPrefersReleaseThenLatestThenLastListed() {
        assertThat(PluginCatalog.latestVersion("""
                <metadata><versioning><latest>2.0.0-SNAPSHOT</latest><release>1.2.0</release>
                <versions><version>1.0.0</version><version>1.2.0</version></versions></versioning></metadata>
                """)).isEqualTo("1.2.0");
        assertThat(PluginCatalog.latestVersion("<metadata><versioning><latest>1.1</latest></versioning></metadata>"))
                .isEqualTo("1.1");
        assertThat(PluginCatalog.latestVersion("<versions><version>1.0</version><version>1.3</version></versions>"))
                .isEqualTo("1.3");
        assertThat(PluginCatalog.latestVersion("<metadata/>")).isNull();
        assertThat(PluginCatalog.latestVersion(null)).isNull();
    }

    @Test
    void findsInstalledVersionsByFileName() {
        List<String> files = List.of("logfmt-clouseau-plugin-1.2.0.jar", "logfmt-clouseau-plugin-extra-1.0.jar",
                "other-clouseau-plugin-3.0.jar", "LOGFMT-clouseau-plugin-1.3.0-rc.1.zip", "logfmt-clouseau-plugin.jar");
        assertThat(PluginCatalog.installedVersions("logfmt-clouseau-plugin", files))
                .containsExactly("1.2.0", "1.3.0-rc.1");
        assertThat(PluginCatalog.installedVersions("missing-clouseau-plugin", files)).isEmpty();
    }
}
