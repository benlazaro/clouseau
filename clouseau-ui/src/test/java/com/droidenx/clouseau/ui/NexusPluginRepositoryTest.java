package com.droidenx.clouseau.ui;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NexusPluginRepositoryTest {

    private HttpServer server;
    private String base;
    private final List<String> queries = new CopyOnWriteArrayList<>();

    /** What the fake server answers: the keyword search, then the wildcard name search. */
    private volatile String keywordBody = "{\"items\":[]}";
    private volatile String nameBody    = "{\"items\":[]}";
    private volatile int    nameStatus  = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/nexus/service/rest/v1/search/assets", ex -> {
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            queries.add(query);
            boolean keyword = query.startsWith("q=");
            int status = keyword ? 200 : nameStatus;
            byte[] body = (keyword ? keywordBody : nameBody).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/nexus/";
    }

    @AfterEach
    void stop() { server.stop(0); }

    private NexusPluginRepository repo(String repository) {
        return new NexusPluginRepository(new AppPrefs.PluginRepo(
                "Work", "nexus3", base, repository, "none", null, null));
    }

    private static String item(String artifactId, String version, String classifier, String lastModified) {
        String path = "com/example/" + artifactId + "/" + version + "/" + artifactId + "-" + version
                + (classifier != null ? "-" + classifier : "") + ".jar";
        return "{\"path\":\"" + path + "\",\"downloadUrl\":\"https://nexus/repository/r/" + path + "\","
                + "\"contentType\":\"application/java-archive\",\"fileSize\":2048,"
                + (lastModified != null ? "\"lastModified\":\"" + lastModified + "\"," : "")
                + "\"maven2\":{\"groupId\":\"com.example\",\"artifactId\":\"" + artifactId + "\","
                + "\"version\":\"" + version + "\""
                + (classifier != null ? ",\"classifier\":\"" + classifier + "\"" : "") + "}}";
    }

    @Test
    void searchesByKeywordAndByPartOfTheName() throws Exception {
        keywordBody = "{\"items\":[" + item("clouseau-logfmt-plugin", "1.0", null, "2026-03-01T10:15:30.123+00:00") + "]}";
        nameBody    = "{\"items\":[" + item("clouseau-logfmt-plugin", "1.0", null, null) + ","
                                     + item("logfmt-extras", "2.1", null, null) + "]}";

        List<PluginRepository.Asset> found = repo(null).search("fmt");

        assertThat(queries).containsExactly("q=fmt", "name=*fmt*");
        assertThat(found).extracting(PluginRepository.Asset::artifactId)
                .containsExactly("clouseau-logfmt-plugin", "logfmt-extras");
        assertThat(found.get(0).published()).isEqualTo(Instant.parse("2026-03-01T10:15:30.123Z"));
        assertThat(found.get(1).published()).isNull();
    }

    @Test
    void keepsTheKeywordResultsWhenTheServerRefusesTheWildcardSearch() throws Exception {
        keywordBody = "{\"items\":[" + item("clouseau-logfmt-plugin", "1.0", null, null) + "]}";
        nameStatus  = 400;

        assertThat(repo(null).search("logfmt")).hasSize(1);
    }

    @Test
    void aFailedKeywordSearchIsAnError() {
        server.removeContext("/nexus/service/rest/v1/search/assets");
        server.createContext("/nexus/service/rest/v1/search/assets", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });

        assertThatThrownBy(() -> repo(null).search("logfmt")).hasMessageContaining("HTTP 401");
    }

    @Test
    void searchesOneRepositoryWhenNamed() throws Exception {
        repo(" maven-releases ").search("logfmt");

        assertThat(queries).allMatch(q -> q.endsWith("&repository=maven-releases"));
    }

    @Test
    void typedWildcardsAreSentAsTheyAre() throws Exception {
        repo(null).search("clouseau-*");

        assertThat(queries).containsExactly("q=clouseau-*");
    }

    @Test
    void readsFurtherPages() throws Exception {
        server.removeContext("/nexus/service/rest/v1/search/assets");
        server.createContext("/nexus/service/rest/v1/search/assets", ex -> {
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            queries.add(query);
            String body = !query.startsWith("q=") ? "{\"items\":[]}"
                    : query.contains("continuationToken=p2")
                    ? "{\"items\":[" + item("second", "1.0", null, null) + "],\"continuationToken\":null}"
                    : "{\"items\":[" + item("first", "1.0", null, null) + "],\"continuationToken\":\"p2\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        });

        assertThat(repo(null).search("plugin")).extracting(PluginRepository.Asset::artifactId)
                .containsExactly("first", "second");
    }

    @Test
    void leavesOutClassifiedJarsAndOtherFiles() {
        String pom = "{\"path\":\"com/example/a/1.0/a-1.0.pom\",\"downloadUrl\":\"https://x/a-1.0.pom\","
                + "\"contentType\":\"application/xml\"}";
        var root = JsonParser.parseString("{\"items\":[" + item("a", "1.0", null, null) + ","
                + item("a", "1.0", "sources", null) + "," + pom + "]}").getAsJsonObject();

        assertThat(NexusPluginRepository.parseItems(root)).singleElement()
                .satisfies(a -> assertThat(a.downloadUrl()).endsWith("a-1.0.jar"));
    }

    @Test
    void theUrlIsTheServersAddress() {
        assertThat(NexusPluginRepository.searchUrl(" https://nexus.example.com// ", "", "q", "a b", null))
                .isEqualTo("https://nexus.example.com/service/rest/v1/search/assets?q=a+b");
    }

    @Test
    void listsPluginsByNameNewestVersionOfEach() throws Exception {
        keywordBody = "{\"items\":[" + item("logfmt-clouseau-plugin", "1.2.0", null, null) + ","
                + item("logfmt-clouseau-plugin", "1.10.0", null, null) + ","
                + item("clouseau-utils", "3.0", null, null) + ","
                + item("logfmt-clouseau-plugin", "1.10.0", "sources", null) + "]}";
        nameBody    = "{\"items\":[" + item("loki-clouseau-plugin", "0.1", null, null) + "]}";

        List<PluginRepository.Asset> list = repo(null).listPlugins();

        assertThat(queries).containsExactly("q=clouseau", "name=*-clouseau-plugin", "name=clouseau-*-plugin");
        assertThat(list).extracting(a -> a.artifactId() + ":" + a.version())
                .containsExactly("logfmt-clouseau-plugin:1.10.0", "loki-clouseau-plugin:0.1");
    }

    @Test
    void listingKeepsTheKeywordResultsWhenTheServerRefusesWildcards() throws Exception {
        keywordBody = "{\"items\":[" + item("logfmt-clouseau-plugin", "1.0", null, null) + "]}";
        nameStatus  = 400;

        assertThat(repo(null).listPlugins()).extracting(PluginRepository.Asset::artifactId)
                .containsExactly("logfmt-clouseau-plugin");
    }
}
