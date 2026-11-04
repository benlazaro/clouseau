package com.droidenx.clouseau.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * Opens a log for reading, whatever it's stored in: a plain file, a {@code .gz}, or one
 * file of a {@code .zip}. Text is decoded as UTF-8 with malformed bytes replaced (never an
 * error), and a leading UTF-8 byte-order mark is skipped after decompression.
 */
public final class LogReaders {

    private LogReaders() {}

    /** True for files that are read through decompression, and so can't be followed. */
    public static boolean isCompressed(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gz") || name.endsWith(".zip");
    }

    /**
     * @param entry the file to read inside a {@code .zip}, or null (for a zip: its first file)
     */
    public static BufferedReader open(Path file, String entry) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        // The BOM check is on the decompressed bytes, so a .gz of a BOM file works too.
        return new BufferedReader(new InputStreamReader(Utf8Bom.skip(openBytes(file, entry)), decoder));
    }

    /**
     * The log's bytes after decompression, exactly as stored: a BOM is not skipped. Used
     * to index a compressed log through a decompressed copy (D-16).
     *
     * @param entry the file to read inside a {@code .zip}, or null (for a zip: its first file)
     */
    public static InputStream openBytes(Path file, String entry) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".gz")) {
            return new GZIPInputStream(Files.newInputStream(file), 64 * 1024);
        } else if (entry != null) {
            return ZipLogs.open(file, entry);
        } else if (name.endsWith(".zip")) {
            ZipInputStream zis = new ZipInputStream(Files.newInputStream(file));
            zis.getNextEntry();
            return zis;
        }
        return Files.newInputStream(file);
    }

    /**
     * The log's size after decompression, or -1 if it isn't known before reading it. A zip
     * records each file's size; a {@code .gz} only keeps it modulo 4 GiB (and only for its
     * last member), so it is never trusted.
     */
    public static long uncompressedSize(Path file, String entry) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (entry == null && !name.endsWith(".zip")) {
            return isCompressed(file) ? -1 : Files.size(file);
        }
        List<ZipLogs.Entry> entries = ZipLogs.list(file);
        for (ZipLogs.Entry e : entries) {
            if (entry == null || e.name().equals(entry)) return e.size();
        }
        return -1;
    }
}
