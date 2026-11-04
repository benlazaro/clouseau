package com.droidenx.clouseau.ui;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ensures only one instance of Clouseau runs at a time by binding a
 * ServerSocket on localhost. Secondary instances send their file path(s)
 * to the primary and then exit.
 *
 * <p>Protocol: a secondary opens one connection, writes each path on its own line
 * (UTF-8), and closes the connection. The primary reads lines until end of stream.
 */
@Slf4j
public final class SingleInstanceManager {

    /** Arbitrary port unlikely to conflict with other services. */
    static final int PORT = 54823;

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
     * listener thread that calls {@code onFileReceived} (on the listener thread)
     * for every path a secondary instance sends.
     *
     * @return {@code true} if this is the primary instance;
     *         {@code false} if another instance is already running.
     */
    public static boolean tryBecomePrimary(Consumer<String> onFileReceived) {
        return listen(PORT, onFileReceived) != null;
    }

    /**
     * Binds {@code port} on the loopback address and serves secondaries from a daemon
     * thread. Returns the bound socket, or {@code null} if the port is taken.
     */
    static ServerSocket listen(int port, Consumer<String> onFileReceived) {
        ServerSocket server;
        try {
            server = new ServerSocket(port, BACKLOG, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            log.info("Another instance is already running (port {} busy: {})", port, e.getMessage());
            return null;
        }
        Thread listener = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                    client.setSoTimeout(5_000);   // a stuck secondary must not block the others
                    String path;
                    while ((path = reader.readLine()) != null) {
                        if (!path.isBlank()) onFileReceived.accept(path);
                    }
                } catch (IOException e) {
                    if (!server.isClosed()) log.debug("IPC connection failed", e);
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
     * retrying briefly if the primary doesn't accept it at once.
     *
     * @return {@code true} if the paths were delivered.
     */
    public static boolean sendToPrimary(List<String> filePaths) {
        return send(PORT, filePaths);
    }

    static boolean send(int port, List<String> filePaths) {
        IOException last = null;
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                 Writer writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                for (String path : filePaths) {
                    writer.write(path);
                    writer.write('\n');
                }
                writer.flush();
                return true;
            } catch (IOException e) {
                last = e;
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
        log.warn("Failed to contact primary instance on port {}", port, last);
        return false;
    }
}
