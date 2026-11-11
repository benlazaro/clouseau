package com.droidenx.clouseau.ui;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ensures only one instance of Clouseau runs at a time by binding a
 * ServerSocket on localhost. Secondary instances send their file path(s)
 * to the primary and then exit.
 *
 * <p>Protocol: a secondary opens one connection, writes each path on its own line
 * (UTF-8), and closes the connection. The primary reads lines until end of stream and
 * hands the whole batch to its callback once. A connection with no paths (a launch
 * without file arguments) asks the running window to come to the front; the primary
 * does that after every batch, so older secondaries that only send paths work too.
 */
@Slf4j
public final class SingleInstanceManager {

    /**
     * Arbitrary port unlikely to conflict with other services. It sits below Windows' dynamic
     * port range (49152-65535), where Hyper-V, WSL and Docker reserve blocks at boot that no
     * program can bind, though nothing listens on them.
     */
    static final int PORT = 41823;

    /** Connections the OS may queue while the listener handles one; Windows refuses the rest. */
    private static final int BACKLOG = 50;

    /** Attempts to reach the primary before giving up, and the pause between them. */
    private static final int SEND_ATTEMPTS = 5;
    private static final long SEND_RETRY_MS = 200;

    private SingleInstanceManager() {}

    /**
     * Attempts to become the primary instance.
     *
     * <p>If the port is free this instance binds it and starts a daemon
     * listener thread that calls {@code onLaunch} (on the listener thread) once per
     * secondary launch, with the paths it sent in order; the list is empty when the
     * launch had no files and only wants the window brought forward.
     *
     * @return {@code true} if this is the primary instance;
     *         {@code false} if another instance is already running.
     */
    public static boolean tryBecomePrimary(Consumer<List<String>> onLaunch) {
        return listen(PORT, onLaunch) != null;
    }

    /**
     * Binds {@code port} on the loopback address and serves secondaries from a daemon
     * thread. Returns the bound socket, or {@code null} if the port is taken.
     */
    static ServerSocket listen(int port, Consumer<List<String>> onLaunch) {
        ServerSocket server;
        try {
            server = new ServerSocket(port, BACKLOG, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            log.info("Another instance is already running (port {} busy: {})", port, e.getMessage());
            return null;
        }
        Thread listener = new Thread(() -> {
            while (!server.isClosed()) {
                List<String> paths = new ArrayList<>();
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                    client.setSoTimeout(5_000);   // a stuck secondary must not block the others
                    String path;
                    while ((path = reader.readLine()) != null) {
                        if (!path.isBlank()) paths.add(path);
                    }
                } catch (IOException e) {
                    if (server.isClosed()) break;
                    log.debug("IPC connection failed", e);
                    if (paths.isEmpty()) continue;   // nothing arrived: no launch to act on
                }
                try {
                    onLaunch.accept(List.copyOf(paths));
                } catch (RuntimeException e) {
                    log.warn("Could not handle forwarded files {}", paths, e);
                }
            }
        }, "clouseau-ipc");
        listener.setDaemon(true);
        listener.start();
        log.debug("Single-instance server bound on port {}", port);
        return server;
    }

    /**
     * Sends {@code filePaths} to the primary instance over one IPC connection,
     * retrying briefly if the primary doesn't accept it at once. An empty list only
     * asks the primary to bring its window to the front.
     */
    public static Delivery sendToPrimary(List<String> filePaths) {
        return send(PORT, filePaths);
    }

    /** What became of a secondary launch's attempt to reach the primary. */
    public enum Delivery {
        /** The primary received the paths. */
        DELIVERED,
        /**
         * Every attempt was refused: nothing listens on the port, so there is no primary.
         * The port couldn't be bound for another reason (on Windows, a reserved port range).
         */
        NO_INSTANCE,
        /** Something holds the port but the paths didn't get through. */
        FAILED
    }

    static Delivery send(int port, List<String> filePaths) {
        IOException last = null;
        boolean allRefused = true;
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                 Writer writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                for (String path : filePaths) {
                    writer.write(path);
                    writer.write('\n');
                }
                writer.flush();
                return Delivery.DELIVERED;
            } catch (IOException e) {
                last = e;
                if (!(e instanceof ConnectException)) allRefused = false;
                if (attempt < SEND_ATTEMPTS) {
                    try {
                        Thread.sleep(SEND_RETRY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (allRefused && last != null) {
            log.warn("Port {} couldn't be bound, but no instance listens on it ({})", port, last.getMessage());
            return Delivery.NO_INSTANCE;
        }
        log.warn("Failed to contact primary instance on port {}", port, last);
        return Delivery.FAILED;
    }
}
