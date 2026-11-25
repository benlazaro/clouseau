package com.droidenx.clouseau.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Searches Maven Central via its Solr search API and downloads JARs from
 * repo1.maven.org. No credentials required.
 */
final class MavenCentralPluginRepository implements PluginRepository {

    private static final String SEARCH_URL   = "https://search.maven.org/solrsearch/select";
    private static final String DOWNLOAD_BASE = "https://repo1.maven.org/maven2";

    private final HttpClient http = PluginRepository.httpClient();

    @Override
    public String getName() { return "Maven Central"; }

    @Override
    public List<Asset> search(String query) throws IOException, InterruptedException {
        String url = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&rows=20&wt=json";

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

    private List<Asset> parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray  docs = root.getAsJsonObject("response").getAsJsonArray("docs");
        List<Asset> result = new ArrayList<>();
        for (JsonElement el : docs) {
            JsonObject doc = el.getAsJsonObject();
            String groupId    = doc.get("g").getAsString();
            String artifactId = doc.get("a").getAsString();
            String version    = doc.has("latestVersion")
                    ? doc.get("latestVersion").getAsString()
                    : doc.get("v").getAsString();
            String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version
                    + "/" + artifactId + "-" + version + ".jar";
            // "timestamp" is when Central got that version, in epoch milliseconds.
            java.time.Instant published = doc.has("timestamp")
                    ? java.time.Instant.ofEpochMilli(doc.get("timestamp").getAsLong()) : null;
            result.add(new Asset(groupId, artifactId, version, DOWNLOAD_BASE + "/" + path, -1, published));
        }
        return result;
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
