package com.droidenx.clouseau.ui;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Searches and downloads plugin JARs from a Nexus Repository Manager 3 instance
 * via its REST API ({@code /service/rest/v1/search/assets}). The configured URL is the
 * server's address ({@code https://nexus.example.com}), not a repository's.
 */
@Slf4j
final class NexusPluginRepository implements PluginRepository {

    /** Pages (of 50 items) read per search at most, so a broad word can't page through a whole server. */
    static final int MAX_PAGES = 5;

    private final AppPrefs.PluginRepo config;
    private final HttpClient          http = PluginRepository.httpClient();

    NexusPluginRepository(AppPrefs.PluginRepo config) {
        this.config = config;
    }

    @Override
    public String getName() { return config.name(); }

    /**
     * Finds JARs whose coordinates contain {@code query}. Two searches, merged: Nexus's keyword
     * search, which matches whole words of the group, name and version ({@code logfmt} finds
     * {@code logfmt-clouseau-plugin}), then the name with wildcards around it, which also
     * matches part of a word ({@code fmt}). A server that refuses the wildcard search (not every
     * one allows a leading wildcard) still answers the keyword one.
     */
    @Override
    public List<Asset> search(String query) throws IOException, InterruptedException {
        String q = query.strip();
        Map<String, Asset> found = new LinkedHashMap<>();
        collect("q", q, found, true);
        if (!q.isEmpty() && q.chars().noneMatch(c -> c == '*' || c == '?' || Character.isWhitespace(c)))
            collect("name", "*" + q + "*", found, false);

        List<Asset> results = new ArrayList<>(found.values());
        log.info("Nexus search returned {} JAR asset(s)", results.size());
        if (results.isEmpty() && hasAuthConfigured()) {
            log.warn("Nexus returned 0 results but credentials are configured. If the plugin "
                    + "should be there, verify the credentials: Nexus silently falls back to "
                    + "anonymous access when authentication fails.");
        }
        return results;
    }

    /**
     * The Clouseau plugins on the server, by name (G-15, D-32): artifacts named
     * {@code <name>-clouseau-plugin} or {@code clouseau-<name>-plugin}, the latest version of
     * each. Nexus's search is current and takes wildcards, so in-house plugins list as soon as
     * they're deployed. The keyword search ({@code clouseau}) always runs; the two name searches
     * add what it misses, on servers that allow a leading wildcard.
     */
    @Override
    public List<Asset> listPlugins() throws IOException, InterruptedException {
        Map<String, Asset> found = new LinkedHashMap<>();
        collect("q", "clouseau", found, true);
        collect("name", "*-clouseau-plugin", found, false);
        collect("name", "clouseau-*-plugin", found, false);
        List<Asset> latest = latestOfEach(found.values());
        log.info("Nexus has {} Clouseau plugin(s)", latest.size());
        return latest;
    }

    /** The conventionally named plugins among {@code assets}, newest version of each, in the order first seen. */
    static List<Asset> latestOfEach(java.util.Collection<Asset> assets) {
        Map<String, Asset> newest = new LinkedHashMap<>();
        for (Asset a : assets) {
            if (!com.droidenx.clouseau.core.PluginCatalog.isPluginName(a.artifactId())) continue;
            newest.merge(a.groupId() + ":" + a.artifactId(), a,
                    (was, now) -> BrowsePluginsPanel.compareVersions(now.version(), was.version()) > 0 ? now : was);
        }
        return new ArrayList<>(newest.values());
    }

    /** Reads every page (up to {@link #MAX_PAGES}) of one search into {@code found}, keyed by download URL. */
    private void collect(String param, String value, Map<String, Asset> found, boolean required)
            throws IOException, InterruptedException {
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = searchUrl(config.url(), config.repository(), param, value, token);
            log.info("Nexus search: GET {}", url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(SEARCH_TIMEOUT)
                    .header("Authorization", buildAuthHeader())
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Nexus search response: HTTP {}", resp.statusCode());
            if (resp.statusCode() != 200) {
                if (required)
                    throw new IOException("Nexus search returned HTTP " + resp.statusCode()
                            + " for " + config.name());
                log.info("Nexus refused the {} search (HTTP {}); keeping the other results", param, resp.statusCode());
                return;
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            List<Asset> assets = parseItems(root);
            if (assets.isEmpty() && page == 0) log.info("Nexus search response body: {}", resp.body());
            for (Asset a : assets) found.putIfAbsent(a.downloadUrl(), a);
            token = root.has("continuationToken") && !root.get("continuationToken").isJsonNull()
                    ? root.get("continuationToken").getAsString() : null;
            if (token == null || token.isBlank()) return;
        }
    }

    /**
     * The asset search URL. {@code base} is the server's address (trailing slashes are
     * dropped); {@code repository}, when given, limits the search to that repository.
     */
    static String searchUrl(String base, String repository, String param, String value, String continuationToken) {
        String b = base.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        StringBuilder url = new StringBuilder(b).append("/service/rest/v1/search/assets?")
                .append(param).append('=').append(enc(value));
        if (repository != null && !repository.isBlank()) url.append("&repository=").append(enc(repository.strip()));
        if (continuationToken != null) url.append("&continuationToken=").append(enc(continuationToken));
        return url.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** The JARs in one page of results; sources, Javadoc and other classified JARs are left out. */
    static List<Asset> parseItems(JsonObject root) {
        JsonArray items = root.has("items") && root.get("items").isJsonArray()
                ? root.getAsJsonArray("items") : new JsonArray();
        log.debug("Nexus page has {} item(s) before the JAR filter", items.size());
        List<Asset> result = new ArrayList<>();
        for (JsonElement el : items) {
            JsonObject item = el.getAsJsonObject();
            String path        = string(item, "path", "");
            String contentType = string(item, "contentType", "");
            if (!contentType.contains("java-archive") && !path.endsWith(".jar")) continue;
            JsonObject m2 = item.has("maven2") && item.get("maven2").isJsonObject()
                    ? item.getAsJsonObject("maven2") : null;
            if (m2 != null && !string(m2, "classifier", "").isBlank()) continue;

            String downloadUrl = string(item, "downloadUrl", null);
            if (downloadUrl == null) continue;
            long   size        = item.has("fileSize") && !item.get("fileSize").isJsonNull()
                    ? item.get("fileSize").getAsLong() : -1;
            String groupId     = m2 != null ? string(m2, "groupId", "")      : "";
            String artifactId  = m2 != null ? string(m2, "artifactId", path) : path;
            String version     = m2 != null ? string(m2, "version", "")      : "";
            // lastModified on Nexus 3.x; blobCreated as well on most versions.
            Instant published  = Asset.parseTime(string(item, "lastModified", null));
            if (published == null) published = Asset.parseTime(string(item, "blobCreated", null));

            result.add(new Asset(groupId, artifactId, version, downloadUrl, size, published));
        }
        return result;
    }

    private static String string(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    @Override
    public Path download(Asset asset, Path destDir, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        log.info("Nexus download: GET {}", asset.downloadUrl());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(asset.downloadUrl()))
                .header("Authorization", buildAuthHeader())
                .GET()
                .build();

        String rawName = asset.downloadUrl().substring(asset.downloadUrl().lastIndexOf('/') + 1);
        Path dest = destDir.resolve(rawName);

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        log.info("Nexus download response: HTTP {}", resp.statusCode());
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

    boolean hasAuthConfigured() {
        String t = config.authType();
        return "basic".equals(t) || "usertoken".equals(t);
    }

    private String buildAuthHeader() {
        return switch (config.authType() != null ? config.authType() : "none") {
            case "basic", "usertoken" -> "Basic " + Base64.getEncoder().encodeToString(
                    (config.username() + ":" + config.credential()).getBytes(StandardCharsets.UTF_8));
            default -> "";
        };
    }
}
