package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.PluginCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class PluginUpdatesTest {

    private static final Path DIR = Path.of("plugins");

    /** A repository that answers {@code latest} from a map, and fails for "boom". */
    private static final class FakeRepo implements PluginRepository {
        final String name;
        final Map<String, String> versions;
        final List<String> asked = new ArrayList<>();
        FakeRepo(String name, Map<String, String> versions) { this.name = name; this.versions = versions; }
        @Override public String getName() { return name; }
        @Override public List<Asset> search(String q) { return List.of(); }
        @Override public List<Asset> listPlugins() { return List.of(); }
        @Override public Asset latest(String g, String a) throws IOException {
            asked.add(g + ":" + a);
            if (a.startsWith("boom")) throw new IOException("down");
            String v = versions.get(a);
            return v == null ? null : new Asset(g, a, v, "u/" + a + "-" + v + ".jar", -1, null);
        }
        @Override public Path download(Asset a, Path d, LongConsumer c) { return null; }
    }

    private static PluginCatalog.Entry listed(String artifact) {
        return new PluginCatalog.Entry("io.example", artifact, artifact, "", List.of(), null);
    }

    @Test
    void findsANewerVersionInTheRepositoryAPluginCameFrom() throws Exception {
        var work = new FakeRepo("Work", Map.of("audit-clouseau-plugin", "2.1.0"));
        var central = new FakeRepo("Maven Central", Map.of("audit-clouseau-plugin", "9.9.9"));
        List<String> files = List.of("audit-clouseau-plugin-2.0.0.jar");

        var updates = PluginUpdates.find(files, DIR,
                Map.of("audit-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "com.acme")), List.of(central, work));

        assertThat(updates).hasSize(1);
        var u = updates.get(0);
        assertThat(u.installed()).isEqualTo("2.0.0");
        assertThat(u.latest().version()).isEqualTo("2.1.0");
        assertThat(u.repository()).isEqualTo("Work");
        assertThat(u.file()).isEqualTo(DIR.resolve("audit-clouseau-plugin-2.0.0.jar"));
        assertThat(work.asked).containsExactly("com.acme:audit-clouseau-plugin");
        assertThat(central.asked).isEmpty();
    }

    @Test
    void theSameOrAnOlderVersionIsNoUpdate() throws Exception {
        var repo = new FakeRepo("Work", Map.of("a-clouseau-plugin", "1.0.0", "b-clouseau-plugin", "1.0.0"));
        var updates = PluginUpdates.find(List.of("a-clouseau-plugin-1.0.0.jar", "b-clouseau-plugin-1.2.0.jar"), DIR,
                Map.of("a-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "g"),
                       "b-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "g")), List.of(repo));
        assertThat(updates).isEmpty();
    }

    @Test
    void aFailingOrMissingRepositoryOnlyLeavesThatPluginOut() throws Exception {
        var repo = new FakeRepo("Work", Map.of("ok-clouseau-plugin", "2.0"));
        var updates = PluginUpdates.find(
                List.of("ok-clouseau-plugin-1.0.jar", "boom-clouseau-plugin-1.0.jar", "gone-clouseau-plugin-1.0.jar"), DIR,
                Map.of("ok-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "g"),
                       "boom-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "g"),
                       "gone-clouseau-plugin", new AppPrefs.PluginOrigin("Removed Nexus", "g")), List.of(repo));
        assertThat(updates).extracting(PluginUpdates.Update::artifactId).containsExactly("ok-clouseau-plugin");
    }

    @Test
    void aPluginInstalledByHandIsCheckedOnCentralWhenTheListNamesIt() {
        int[] reads = {0};
        var candidates = PluginUpdates.candidates(
                List.of("known-clouseau-plugin-1.0.jar", "logfmt-clouseau-plugin-1.2.0.jar", "mystery.jar"), DIR,
                Map.of("known-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "com.acme")),
                () -> { reads[0]++; return List.of(listed("logfmt-clouseau-plugin"), listed("known-clouseau-plugin")); });

        assertThat(candidates).extracting(c -> c.artifactId() + "@" + c.repository() + "@" + c.installed())
                .containsExactly("known-clouseau-plugin@Work@1.0", "logfmt-clouseau-plugin@Maven Central@1.2.0");
        assertThat(reads[0]).isEqualTo(1);
    }

    @Test
    void theListIsntReadWhenEveryPluginHasAnOrigin() {
        var candidates = PluginUpdates.candidates(List.of("known-clouseau-plugin-1.0.jar", "notes.txt"), DIR,
                Map.of("known-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "g")),
                () -> { throw new AssertionError("read the list"); });
        assertThat(candidates).hasSize(1);
    }

    @Test
    void theNewestInstalledCopyIsTheOneChecked() {
        var candidates = PluginUpdates.candidates(List.of("x-clouseau-plugin-1.9.jar", "x-clouseau-plugin-1.10.jar"), DIR,
                Map.of("x-clouseau-plugin", new AppPrefs.PluginOrigin("Work", "g")), List::of);
        assertThat(candidates).extracting(PluginUpdates.Candidate::installed).containsExactly("1.10");
        assertThat(candidates.get(0).file()).isEqualTo(DIR.resolve("x-clouseau-plugin-1.10.jar"));
    }
}
