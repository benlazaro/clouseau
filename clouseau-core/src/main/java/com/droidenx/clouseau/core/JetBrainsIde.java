package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.SourceRefs.SourceRef;
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

/**
 * Opens a file at a line in a running JetBrains IDE (IntelliJ IDEA, PyCharm, Rider, …)
 * through the IDE's built-in web server.
 *
 * <p>Each IDE listens on {@code localhost}, on port 63342 or the next free one up to 63352
 * when several are open. {@code GET /api/file?file=<path>&line=<n>} finds a relative path
 * under any open project's module source and content roots and opens it: 200 means it did,
 * 404 that this IDE's projects don't have the file. Every port is tried until one opens it.
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
            if (status == 200) return Result.OPENED;
            if (status < 0) continue;
            // 404 is also what an IDE without the plugin answers: ask with no file to tell them apart.
            Result here = status == 404 && request(port, "") == 404 ? Result.NO_PLUGIN : Result.NOT_FOUND;
            if (here.ordinal() < best.ordinal()) best = here;
        }
        return best;
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
