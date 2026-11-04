package com.droidenx.clouseau.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reading the logs inside a {@code .zip} (G-6): list the files, and open one of them.
 * Entry names are read as UTF-8, falling back to the old DOS code page (CP437) that
 * many Windows zip tools still use.
 */
public final class ZipLogs {

    /** A file in the archive: its full name inside the zip and its uncompressed size (-1 if unknown). */
    public record Entry(String name, long size) {
        /**
         * The part after the last slash. Backslashes count too: Windows PowerShell's
         * Compress-Archive writes them, against the zip spec.
         */
        public String fileName() {
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            return slash >= 0 ? name.substring(slash + 1) : name;
        }
    }

    private static final Charset CP437 = Charset.forName("IBM437");

    private ZipLogs() {}

    /** The files in {@code zip}, in archive order. Folders are left out. */
    public static List<Entry> list(Path zip) throws IOException {
        try (ZipFile zf = openZip(zip)) {
            List<Entry> entries = new ArrayList<>();
            for (ZipEntry e : Collections.list(zf.entries())) {
                if (!e.isDirectory()) entries.add(new Entry(e.getName(), e.getSize()));
            }
            return entries;
        }
    }

    /**
     * Opens one file in {@code zip} for reading. Closing the stream closes the archive.
     *
     * @throws IOException if the archive can't be read or has no such entry
     */
    public static InputStream open(Path zip, String entryName) throws IOException {
        ZipFile zf = openZip(zip);
        try {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("No file named " + entryName + " in " + zip.getFileName());
            }
            return new FilterInputStream(zf.getInputStream(entry)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { zf.close(); }
                }
            };
        } catch (IOException | RuntimeException e) {
            zf.close();
            throw e;
        }
    }

    /**
     * A path that stands for one entry of a zip, next to the zip: {@code app.zip!logs_server.log}.
     * Used to key per-entry state such as annotations, never opened as a file.
     */
    public static Path entryKey(Path zip, String entryName) {
        Path abs = zip.toAbsolutePath();
        String safe = entryName.replaceAll("[/\\\\:*?\"<>|]", "_");
        return abs.resolveSibling(abs.getFileName() + "!" + safe);
    }

    private static ZipFile openZip(Path zip) throws IOException {
        try {
            return new ZipFile(zip.toFile(), StandardCharsets.UTF_8);
        } catch (ZipException e) {
            // Names that aren't valid UTF-8: most likely an older Windows tool's code page.
            try {
                return new ZipFile(zip.toFile(), CP437);
            } catch (ZipException ignored) {
                throw e;
            }
        }
    }
}
