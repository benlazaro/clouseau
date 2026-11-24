package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.SourceRefs.SourceRef;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Opens a file at a line in a running JetBrains IDE (IntelliJ IDEA, PyCharm, Rider, …)
 * through the IDE's built-in web server.
 *
 * <p>Each IDE listens on {@code localhost}, on port 63342 or the next free one up to 63352
 * when several are open. {@code GET /api/file?file=<path>&line=<n>} opens a full path, or
 * finds a relative path under any open project's module source and content roots and opens
 * it. Before 2026.2, 200 meant it did and 404 that this IDE's projects don't have the file, and
 * every port is tried until one opens it. The IDE Remote Control plugin (below) answers 200
 * at once, whether or not it finds the file, so with it a relative path's 200 means only that
 * the IDE was asked, and the first IDE to answer is the only one asked: callers that can,
 * send a full path.
 *
 * <p>The request carries a {@code localhost} {@code Origin}: the IDE trusts local origins,
 * and asks "allow this request?" each time for a request with no origin at all.
 *
 * <p>Since IntelliJ 2026.2 the service comes from JetBrains' "IDE Remote Control" plugin
 * ({@code com.intellij.remoteControl}), no longer bundled; earlier versions have it built in.
 * Without it the IDE answers 404 to every {@code /api/file} request, as it does for a file it
 * doesn't have, so an IDE that answers 404 is asked again with no file: the service answers
 * that with an error of its own (a missing parameter), an IDE without it with 404 again.
 *
 * <p>Blocking: call {@link #open} off the Event Dispatch Thread.
 */
@Slf4j
public final class JetBrainsIde {

    /** The built-in server's first port, and how many after it the IDEs may take. */
    static final int FIRST_PORT = 63342, PORTS = 11;

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    /** The IDE answers once it has looked the file up, which can take a moment while it indexes. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** How an attempt to open a file ended, best first. */
    public enum Result {
        /** An IDE opened it. */
        OPENED,
        /** IDEs answered, but none of their open projects has the file. */
        NOT_FOUND,
        /** IDEs answered, but none can open files: the IDE Remote Control plugin is missing. */
        NO_PLUGIN,
        /** No IDE answered on any port: none is running, or its built-in server is off. */
        NO_IDE
    }

    private final HttpClient http;
    private final String host;
    private final int firstPort, ports;
    /** The port of the IDE that answered 200 last, or -1. */
    private volatile int openedPort = -1;

    public JetBrainsIde() {
        this("127.0.0.1", FIRST_PORT, PORTS);
    }

    /** For tests: another host or range of ports. */
    JetBrainsIde(String host, int firstPort, int ports) {
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.host = host;
        this.firstPort = firstPort;
        this.ports = ports;
    }

    /** Opens the first of {@code candidates} an IDE has, at its line. Blocks. */
    public Result open(List<SourceRef> candidates) {
        Result best = Result.NO_IDE;
        for (SourceRef ref : candidates) {
            Result r = open(ref);
            if (r == Result.OPENED) return r;
            if (r.ordinal() < best.ordinal()) best = r;
        }
        return best;
    }

    /** Opens {@code ref} in whichever running IDE has it. Blocks. */
    public Result open(SourceRef ref) {
        StringBuilder query = new StringBuilder("?file=").append(URLEncoder.encode(ref.path(), StandardCharsets.UTF_8));
        if (ref.line() > 0) query.append("&line=").append(ref.line());
        Result best = Result.NO_IDE;
        for (int port = firstPort; port < firstPort + ports; port++) {
            if (Thread.currentThread().isInterrupted()) break;
            int status = request(port, query.toString());
            if (status == 200) {
                openedPort = port;
                return Result.OPENED;
            }
            if (status < 0) continue;
            // 404 is also what an IDE without the plugin answers: ask with no file to tell them apart.
            Result here = status == 404 && request(port, "") == 404 ? Result.NO_PLUGIN : Result.NOT_FOUND;
            if (here.ordinal() < best.ordinal()) best = here;
        }
        return best;
    }

    /** The port of the IDE that last answered {@link Result#OPENED}, to find its process by. */
    public OptionalInt openedPort() {
        int port = openedPort;
        return port < 0 ? OptionalInt.empty() : OptionalInt.of(port);
    }

    /**
     * What a running IDE says about itself in {@code /api/about}: its port, its name ("IntelliJ
     * IDEA 2026.3"), product ("IDEA") and build ("263.7307.200"); any but the port may be null.
     */
    public record About(int port, String name, String product, String build) {}

    /** The first running IDE, by port, that says what it is. Blocks. */
    public Optional<About> about() {
        for (int port = firstPort; port < firstPort + ports; port++) {
            if (Thread.currentThread().isInterrupted()) break;
            Optional<About> about = about(port);
            if (about.isPresent()) return about;
        }
        return Optional.empty();
    }

    /** What the IDE on {@code port} says about itself, or empty if nothing there says. Blocks. */
    public Optional<About> about(int port) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/api/about"))
                .timeout(REQUEST_TIMEOUT)
                .header("Origin", "http://localhost:" + port)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return Optional.of(new About(port, text(json, "name"), text(json, "productName"), text(json, "buildNumber")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();   // nothing listening, or not JSON: not an IDE
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static String text(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** The status the IDE on {@code port} answered to {@code /api/file<query>}, or -1 if nothing answered. */
    private int request(int port, String query) {
        URI uri = URI.create("http://" + host + ":" + port + "/api/file" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Origin", "http://localhost:" + port)
                .GET()
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            log.debug("Open in IDE: port {} answered {} for /api/file{}", port, status, query);
            return status;
        } catch (IOException e) {
            return -1;   // nothing listening, or not an IDE
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
