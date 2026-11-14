package com.droidenx.clouseau.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Saved SSH connections, as the SSH dialog keeps them: each connection is one sign-in (user,
 * host, port and how to authenticate) with an optional name, and the logs saved on it. A
 * connection is identified by its user, host and port ({@link #key}); how it authenticates is a
 * setting of it, so two connections can't share all three. Names, when given, are unique too.
 *
 * <p>Earlier versions saved one entry per log, each carrying the whole sign-in, with names kept
 * per host; {@link #fromFavorites} turns those into connections once.
 */
public final class SshSaved {

    private SshSaved() {}

    /** A log saved on a connection: its path on the server and the parser to read it with (null: auto-detect). */
    public record Log(String remotePath, String parserName) {
        public Log {
            Objects.requireNonNull(remotePath, "remotePath");
        }
    }

    /**
     * A saved connection. {@code authType} is "password", "key" or "none"; {@code password} goes
     * with "password", {@code keyFile} and {@code keyPassphrase} with "key". Secrets are held in
     * the clear here; whoever stores them encrypts them.
     */
    public record Connection(String name, String host, int port, String user, String authType,
                             String password, String keyFile, String keyPassphrase, List<Log> logs) {
        public Connection {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(authType, "authType");
            name = name == null || name.isBlank() ? null : name.strip();
            logs = List.copyOf(logs);
        }

        /** What identifies it: user, host (in any case) and port. */
        public String key() {
            return SshSaved.key(user, host, port);
        }

        /** {@code user@host}, with {@code :port} when it isn't 22. */
        public String address() {
            return SshSaved.address(user, host, port);
        }

        /** What a list shows for it: its name, or its address when it has none. */
        public String label() {
            return name != null ? name : address();
        }

        public Connection withName(String newName) {
            return new Connection(newName, host, port, user, authType, password, keyFile, keyPassphrase, logs);
        }

        public Connection withLogs(List<Log> newLogs) {
            return new Connection(name, host, port, user, authType, password, keyFile, keyPassphrase, newLogs);
        }

        /** The same connection with another sign-in (address and credentials), its name and logs kept. */
        public Connection withSignIn(Connection signIn) {
            return new Connection(name, signIn.host, signIn.port, signIn.user, signIn.authType,
                    signIn.password, signIn.keyFile, signIn.keyPassphrase, logs);
        }

        /** Whether it signs in the same way as {@code other}: the same method and secrets. */
        public boolean sameSignIn(Connection other) {
            return authType.equals(other.authType) && same(password, other.password)
                    && same(keyFile, other.keyFile) && same(keyPassphrase, other.keyPassphrase);
        }

        /** Its name and address only: never its password or passphrase, so a log line can't leak one. */
        @Override public String toString() {
            return "Connection[" + (name != null ? name + ", " : "") + address() + ", " + authType + ", "
                    + logs.size() + " logs]";
        }

        /** The connection to sign in with for one of its logs. */
        public SshConfig config(String remotePath) {
            return switch (authType) {
                case "key"  -> new SshConfig(host, port, user, null, keyFile, keyPassphrase, remotePath);
                case "none" -> new SshConfig(host, port, user, null, null, null, remotePath);
                default     -> new SshConfig(host, port, user, password, null, null, remotePath);
            };
        }
    }

    /** The identity of a connection: user, host (lower-cased) and port. */
    public static String key(String user, String host, int port) {
        return user + "@" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** {@code user@host}, with {@code :port} when it isn't 22, as lists show an unnamed connection. */
    public static String address(String user, String host, int port) {
        return user + "@" + host + (port != SshConfig.DEFAULT_PORT ? ":" + port : "");
    }

    /** The connection with this key, or null. */
    public static Connection find(List<Connection> connections, String key) {
        for (Connection c : connections) if (c.key().equals(key)) return c;
        return null;
    }

    /** The connection with this name (any case), other than the one with key {@code exceptKey}, or null. */
    public static Connection named(List<Connection> connections, String name, String exceptKey) {
        if (name == null || name.isBlank()) return null;
        for (Connection c : connections) {
            if (c.name() != null && c.name().equalsIgnoreCase(name.strip()) && !c.key().equals(exceptKey)) return c;
        }
        return null;
    }

    /** The logs with {@code log} added, or replacing one with the same path. */
    public static List<Log> withLog(List<Log> logs, Log log) {
        List<Log> out = new ArrayList<>();
        boolean replaced = false;
        for (Log l : logs) {
            if (l.remotePath().equals(log.remotePath())) {
                if (!replaced) out.add(log);
                replaced = true;
            } else {
                out.add(l);
            }
        }
        if (!replaced) out.add(log);
        return out;
    }

    /** A log as earlier versions saved it: one per entry, with the whole sign-in on each. */
    public record OldFavorite(String host, int port, String user, String authType, String password,
                              String keyFile, String keyPassphrase, String remotePath, String parserName) {}

    /**
     * Turns earlier versions' saved logs into connections: logs with the same user, host and port
     * become one connection, which signs in as the first of them did. A name saved for a host goes
     * to the first connection on it; any other connection on that host gets the name with its user
     * after it ("Payments prod (root)"), since names are unique. A path saved twice on one
     * connection is kept once.
     */
    public static List<Connection> fromFavorites(List<OldFavorite> favorites, Map<String, String> namesByHost) {
        Map<String, Connection> byKey = new LinkedHashMap<>();
        for (OldFavorite f : favorites) {
            String key = key(f.user(), f.host(), f.port());
            Connection c = byKey.get(key);
            if (c == null) {
                c = new Connection(null, f.host(), f.port(), f.user(), f.authType() == null ? "password" : f.authType(),
                        f.password(), f.keyFile(), f.keyPassphrase(), List.of());
            }
            boolean known = c.logs().stream().anyMatch(l -> l.remotePath().equals(f.remotePath()));
            if (!known && f.remotePath() != null && !f.remotePath().isBlank()) {
                List<Log> logs = new ArrayList<>(c.logs());
                logs.add(new Log(f.remotePath(), f.parserName()));
                c = c.withLogs(logs);
            }
            byKey.put(key, c);
        }
        List<Connection> out = new ArrayList<>();
        Map<String, Integer> namedOnHost = new LinkedHashMap<>();
        for (Connection c : byKey.values()) {
            String name = namesByHost == null ? null : namesByHost.get(c.host());
            if (name != null && !name.isBlank()) {
                int n = namedOnHost.merge(c.host(), 1, Integer::sum);
                c = c.withName(n == 1 ? name : name + " (" + c.user() + ")");
            }
            out.add(c);
        }
        return out;
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
}
