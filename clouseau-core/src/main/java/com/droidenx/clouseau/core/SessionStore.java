package com.droidenx.clouseau.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads and writes sessions (UC-17, D-19): {@code <data>/session.json}, the last session,
 * and {@code <data>/sessions/<name>.json}, one named session each, in one format.
 *
 * <pre>{"format": "clouseau-session", "formatVersion": 1, "clouseauVersion": "…",
 *  "savedAt": "…", "name": "…", "activeSession": "…", "windows": [ … ]}</pre>
 *
 * Unknown fields are ignored and a newer {@code formatVersion} is read anyway (logged), so
 * new fields must stay optional. Writes go to a temporary file moved into place, so an
 * interrupted write leaves the previous file whole.
 */
@Slf4j
public final class SessionStore {

    public static final String FORMAT = "clouseau-session";
    public static final int FORMAT_VERSION = 1;
    /** How many times a failed move into place is tried (a virus scanner may hold the file). */
    private static final int MOVE_ATTEMPTS = 3;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SessionStore() {}

    /**
     * The session's content as JSON, without the header (version, time saved). Two sessions
     * with the same content give the same text, which is how an automatic save tells that
     * nothing changed.
     */
    public static String toJson(Session session) {
        return GSON.toJson(session);
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /** What reading a session file found. */
    public sealed interface ReadResult {
        /** The file doesn't exist. */
        record Missing() implements ReadResult {}
        /** The file was read. {@code name} is a named session's name, or null. */
        record Read(Session session, String name) implements ReadResult {}
        /** The file exists but isn't a session this version can read. */
        record Damaged(String reason) implements ReadResult {}
    }

    public static ReadResult read(Path file) {
        if (!Files.isRegularFile(file)) return new ReadResult.Missing();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read session {}", file, e);
            return new ReadResult.Damaged(e.getMessage());
        }
    }

    static ReadResult parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return new ReadResult.Damaged("not JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) return new ReadResult.Damaged("not a JSON object");
        JsonObject o = root.getAsJsonObject();
        if (!o.has("format") || !FORMAT.equals(o.get("format").getAsString())) {
            return new ReadResult.Damaged("not a Clouseau session");
        }
        int version = o.has("formatVersion") ? o.get("formatVersion").getAsInt() : FORMAT_VERSION;
        if (version > FORMAT_VERSION) {
            log.warn("Session format {} is newer than this Clouseau's {}; reading what it can", version, FORMAT_VERSION);
        }
        try {
            Session session = GSON.fromJson(o, Session.class);
            String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
            return new ReadResult.Read(clean(session), name);
        } catch (RuntimeException e) {
            return new ReadResult.Damaged(e.getMessage());
        }
    }

    /** Drops tabs a reader can't use (no kind, or missing what their kind needs). */
    private static Session clean(Session s) {
        List<Session.Window> windows = new ArrayList<>();
        for (Session.Window w : s.windows()) {
            if (w == null) continue;
            List<Session.Tab> tabs = w.tabs().stream().filter(SessionStore::usable).toList();
            int selected = Math.min(w.selectedTab(), tabs.size() - 1);
            windows.add(new Session.Window(w.bounds(), w.screen(), w.maximized(), selected, tabs));
        }
        return new Session(s.activeSession(), windows);
    }

    private static boolean usable(Session.Tab t) {
        if (t == null || t.kind() == null) return false;
        return switch (t.kind()) {
            case FILE -> t.path() != null;
            case MERGED -> t.files() != null && !t.files().isEmpty();
            case SSH -> t.ssh() != null && t.ssh().host() != null;
            case SOURCE -> t.source() != null && t.source().provider() != null;
        };
    }

    /**
     * Moves a session file that couldn't be read out of the way, to {@code <file>.bad},
     * so the next save doesn't overwrite what the user might want back.
     */
    public static void setAside(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not set aside damaged session {}", file, e);
        }
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    /** Writes {@code session} to {@code file}; {@code name} is a named session's name, or null. */
    public static void write(Path file, Session session, String name, String clouseauVersion) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("formatVersion", FORMAT_VERSION);
        o.addProperty("clouseauVersion", clouseauVersion);
        o.addProperty("savedAt", Instant.now().toString());
        if (name != null) o.addProperty("name", name);
        GSON.toJsonTree(session).getAsJsonObject().entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Files.deleteIfExists(tmp);
        throw last != null ? last : new IOException("Interrupted while saving " + file);
    }

    // ── Named sessions ────────────────────────────────────────────────────────

    /** A saved named session's name and file. */
    public record Named(String name, Path file) {}

    /** The named sessions in {@code dir}, sorted by name (case-insensitive). */
    public static List<Named> list(Path dir) {
        List<Named> named = new ArrayList<>();
        if (!Files.isDirectory(dir)) return named;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                if (read(f) instanceof ReadResult.Read r) {
                    named.add(new Named(r.name() != null ? r.name() : baseName(f), f));
                }
            }
        } catch (IOException e) {
            log.warn("Could not list sessions in {}", dir, e);
        }
        named.sort(Comparator.comparing(n -> n.name().toLowerCase(Locale.ROOT)));
        return named;
    }

    /** The named session called {@code name} (case-insensitive), if there is one. */
    public static Optional<Named> find(Path dir, String name) {
        return list(dir).stream().filter(n -> n.name().equalsIgnoreCase(name.strip())).findFirst();
    }

    /** Saves {@code session} as the named session {@code name}, replacing one of that name. */
    public static Path save(Path dir, String name, Session session, String clouseauVersion) throws IOException {
        String clean = name.strip();
        Path file = find(dir, clean).map(Named::file).orElseGet(() -> freeFile(dir, clean));
        write(file, session.withActiveSession(null), clean, clouseauVersion);
        log.info("Saved session '{}' to {}", clean, file);
        return file;
    }

    /** Renames a named session, keeping its file's content. */
    public static void rename(Path dir, String from, String to) throws IOException {
        Named named = find(dir, from).orElseThrow(() -> new IOException("No session named " + from));
        if (!(read(named.file()) instanceof ReadResult.Read r)) throw new IOException("Can't read " + named.file());
        Path target = freeFile(dir, to.strip());
        write(target, r.session(), to.strip(), null);
        Files.deleteIfExists(named.file());
    }

    public static void delete(Path dir, String name) throws IOException {
        Optional<Named> named = find(dir, name);
        if (named.isPresent()) Files.deleteIfExists(named.get().file());
    }

    /** A file in {@code dir} named after {@code name} that doesn't exist yet. */
    static Path freeFile(Path dir, String name) {
        String base = fileName(name);
        Path file = dir.resolve(base + ".json");
        for (int i = 2; Files.exists(file); i++) file = dir.resolve(base + "-" + i + ".json");
        return file;
    }

    /** {@code name} made safe as a file name on every platform. */
    static String fileName(String name) {
        String s = name.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        s = s.replaceAll("^[. ]+|[. ]+$", "");
        if (s.length() > 100) s = s.substring(0, 100);
        if (s.isEmpty() || s.matches("(?i)(CON|PRN|AUX|NUL|COM\\d|LPT\\d)")) s = "session_" + s;
        return s;
    }

    private static String baseName(Path f) {
        String n = f.getFileName().toString();
        return n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
    }
}
