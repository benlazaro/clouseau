package com.droidenx.clouseau.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The annotations of one log file (D-9).
 * <p>
 * Each annotation is anchored by its <em>entry number</em> (the 1-based position of the
 * entry in the file, blank lines not counted) and by a hash of the line's content. When
 * the file is read again, an annotation attaches to the entry at its number if the hash
 * matches there; otherwise to the nearest entry with a matching hash that isn't taken.
 * Annotations that match nothing are kept as orphans, so they're never lost.
 * <p>
 * Annotations are saved to {@code <log>.annotations} next to the log. If that folder
 * can't be written, they go to {@code <fallbackDir>/<hash of the log's path>.annotations}
 * instead. On load, whichever of the two is newer wins.
 * <p>
 * Threading: {@link #load} and an {@link Anchoring} run on a background thread; once the
 * store is handed to the UI, use it from that thread only.
 */
@Slf4j
public final class AnnotationStore {

    /**
     * One note. {@code line} is the 1-based entry number (null in sidecars written before
     * D-9); {@code lineHash} is {@link #hashOf} of the line's raw text.
     */
    public record Annotation(
            String lineHash,
            Integer line,
            String logTimestamp,
            String logPreview,
            String note,
            String author,
            String createdAt
    ) {
        Annotation atLine(int line) {
            return new Annotation(lineHash, line, logTimestamp, logPreview, note, author, createdAt);
        }
    }

    /** An annotation with the 0-based entry ordinal it is attached to. */
    public record Placed(int ordinal, Annotation annotation) {}

    static final String SUFFIX = ".annotations";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Attached annotations, by 0-based entry ordinal. */
    private final TreeMap<Integer, Annotation> byOrdinal = new TreeMap<>();
    /** Annotations from the sidecar not attached to any entry (yet). */
    private final List<Annotation> orphans = new ArrayList<>();

    private final Path sidecar;
    private final Path fallback;
    private Path savePath;

    private AnnotationStore(Path sidecar, Path fallback) {
        this.sidecar  = sidecar;
        this.fallback = fallback;
        this.savePath = sidecar;
    }

    /** A store that is never saved, for sources without a file (SSH, plugin sources). */
    public static AnnotationStore inMemory() {
        return new AnnotationStore(null, null);
    }

    /**
     * Reads the annotations of {@code logFile}. Every annotation starts as an orphan until
     * an {@link Anchoring} attaches it. Does I/O: call off the UI thread.
     *
     * @param fallbackDir where to save if the log's folder isn't writable; may be null
     */
    public static AnnotationStore load(Path logFile, Path fallbackDir) {
        Path abs = logFile.toAbsolutePath().normalize();
        Path sidecar  = abs.resolveSibling(abs.getFileName() + SUFFIX);
        Path fallback = fallbackDir == null ? null
                : fallbackDir.resolve(hashOf(abs.toString()) + SUFFIX);
        AnnotationStore store = new AnnotationStore(sidecar, fallback);
        Path source = newer(sidecar, fallback);
        if (source != null) {
            store.orphans.addAll(read(source));
            store.savePath = source;
            log.debug("Loaded {} annotation(s) from {}", store.orphans.size(), source);
        }
        return store;
    }

    private static Path newer(Path a, Path b) {
        boolean hasA = a != null && Files.isRegularFile(a);
        boolean hasB = b != null && Files.isRegularFile(b);
        if (hasA && hasB) {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)) > 0 ? b : a;
            } catch (IOException e) {
                return a;
            }
        }
        return hasA ? a : hasB ? b : null;
    }

    private static List<Annotation> read(Path file) {
        List<Annotation> result = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) return result;
            JsonArray arr = root.getAsJsonObject().getAsJsonArray("annotations");
            if (arr == null) return result;
            for (JsonElement el : arr) {
                Annotation a = GSON.fromJson(el, Annotation.class);
                if (a != null && a.lineHash() != null) result.add(a);
            }
        } catch (Exception e) {
            log.warn("Could not read annotations from {}", file, e);
        }
        return result;
    }

    // ── Anchoring ────────────────────────────────────────────────────────────

    /**
     * Starts attaching the loaded annotations to entries. Feed it every entry's raw line
     * in file order, then call {@link Anchoring#finish()}. Returns null when there is
     * nothing to attach, so callers can skip hashing lines.
     */
    public Anchoring startAnchoring() {
        return orphans.isEmpty() ? null : new Anchoring();
    }

    /** Collects where each wanted hash occurs while a file is read. Not thread-safe. */
    public final class Anchoring {
        private final Set<String> wanted = new HashSet<>();
        private final Map<String, List<Integer>> found = new HashMap<>();
        private int ordinal = 0;

        private Anchoring() {
            for (Annotation a : orphans) wanted.add(a.lineHash());
        }

        /** The raw line of the next entry (ordinal 0, 1, 2, …). */
        public void accept(String rawLine) {
            String hash = hashOf(rawLine);
            if (wanted.contains(hash)) found.computeIfAbsent(hash, h -> new ArrayList<>()).add(ordinal);
            ordinal++;
        }

        /** Attaches what it can. Unmatched annotations stay orphans. */
        public void finish() {
            Set<Integer> taken = new HashSet<>();
            List<Annotation> pending = new ArrayList<>(orphans);
            orphans.clear();

            // First pass: annotations whose own entry still has the same content.
            List<Annotation> moved = new ArrayList<>();
            for (Annotation a : pending) {
                List<Integer> at = found.getOrDefault(a.lineHash(), List.of());
                Integer want = a.line() != null ? a.line() - 1 : null;
                if (want != null && Collections.binarySearch(at, want) >= 0 && taken.add(want)) {
                    byOrdinal.put(want, a);
                } else {
                    moved.add(a);
                }
            }
            // Second pass: the nearest free entry with the same content.
            moved.sort(Comparator.comparing(a -> a.line() == null ? Integer.MAX_VALUE : a.line()));
            for (Annotation a : moved) {
                List<Integer> at = found.getOrDefault(a.lineHash(), List.of());
                int want = a.line() != null ? a.line() - 1 : 0;
                Integer best = null;
                for (int candidate : at) {
                    if (taken.contains(candidate)) continue;
                    if (best == null || Math.abs(candidate - want) < Math.abs(best - want)) best = candidate;
                }
                if (best != null) {
                    taken.add(best);
                    byOrdinal.put(best, a.atLine(best + 1));
                } else {
                    orphans.add(a);
                }
            }
        }
    }

    // ── Queries and edits (UI thread) ────────────────────────────────────────

    public Annotation at(int ordinal) { return byOrdinal.get(ordinal); }

    public boolean has(int ordinal) { return byOrdinal.containsKey(ordinal); }

    /** Attached annotations in entry order. */
    public List<Placed> placed() {
        List<Placed> result = new ArrayList<>(byOrdinal.size());
        byOrdinal.forEach((ord, a) -> result.add(new Placed(ord, a)));
        return result;
    }

    /** Annotations that match no entry in the file as it is now. */
    public List<Annotation> orphans() { return List.copyOf(orphans); }

    public int size() { return byOrdinal.size() + orphans.size(); }

    /**
     * Sets (or replaces) the annotation on the entry at {@code ordinal}, then saves.
     * The annotation's line number is set from the ordinal.
     *
     * @return false if the annotations couldn't be saved anywhere
     */
    public boolean put(int ordinal, Annotation annotation) {
        byOrdinal.put(ordinal, annotation.atLine(ordinal + 1));
        return save();
    }

    /** Removes the annotation on the entry at {@code ordinal}, if any, then saves. */
    public boolean remove(int ordinal) {
        return byOrdinal.remove(ordinal) == null || save();
    }

    /** Removes an orphan annotation, then saves. */
    public boolean removeOrphan(Annotation annotation) {
        return !orphans.remove(annotation) || save();
    }

    /** Where the annotations are (or will be) saved, or null for in-memory stores. */
    public Path getSavePath() { return savePath; }

    /** True when the annotations are saved in the data folder, not next to the log. */
    public boolean isUsingFallback() { return savePath != null && savePath.equals(fallback); }

    // ── Saving ───────────────────────────────────────────────────────────────

    private boolean save() {
        if (savePath == null) return true;
        List<Annotation> all = new ArrayList<>(byOrdinal.values());
        all.addAll(orphans);
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.add("annotations", GSON.toJsonTree(all));
        String json = GSON.toJson(root);

        if (write(savePath, json)) return true;
        if (fallback != null && !savePath.equals(fallback)) {
            log.info("Can't write annotations next to the log; saving them to {}", fallback);
            if (write(fallback, json)) {
                savePath = fallback;
                return true;
            }
        }
        return false;
    }

    /** Writes through a temp file in the same folder, so a failed write never truncates. */
    private static boolean write(Path target, String json) {
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(json);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | SecurityException e) {
            log.warn("Could not save annotations to {}: {}", target, e.toString());
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
            return false;
        }
    }

    /**
     * The first 16 hex characters of the SHA-256 of {@code rawLine} (UTF-8). Stable across
     * reloads and filters, and compatible with sidecars from earlier versions.
     */
    public static String hashOf(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) return "0000000000000000";
        byte[] hash = SHA256.get().digest(rawLine.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2]     = HEX[(hash[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[hash[i] & 0xF];
        }
        return new String(out);
    }

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    });
}
