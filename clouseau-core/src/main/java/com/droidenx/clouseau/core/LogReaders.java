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
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream in;
        if (name.endsWith(".gz")) {
            in = new GZIPInputStream(Files.newInputStream(file));
        } else if (entry != null) {
            in = ZipLogs.open(file, entry);
        } else if (name.endsWith(".zip")) {
            ZipInputStream zis = new ZipInputStream(Files.newInputStream(file));
            zis.getNextEntry();
            in = zis;
        } else {
            in = Files.newInputStream(file);
        }
        // The BOM check is on the decompressed bytes, so a .gz of a BOM file works too.
        return new BufferedReader(new InputStreamReader(Utf8Bom.skip(in), decoder));
    }
}
