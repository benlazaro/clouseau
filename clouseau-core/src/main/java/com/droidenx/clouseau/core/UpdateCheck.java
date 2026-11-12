package com.droidenx.clouseau.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out whether a newer Clouseau has been released, from the project's GitHub releases.
 * <p>
 * Only published releases count: drafts are skipped, and so is the rolling {@code edge}
 * pre-release, whose tag isn't a version. Pre-releases count only while the running version
 * is itself a pre-release, so someone on a stable release isn't offered an alpha.
 * Development builds ({@code -SNAPSHOT}, or no version at all) are never compared.
 * <p>
 * Blocking: call {@link #check} off the Event Dispatch Thread.
 */
@Slf4j
public final class UpdateCheck {

    /** The GitHub API for the project's releases, newest first. */
    public static final String RELEASES_API = "https://api.github.com/repos/benlazaro/clouseau/releases?per_page=30";
    /** The releases page, for people who want to look for themselves. */
    public static final String RELEASES_PAGE = "https://github.com/benlazaro/clouseau/releases";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private UpdateCheck() {}

    /** A published release: its version (without the leading "v"), title and web page. */
    public record Release(String version, String name, String url) {}

    /** What a check found. */
    public sealed interface Result {
        /** A release newer than the running version. */
        record Available(Release release) implements Result {}
        /** Nothing newer. */
        record UpToDate() implements Result {}
        /** The running version is a development build, so there's nothing to compare it with. */
        record DevelopmentBuild() implements Result {}
        /** The releases couldn't be fetched or read. */
        record Failed(String reason) implements Result {}
    }

    /** Asks GitHub for the releases and compares them with {@code currentVersion}. Blocks. */
    public static Result check(String currentVersion) {
        if (isDevelopment(currentVersion)) return new Result.DevelopmentBuild();
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_API))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Clouseau/" + currentVersion)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.info("Update check: GitHub answered HTTP {}", response.statusCode());
                return new Result.Failed("HTTP " + response.statusCode());
            }
            return newest(response.body(), currentVersion)
                    .<Result>map(Result.Available::new)
                    .orElseGet(Result.UpToDate::new);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Failed("interrupted");
        } catch (IOException | RuntimeException e) {
            log.info("Update check failed: {}", e.toString());
            return new Result.Failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * The newest release in GitHub's releases JSON that is newer than {@code currentVersion},
     * if any. Malformed entries are skipped.
     */
    public static Optional<Release> newest(String releasesJson, String currentVersion) {
        Version current = Version.parse(currentVersion);
        if (current == null) return Optional.empty();
        JsonElement root = JsonParser.parseString(releasesJson);
        if (!root.isJsonArray()) return Optional.empty();

        Release best = null;
        Version bestVersion = current;
        for (JsonElement el : (JsonArray) root) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (bool(o, "draft")) continue;
            Version v = Version.parse(string(o, "tag_name"));
            if (v == null) continue;                                      // "edge", say
            if ((bool(o, "prerelease") || v.isPreRelease()) && !current.isPreRelease()) continue;
            if (v.compareTo(bestVersion) <= 0) continue;
            String name = string(o, "name");
            String url = string(o, "html_url");
            best = new Release(v.toString(),
                    name == null || name.isBlank() ? "Clouseau " + v : name,
                    url == null ? RELEASES_PAGE : url);
            bestVersion = v;
        }
        return Optional.ofNullable(best);
    }

    /** True for a version that isn't a release: blank, {@code dev}, or a {@code -SNAPSHOT}. */
    public static boolean isDevelopment(String version) {
        return version == null || version.isBlank() || version.endsWith("-SNAPSHOT") || Version.parse(version) == null;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /**
     * A release version, {@code 1.2.3} or {@code 1.2.3-alpha.1}, with an optional leading
     * "v", compared as Semantic Versioning does: a pre-release comes before its release, and
     * pre-release labels compare part by part (numbers as numbers).
     */
    public record Version(int major, int minor, int patch, List<String> preRelease) implements Comparable<Version> {

        private static final Pattern FORM = Pattern.compile("v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?");

        /** The version in {@code text}, or null if it isn't one. */
        public static Version parse(String text) {
            if (text == null) return null;
            Matcher m = FORM.matcher(text.trim());
            if (!m.matches()) return null;
            try {
                List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
                return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), pre);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public boolean isPreRelease() {
            return !preRelease.isEmpty();
        }

        @Override public int compareTo(Version o) {
            int c = Integer.compare(major, o.major);
            if (c == 0) c = Integer.compare(minor, o.minor);
            if (c == 0) c = Integer.compare(patch, o.patch);
            if (c != 0) return c;
            if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
                return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty());
            }
            for (int i = 0; i < Math.min(preRelease.size(), o.preRelease.size()); i++) {
                c = compareLabel(preRelease.get(i), o.preRelease.get(i));
                if (c != 0) return c;
            }
            return Integer.compare(preRelease.size(), o.preRelease.size());
        }

        private static int compareLabel(String a, String b) {
            boolean an = a.chars().allMatch(Character::isDigit), bn = b.chars().allMatch(Character::isDigit);
            if (an && bn) return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
            if (an != bn) return an ? -1 : 1;                             // numbers sort before words
            return a.compareTo(b);
        }

        @Override public String toString() {
            String base = major + "." + minor + "." + patch;
            return preRelease.isEmpty() ? base : base + "-" + String.join(".", preRelease);
        }
    }
}
