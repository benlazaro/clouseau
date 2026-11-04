package com.droidenx.clouseau.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A review bundle (D-10, UC-8/UC-9): one {@code .zip} holding a log and its annotations,
 * so a field engineer can hand off their findings as a single file.
 * <pre>
 * manifest.json      what the bundle is (see {@link Manifest})
 * &lt;log file name&gt;    the log, byte-for-byte (still .gz if it was)
 * annotations.json   the annotations and highlights, in the sidecar format (D-9, D-15)
 * </pre>
 * A {@code .zip} is a bundle only if it has a {@code manifest.json} whose {@code format}
 * is {@link #FORMAT} and whose log entry exists; any other zip is an ordinary compressed
 * log (UC-2), even one with a {@code manifest.json} of its own.
 * <p>
 * This is an interchange format (DESIGN section 7, rule 3): later versions must keep
 * reading bundles written by earlier ones.
 */
@Slf4j
public final class ReviewBundle {

    /** The name of the manifest entry, at the root of the zip. */
    public static final String MANIFEST = "manifest.json";
    /** The name of the annotations entry, at the root of the zip. */
    public static final String ANNOTATIONS = "annotations.json";
    /** The manifest's {@code format} value that marks a Clouseau review bundle. */
    public static final String FORMAT = "clouseau-review-bundle";
    /** The format version this class writes. */
    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * The contents of {@code manifest.json}.
     *
     * @param format           always {@link #FORMAT}
     * @param formatVersion    {@link #FORMAT_VERSION} when written
     * @param clouseauVersion  the version of Clouseau that wrote the bundle
     * @param originalFileName the log's file name where it was exported from
     * @param log              the zip entry holding the log (normally {@code originalFileName})
     * @param annotations      the zip entry holding the annotations
     * @param exportedAt       when the bundle was written, ISO-8601 UTC
     */
    public record Manifest(String format, int formatVersion, String clouseauVersion,
                           String originalFileName, String log, String annotations, String exportedAt) {}

    private ReviewBundle() {}

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * The name of the log as it goes into a bundle: the file name, or the zip entry's
     * file name. Exporting a tab that shows a bundle exports the log inside it.
     */
    public static String logName(Path file, String entry) {
        return entry == null ? file.getFileName().toString() : new ZipLogs.Entry(entry, -1).fileName();
    }

    /**
     * Writes a bundle of {@code file} (or of its zip {@code entry}) and {@code annotationsJson}
     * to {@code dest}. The log's bytes are copied as stored: a {@code .gz} stays a
     * {@code .gz}, and a zip entry is copied out of its zip unchanged. {@code dest} is
     * written through a temp file, so a failure never leaves half a bundle behind.
     * Does I/O: call off the UI thread.
     *
     * @param annotationsJson the store's {@link AnnotationStore#toJson()}
     * @throws IOException if the log can't be read, or {@code dest} can't be written or
     *                     is the file being exported
     */
    public static Manifest write(Path file, String entry, String annotationsJson,
                                 String clouseauVersion, Path dest) throws IOException {
        Path target = dest.toAbsolutePath().normalize();
        if (target.equals(file.toAbsolutePath().normalize())) {
            throw new IOException("Can't export " + file.getFileName() + " over itself");
        }
        String original = logName(file, entry);
        // The log keeps its name unless that would clash with the bundle's own entries.
        String logEntry = original.equalsIgnoreCase(MANIFEST) || original.equalsIgnoreCase(ANNOTATIONS)
                ? "log-" + original : original;
        Manifest manifest = new Manifest(FORMAT, FORMAT_VERSION, clouseauVersion, original,
                logEntry, ANNOTATIONS, Instant.now().toString());

        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp);
                 ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                putText(zip, MANIFEST, GSON.toJson(manifest));
                // Compressing a .gz again gains nothing.
                if (logEntry.toLowerCase(Locale.ROOT).endsWith(".gz")) zip.setLevel(Deflater.NO_COMPRESSION);
                zip.putNextEntry(new ZipEntry(logEntry));
                try (InputStream in = entry == null ? Files.newInputStream(file) : ZipLogs.open(file, entry)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
                zip.setLevel(Deflater.DEFAULT_COMPRESSION);
                putText(zip, ANNOTATIONS, annotationsJson);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        log.info("Exported {} for review to {}", original, target);
        return manifest;
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * The manifest of {@code zip} if it is a review bundle, or empty if it is an ordinary
     * zip (or not a zip at all). Never throws: anything unreadable is "not a bundle".
     * Does I/O: call off the UI thread where possible (it is quick: one small entry).
     */
    public static Optional<Manifest> read(Path zip) {
        if (!zip.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) return Optional.empty();
        try {
            if (ZipLogs.list(zip).stream().noneMatch(e -> e.name().equals(MANIFEST))) return Optional.empty();
            JsonObject root;
            try (Reader r = new InputStreamReader(ZipLogs.open(zip, MANIFEST), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            }
            if (!root.has("format") || !FORMAT.equals(root.get("format").getAsString())) return Optional.empty();
            Manifest m = GSON.fromJson(root, Manifest.class);
            if (m.formatVersion() > FORMAT_VERSION) {
                log.warn("{} is a version {} review bundle; this Clouseau knows version {}. Opening it anyway",
                        zip.getFileName(), m.formatVersion(), FORMAT_VERSION);
            }
            String logEntry = m.log() != null ? m.log() : m.originalFileName();
            if (logEntry == null || ZipLogs.list(zip).stream().noneMatch(e -> e.name().equals(logEntry))) {
                log.warn("{} has a review manifest but no log entry {}; opening it as an ordinary zip",
                        zip.getFileName(), logEntry);
                return Optional.empty();
            }
            String annotations = m.annotations() != null ? m.annotations() : ANNOTATIONS;
            return Optional.of(new Manifest(m.format(), m.formatVersion(), m.clouseauVersion(),
                    m.originalFileName() != null ? m.originalFileName() : logEntry,
                    logEntry, annotations, m.exportedAt()));
        } catch (Exception e) {
            log.debug("{} is not a review bundle: {}", zip.getFileName(), e.toString());
            return Optional.empty();
        }
    }

    /** True if {@code zip} is a review bundle. */
    public static boolean isBundle(Path zip) {
        return read(zip).isPresent();
    }

    /**
     * Loads the annotations for {@code file} (or its zip {@code entry}), keyed by
     * {@code key} as {@link AnnotationStore#load} would. When {@code file} is a review
     * bundle and {@code entry} is its log, and no sidecar for {@code key} has been saved
     * yet, the store starts from the bundle's {@code annotations.json}.
     * <p>
     * The bundle itself is never changed: the reviewer's own notes, added on top of the
     * bundled ones, are saved to {@code key}'s sidecar next to the bundle (or to
     * {@code fallbackDir}), and from then on that sidecar is what loads.
     * Does I/O: call off the UI thread.
     */
    public static AnnotationStore loadAnnotations(Path file, String entry, Path key, Path fallbackDir) {
        AnnotationStore store = AnnotationStore.load(key, fallbackDir);
        if (entry == null || store.hasSavedFile()) return store;
        Optional<Manifest> manifest = read(file);
        if (manifest.isEmpty() || !entry.equals(manifest.get().log())) return store;
        try (Reader r = new InputStreamReader(ZipLogs.open(file, manifest.get().annotations()),
                StandardCharsets.UTF_8)) {
            store.seed(r);
        } catch (IOException e) {
            log.warn("Review bundle {} has no readable {}", file.getFileName(), manifest.get().annotations(), e);
        }
        return store;
    }
}
