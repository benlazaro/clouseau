package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.PluginCatalog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.LongConsumer;

/**
 * Maven Central: searches it through its Solr search API and downloads JARs from
 * repo1.maven.org. No credentials required.
 * <p>
 * Central's search index lags (a plugin published this month may not be in it) and refuses a
 * leading wildcard, so it can't find plugins by name. {@link #listPlugins} reads the project's
 * plugin list instead and asks repo1 for each plugin's latest version, which is always current
 * (G-15, D-32).
 */
@Slf4j
final class MavenCentralPluginRepository implements PluginRepository {

    /** Set to a URL or a file path to read another plugin list, such as a local copy while editing it. */
    static final String CATALOG_PROPERTY = "clouseau.pluginCatalog";

    private final String searchUrl;
    private final String downloadBase;
    private final String catalogUrl;
    private final HttpClient http = PluginRepository.httpClient();

    MavenCentralPluginRepository() {
        this("https://search.maven.org/solrsearch/select", "https://repo1.maven.org/maven2",
                System.getProperty(CATALOG_PROPERTY, PluginCatalog.CATALOG_URL));
    }

    /** For tests: a fake server's addresses. */
    MavenCentralPluginRepository(String searchUrl, String downloadBase, String catalogUrl) {
        this.searchUrl    = searchUrl;
        this.downloadBase = downloadBase.endsWith("/") ? downloadBase.substring(0, downloadBase.length() - 1) : downloadBase;
        this.catalogUrl   = catalogUrl;
    }

    @Override
    public String getName() { return "Maven Central"; }

    /**
     * Searches Central's index for {@code query}, keeping only plugins (artifacts named by the
     * convention, D-32): any other library that matches is left out. Asks for more rows than it
     * usually shows, since most matches aren't plugins.
     */
    @Override
    public List<Asset> search(String query) throws IOException, InterruptedException {
        String url = searchUrl + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&rows=200&wt=json";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(SEARCH_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("Maven Central search returned HTTP " + resp.statusCode());

        return parseResponse(resp.body());
    }

    /** The plugins in one Solr response; other artifacts are left out. */
    private List<Asset> parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray  docs = root.getAsJsonObject("response").getAsJsonArray("docs");
        List<Asset> result = new ArrayList<>();
        for (JsonElement el : docs) {
            JsonObject doc = el.getAsJsonObject();
            String groupId    = doc.get("g").getAsString();
            String artifactId = doc.get("a").getAsString();
            if (!com.droidenx.clouseau.core.PluginCatalog.isPluginName(artifactId)) continue;
            String version    = doc.has("latestVersion")
                    ? doc.get("latestVersion").getAsString()
                    : doc.get("v").getAsString();
            // "timestamp" is when Central got that version, in epoch milliseconds.
            Instant published = doc.has("timestamp") ? Instant.ofEpochMilli(doc.get("timestamp").getAsLong()) : null;
            result.add(new Asset(groupId, artifactId, version, jarUrl(groupId, artifactId, version), -1, published));
        }
        return result;
    }

    private String artifactDir(String groupId, String artifactId) {
        return downloadBase + "/" + groupId.replace('.', '/') + "/" + artifactId;
    }

    private String jarUrl(String groupId, String artifactId, String version) {
        return artifactDir(groupId, artifactId) + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    /**
     * The plugins on the plugin list, each at its latest version on Central. The plugins are
     * asked about at once; one that Central doesn't have (yet) is left out and logged.
     *
     * @throws IOException if the list can't be read, or Central answered for none of its plugins
     */
    @Override
    public List<Asset> listPlugins() throws IOException, InterruptedException {
        List<PluginCatalog.Entry> entries = catalog();
        log.info("Plugin list has {} plugin(s)", entries.size());

        List<CompletableFuture<Asset>> pending = new ArrayList<>();
        for (PluginCatalog.Entry e : entries) pending.add(latest(e.groupId(), e.artifactId(), e));
        List<Asset> out = new ArrayList<>();
        Throwable lastFailure = null;
        try {
            for (int i = 0; i < pending.size(); i++) {
                try {
                    out.add(pending.get(i).get());
                } catch (ExecutionException ex) {
                    lastFailure = ex.getCause();
                    PluginCatalog.Entry e = entries.get(i);
                    log.warn("Skipping {}:{} from the plugin list: {}", e.groupId(), e.artifactId(), ex.getCause().getMessage());
                }
            }
        } catch (InterruptedException ie) {
            pending.forEach(f -> f.cancel(true));
            throw ie;
        }
        if (out.isEmpty() && lastFailure != null)
            throw lastFailure instanceof IOException io ? io : new IOException(lastFailure.getMessage(), lastFailure);
        return out;
    }

    /** The plugins on the plugin list. */
    List<PluginCatalog.Entry> catalog() throws IOException, InterruptedException {
        try {
            return PluginCatalog.parse(readCatalog());
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * The latest version of {@code groupId:artifactId} on Central, from its {@code maven-metadata.xml};
     * null if Central doesn't have it.
     */
    @Override
    public Asset latest(String groupId, String artifactId) throws IOException, InterruptedException {
        try {
            return latest(groupId, artifactId, null).get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof NotOnCentral) return null;
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause().getMessage(), e.getCause());
        }
    }

    /** Central has no {@code maven-metadata.xml} for the artifact. */
    private static final class NotOnCentral extends IOException {
        NotOnCentral(String message) { super(message); }
    }

    /** The plugin list's text, from {@link #catalogUrl}: an http(s) URL, a {@code file:} URL or a path. */
    private String readCatalog() throws IOException, InterruptedException {
        String where = catalogUrl.strip();
        if (!where.startsWith("http://") && !where.startsWith("https://")) {
            Path file = where.startsWith("file:") ? Path.of(URI.create(where)) : Path.of(where);
            log.info("Reading the plugin list from {}", file);
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        log.info("Reading the plugin list from {}", where);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(where)).timeout(SEARCH_TIMEOUT).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200)
            throw new IOException("The plugin list returned HTTP " + resp.statusCode());
        return resp.body();
    }

    /**
     * {@code groupId:artifactId} at its latest version (with {@code entry}, its place on the list, or null):
     * the version from its {@code maven-metadata.xml}, then
     * the JAR's size and date from a HEAD request (a JAR that doesn't answer still lists, without them).
     */
    private CompletableFuture<Asset> latest(String groupId, String artifactId, PluginCatalog.Entry entry) {
        HttpRequest meta = HttpRequest.newBuilder()
                .uri(URI.create(artifactDir(groupId, artifactId) + "/maven-metadata.xml"))
                .timeout(SEARCH_TIMEOUT).GET().build();
        return http.sendAsync(meta, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(resp -> {
                    String version = resp.statusCode() == 200 ? PluginCatalog.latestVersion(resp.body()) : null;
                    if (version == null)
                        return CompletableFuture.failedFuture(resp.statusCode() == 404
                                ? new NotOnCentral("Maven Central doesn't have " + groupId + ":" + artifactId)
                                : new IOException("Maven Central returned HTTP " + resp.statusCode() + " for " + artifactId));
                    String jar = jarUrl(groupId, artifactId, version);
                    HttpRequest head = HttpRequest.newBuilder().uri(URI.create(jar)).timeout(SEARCH_TIMEOUT)
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                    return http.sendAsync(head, HttpResponse.BodyHandlers.discarding())
                            .handle((h, failure) -> {
                                long size = -1;
                                Instant published = null;
                                if (h != null && h.statusCode() == 200) {
                                    size = h.headers().firstValueAsLong("Content-Length").orElse(-1);
                                    published = h.headers().firstValue("Last-Modified")
                                            .map(MavenCentralPluginRepository::httpDate).orElse(null);
                                }
                                return new Asset(groupId, artifactId, version, jar, size, published, entry);
                            });
                });
    }

    private static Instant httpDate(String text) {
        try {
            return ZonedDateTime.parse(text.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Path download(Asset asset, Path destDir, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(asset.downloadUrl()))
                .GET()
                .build();

        String filename = asset.artifactId() + "-" + asset.version() + ".jar";
        Path dest = destDir.resolve(filename);

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200)
            throw new IOException("Download returned HTTP " + resp.statusCode());

        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(dest)) {
            byte[] buf   = new byte[8192];
            long   total = 0;
            int    n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                onBytesRead.accept(total);
            }
        }
        return dest;
    }
}
