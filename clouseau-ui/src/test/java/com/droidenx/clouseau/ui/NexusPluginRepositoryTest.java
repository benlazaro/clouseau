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

    /** What the fake server answers: the keyword search, then the name searches. */
    private volatile String keywordBody = "{\"items\":[]}";
    private volatile String nameBody    = "{\"items\":[]}";
    private volatile int    nameStatus  = 200;
    /** Answers for particular name searches, by the name asked for; others get {@link #nameBody}. */
    private final java.util.Map<String, String> namedBodies = new java.util.concurrent.ConcurrentHashMap<>();
    /** Answers a name starting with {@code *} with HTTP 400, as some servers do. */
    private volatile boolean refuseLeadingWildcard;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/nexus/service/rest/v1/search/assets", ex -> {
            String query = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            queries.add(query);
            boolean keyword = query.startsWith("q=");
            String name = keyword ? null : query.substring("name=".length()).replaceFirst("&.*", "");
            int status = keyword ? 200 : refuseLeadingWildcard && name.startsWith("*") ? 400 : nameStatus;
            byte[] body = (keyword ? keywordBody : namedBodies.getOrDefault(name, nameBody)).getBytes(StandardCharsets.UTF_8);
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
                                     + item("logfmt-clouseau-plugin", "2.1", null, null) + "]}";

        List<PluginRepository.Asset> found = repo(null).search("fmt");

        // Then every version of each artifact found, by its exact name.
        assertThat(queries).containsExactly("q=fmt", "name=*fmt*", "name=clouseau-logfmt-plugin", "name=logfmt-clouseau-plugin");
        assertThat(found).extracting(PluginRepository.Asset::artifactId)
                .containsExactly("clouseau-logfmt-plugin", "logfmt-clouseau-plugin");
        assertThat(found.get(0).published()).isEqualTo(Instant.parse("2026-03-01T10:15:30.123Z"));
        assertThat(found.get(1).published()).isNull();
    }

    @Test
    void aSearchFindsOnlyPlugins() throws Exception {
        keywordBody = "{\"items\":[" + item("logfmt-clouseau-plugin", "1.0", null, null) + ","
                                     + item("logfmt-core", "3.0", null, null) + ","
                                     + item("clouseau-api", "0.4.0", null, null) + "]}";

        List<PluginRepository.Asset> found = repo(null).search("logfmt");

        assertThat(found).extracting(PluginRepository.Asset::artifactId).containsExactly("logfmt-clouseau-plugin");
        // Nor is every version asked for of what isn't a plugin.
        assertThat(queries).doesNotContain("name=logfmt-core", "name=clouseau-api");
    }

    @Test
    void keepsTheKeywordResultsWhenTheServerRefusesTheWildcardSearch() throws Exception {
        keywordBody = "{\"items\":[" + item("clouseau-logfmt-plugin", "1.0", null, null) + "]}";
        nameStatus  = 400;

        assertThat(repo(null).search("logfmt")).hasSize(1);
    }

    @Test
    void aServerThatRefusesALeadingWildcardIsAskedForNamesThatStartWithTheText() throws Exception {
        refuseLeadingWildcard = true;
        namedBodies.put("drivers-*", "{\"items\":[" + item("drivers-clouseau-plugin", "1.0.0", null, null) + "]}");

        List<PluginRepository.Asset> found = repo(null).search("drivers-");

        assertThat(queries).startsWith("q=drivers-", "name=*drivers-*", "name=drivers-*");
        assertThat(found).extracting(PluginRepository.Asset::artifactId).containsExactly("drivers-clouseau-plugin");
    }

    @Test
    void everyVersionOfWhatASearchFindsIsListed() throws Exception {
        // The keyword search returned only the newest; the exact name search has them all,
        // and one of another group by the same name, which is left out.
        refuseLeadingWildcard = true;
        keywordBody = "{\"items\":[" + item("drivers-clouseau-plugin", "1.3.0", null, null) + "]}";
        String other = item("drivers-clouseau-plugin", "9.0", null, null).replace("com.example", "org.other");
        namedBodies.put("drivers-clouseau-plugin", "{\"items\":["
                + item("drivers-clouseau-plugin", "1.0.0", null, null) + ","
                + item("drivers-clouseau-plugin", "1.1.0", null, null) + ","
                + item("drivers-clouseau-plugin", "1.2.0", null, null) + ","
                + item("drivers-clouseau-plugin", "1.3.0", null, null) + "," + other + "]}");

        List<PluginRepository.Asset> found = repo(null).search("drivers-");

        assertThat(found).extracting(PluginRepository.Asset::version)
                .containsExactlyInAnyOrder("1.0.0", "1.1.0", "1.2.0", "1.3.0");
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
                    ? "{\"items\":[" + item("second-clouseau-plugin", "1.0", null, null) + "],\"continuationToken\":null}"
                    : "{\"items\":[" + item("first-clouseau-plugin", "1.0", null, null) + "],\"continuationToken\":\"p2\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
        });

        assertThat(repo(null).search("plugin")).extracting(PluginRepository.Asset::artifactId)
                .containsExactly("first-clouseau-plugin", "second-clouseau-plugin");
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

        assertThat(queries).containsExactly("q=clouseau", "name=*-clouseau-plugin", "name=clouseau-*-plugin",
                "name=logfmt-clouseau-plugin", "name=clouseau-utils", "name=loki-clouseau-plugin");
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

    @Test
    void latestPicksTheNewestOfThatGroupAndName() throws Exception {
        keywordBody = "{\"items\":[]}";
        nameBody = "{\"items\":[" + item("audit-clouseau-plugin", "1.2.0", null, null) + ","
                + item("audit-clouseau-plugin", "1.10.0", null, null) + ","
                + item("audit-clouseau-plugin-extras", "9.0", null, null) + "]}";

        var latest = repo(null).latest("com.example", "audit-clouseau-plugin");

        assertThat(queries).containsExactly("name=audit-clouseau-plugin");
        assertThat(latest.version()).isEqualTo("1.10.0");
        assertThat(repo(null).latest("org.other", "audit-clouseau-plugin")).isNull();
    }
}
