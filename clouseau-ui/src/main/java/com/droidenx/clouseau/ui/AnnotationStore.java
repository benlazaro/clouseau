package com.droidenx.clouseau.ui;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Manages per-log-file annotations, keyed by a SHA-256 hash of each rawLine.
 * <p>
 * Annotations are persisted to a sidecar file next to the log:
 * {@code <logfile>.clouseau-notes.json}. Colleagues can open the same log
 * file and will see your annotations automatically, as long as the sidecar
 * file is placed in the same directory.
 */
@Slf4j
public final class AnnotationStore {

    public record Annotation(
            String lineHash,
            String logTimestamp,
            String logPreview,
            String note,
            String author,
            String createdAt
    ) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, Annotation> byHash = new LinkedHashMap<>();
    private final Path savePath;

    private AnnotationStore(Path savePath) {
        this.savePath = savePath;
    }

    /** Creates a store backed by a sidecar file next to {@code logFile}, loading any existing annotations. */
    public static AnnotationStore forFile(Path logFile) {
        Path savePath = logFile.resolveSibling(logFile.getFileName() + ".annotations");
        AnnotationStore store = new AnnotationStore(savePath);
        store.load();
        return store;
    }

    /** Creates an in-memory-only store (used when no local file path is available, e.g. SSH streams). */
    public static AnnotationStore empty() {
        return new AnnotationStore(null);
    }

    public boolean hasAnnotation(String lineHash) {
        return byHash.containsKey(lineHash);
    }

    public Annotation get(String lineHash) {
        return byHash.get(lineHash);
    }

    public void put(Annotation annotation) {
        byHash.put(annotation.lineHash(), annotation);
        save();
    }

    public void remove(String lineHash) {
        if (byHash.remove(lineHash) != null) save();
    }

    public int size() { return byHash.size(); }

    public java.util.Collection<Annotation> all() {
        return java.util.Collections.unmodifiableCollection(byHash.values());
    }

    /** Path of the sidecar file, or {@code null} for in-memory-only stores. */
    public Path getSavePath() { return savePath; }

    /**
     * Returns the first 16 hex characters of the SHA-256 hash of {@code rawLine}.
     * This is stable across filter changes, reloads, and different views of the same log.
     */
    public static String hashOf(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) return "0000000000000000";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawLine.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.format("%016x", (long) rawLine.hashCode());
        }
    }

    private void load() {
        if (savePath == null || !Files.exists(savePath)) return;
        try (Reader r = new InputStreamReader(Files.newInputStream(savePath), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("annotations");
            if (arr == null) return;
            for (JsonElement el : arr) {
                Annotation a = GSON.fromJson(el, Annotation.class);
                if (a != null && a.lineHash() != null) byHash.put(a.lineHash(), a);
            }
            log.debug("Loaded {} annotation(s) from {}", byHash.size(), savePath.getFileName());
        } catch (Exception e) {
            log.warn("Could not load annotations from {}", savePath, e);
        }
    }

    private void save() {
        if (savePath == null) return;
        JsonObject root = new JsonObject();
        root.add("annotations", GSON.toJsonTree(new ArrayList<>(byHash.values())));
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(savePath), StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        } catch (IOException e) {
            log.warn("Could not save annotations to {}", savePath, e);
        }
    }
}
