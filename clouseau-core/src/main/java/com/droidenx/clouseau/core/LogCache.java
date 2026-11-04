package com.droidenx.clouseau.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The folder of decompressed copies (D-16): a {@code .gz}, or a file inside a
 * {@code .zip}, is unpacked here while it is indexed, and its tab reads rows back from
 * the copy. A copy lives as long as its tab; anything left over (after a crash) is
 * removed at the next start.
 */
@Slf4j
public final class LogCache {

    /** Free space kept on top of the copy's size, so unpacking doesn't fill the disk. */
    public static final long SPARE_BYTES = 256L * 1024 * 1024;

    private LogCache() {}

    /**
     * Creates an empty file for a copy of {@code file} (or of its zip {@code entry}). The
     * name starts with a hash of both, so it can be traced back, and is unique, so two
     * tabs showing the same log never share a copy.
     */
    public static Path newCopy(Path dir, Path file, String entry) throws IOException {
        Files.createDirectories(dir);
        String key = file.toAbsolutePath() + (entry == null ? "" : "!" + entry);
        return Files.createTempFile(dir, hash(key) + "-", ".log");
    }

    /**
     * True if {@code dir}'s disk has room for {@code size} bytes plus {@link #SPARE_BYTES}.
     * An unknown size (-1) is always allowed; unpacking then stops if the disk fills.
     */
    public static boolean hasRoom(Path dir, long size) {
        if (size < 0) return true;
        try {
            Files.createDirectories(dir);
            return Files.getFileStore(dir).getUsableSpace() >= size + SPARE_BYTES;
        } catch (IOException ex) {
            log.warn("Can't check the free space in {}", dir, ex);
            return true;
        }
    }

    /** Deletes one copy. A failure is logged, and the next start removes the file. */
    public static void delete(Path copy) {
        if (copy == null) return;
        try {
            Files.deleteIfExists(copy);
        } catch (IOException ex) {
            log.warn("Can't delete the unpacked copy {}; it is removed at the next start", copy, ex);
        }
    }

    /** Deletes every copy in {@code dir}. Call at startup, before any tab opens. */
    public static void clean(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (Files.isRegularFile(f)) delete(f);
            }
        } catch (IOException ex) {
            log.warn("Can't clean {}", dir, ex);
        }
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);   // every JVM has SHA-256
        }
    }
}
