package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.PluginCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenCentralPluginRepositoryTest {

    private HttpServer server;
    private String base;
    /** Path → body; a path that isn't here answers 404. */
    private final Map<String, String> files = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String body = files.get(ex.getRequestURI().getPath());
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Last-Modified", "Tue, 03 Nov 2026 10:15:30 GMT");
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Content-Length", Integer.toString(bytes.length));
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private MavenCentralPluginRepository repo() {
        return new MavenCentralPluginRepository(base + "/search", base + "/maven2/", base + "/plugins.json");
    }

    private void publish(String group, String artifact, String version, String jar) {
        String dir = "/maven2/" + group.replace('.', '/') + "/" + artifact;
        files.put(dir + "/maven-metadata.xml", "<metadata><versioning><release>" + version
                + "</release></versioning></metadata>");
        if (jar != null) files.put(dir + "/" + version + "/" + artifact + "-" + version + ".jar", jar);
    }

    @Test
    void listsEachPluginAtItsLatestVersion() throws Exception {
        files.put("/plugins.json", """
                {"plugins": [
                  {"groupId": "io.example", "artifactId": "logfmt-clouseau-plugin", "name": "logfmt",
                   "provides": ["parser"]},
                  {"groupId": "io.example", "artifactId": "loki-clouseau-plugin", "name": "Loki"}
                ]}
                """);
        publish("io.example", "logfmt-clouseau-plugin", "1.2.0", "0123456789");
        publish("io.example", "loki-clouseau-plugin", "0.4.0", "abc");

        List<PluginRepository.Asset> list = repo().listPlugins();

        assertThat(list).extracting(PluginRepository.Asset::artifactId)
                .containsExactly("logfmt-clouseau-plugin", "loki-clouseau-plugin");
        PluginRepository.Asset logfmt = list.get(0);
        assertThat(logfmt.version()).isEqualTo("1.2.0");
        assertThat(logfmt.sizeBytes()).isEqualTo(10);
        assertThat(logfmt.published()).isEqualTo(Instant.parse("2026-11-03T10:15:30Z"));
        assertThat(logfmt.downloadUrl())
                .isEqualTo(base + "/maven2/io/example/logfmt-clouseau-plugin/1.2.0/logfmt-clouseau-plugin-1.2.0.jar");
        assertThat(logfmt.listing().provides()).containsExactly(PluginCatalog.Kind.PARSER);
        assertThat(logfmt.displayName()).isEqualTo("logfmt");
    }

    @Test
    void aPluginCentralDoesntHaveIsLeftOut() throws Exception {
        files.put("/plugins.json", """
                {"plugins": [
                  {"groupId": "io.example", "artifactId": "gone-clouseau-plugin"},
                  {"groupId": "io.example", "artifactId": "here-clouseau-plugin"}
                ]}
                """);
        publish("io.example", "here-clouseau-plugin", "1.0", null);    // no JAR to HEAD: no size or date

        List<PluginRepository.Asset> list = repo().listPlugins();

        assertThat(list).extracting(PluginRepository.Asset::artifactId).containsExactly("here-clouseau-plugin");
        assertThat(list.get(0).sizeBytes()).isEqualTo(-1);
        assertThat(list.get(0).published()).isNull();
    }

    @Test
    void failsWhenNoListedPluginCanBeFound() {
        files.put("/plugins.json", "{\"plugins\": [{\"groupId\": \"io.example\", \"artifactId\": \"gone-clouseau-plugin\"}]}");
        assertThatThrownBy(() -> repo().listPlugins()).isInstanceOf(IOException.class)
                .hasMessageContaining("gone-clouseau-plugin");
    }

    @Test
    void anEmptyListIsNoPlugins() throws Exception {
        files.put("/plugins.json", "{\"plugins\": []}");
        assertThat(repo().listPlugins()).isEmpty();
    }

    @Test
    void aMissingOrBrokenListIsAnError() {
        assertThatThrownBy(() -> repo().listPlugins()).isInstanceOf(IOException.class).hasMessageContaining("404");
        files.put("/plugins.json", "<html>oops</html>");
        assertThatThrownBy(() -> repo().listPlugins()).isInstanceOf(IOException.class);
    }

    @Test
    void readsTheListFromAFile(@TempDir Path dir) throws Exception {
        Path list = dir.resolve("plugins.json");
        Files.writeString(list, "{\"plugins\": [{\"groupId\": \"io.example\", \"artifactId\": \"here-clouseau-plugin\"}]}");
        publish("io.example", "here-clouseau-plugin", "2.0", "x");

        var repo = new MavenCentralPluginRepository(base + "/search", base + "/maven2", list.toString());

        assertThat(repo.listPlugins()).extracting(PluginRepository.Asset::version).containsExactly("2.0");
    }

    @Test
    void latestFindsOnePluginsNewestVersion() throws Exception {
        publish("io.example", "logfmt-clouseau-plugin", "1.3.0", "abc");
        var latest = repo().latest("io.example", "logfmt-clouseau-plugin");
        assertThat(latest.version()).isEqualTo("1.3.0");
        assertThat(latest.listing()).isNull();
        assertThat(repo().latest("io.example", "gone-clouseau-plugin")).isNull();
    }
}
