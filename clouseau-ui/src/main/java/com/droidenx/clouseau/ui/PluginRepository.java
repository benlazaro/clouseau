package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.PluginCatalog;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Abstraction over a remote plugin artifact repository.
 * Implementations handle authentication and protocol details internally.
 */
interface PluginRepository {

    /** How long to wait for a repository to accept the connection. */
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /** How long to wait for one search request's answer; downloads have no limit. */
    Duration SEARCH_TIMEOUT  = Duration.ofSeconds(60);

    /** The HTTP client every repository uses: a connection that isn't accepted times out. */
    static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    record Asset(
            String groupId,
            String artifactId,
            String version,
            String downloadUrl,
            long   sizeBytes,     // -1 if unknown
            Instant published,    // when the repository got it; null if unknown
            PluginCatalog.Entry listing   // its entry on the plugin list; null if it isn't from the list
    ) {
        Asset(String groupId, String artifactId, String version, String downloadUrl, long sizeBytes, Instant published) {
            this(groupId, artifactId, version, downloadUrl, sizeBytes, published, null);
        }

        /** What to call it: the list's name, else the artifact name. */
        String displayName() { return listing != null ? listing.name() : artifactId; }

        /** {@code group:artifact}, or the artifact alone when the group is unknown. */
        String coordinates() { return groupId.isBlank() ? artifactId : groupId + ":" + artifactId; }

        /** {@code text} as an ISO-8601 instant or offset date-time ({@code 2026-03-01T10:15:30.123+00:00}), or null. */
        static Instant parseTime(String text) {
            if (text == null || text.isBlank()) return null;
            try {
                return OffsetDateTime.parse(text.strip()).toInstant();
            } catch (DateTimeParseException e) {
                try { return Instant.parse(text.strip()); } catch (DateTimeParseException e2) { return null; }
            }
        }
    }

    /** Human-readable label shown in the repository selector. */
    String getName();

    /**
     * Search for plugin JARs matching {@code query}.
     * Must be called on a worker thread, never on the EDT.
     */
    List<Asset> search(String query) throws IOException, InterruptedException;

    /**
     * The Clouseau plugins this repository has, latest version of each (G-15): what Browse shows
     * when it opens. Must be called on a worker thread, never on the EDT.
     */
    List<Asset> listPlugins() throws IOException, InterruptedException;

    /**
     * Download {@code asset} into {@code destDir}.
     * {@code onBytesRead} is called periodically with cumulative bytes received.
     * Must be called on a worker thread, never on the EDT.
     * Returns the path of the downloaded file.
     */
    Path download(Asset asset, Path destDir, LongConsumer onBytesRead)
            throws IOException, InterruptedException;
}
