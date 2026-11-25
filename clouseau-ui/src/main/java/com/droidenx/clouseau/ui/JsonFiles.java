package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.Utf8Bom;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reading and writing the JSON files in the data folder ({@code settings.json},
 * {@code parsers.json}): UTF-8 with a leading BOM skipped (a file saved by Notepad), and
 * written through a temporary file moved into place, so a crash or a full disk mid-write
 * leaves the previous file rather than half of one (DESIGN.md 7).
 */
final class JsonFiles {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFiles() {}

    /**
     * The JSON in {@code file}.
     *
     * @throws IOException if it can't be read
     * @throws com.google.gson.JsonParseException if it isn't JSON (damaged or cut short)
     */
    static JsonElement read(Path file) throws IOException {
        try (InputStream in = Utf8Bom.skip(Files.newInputStream(file));
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r);
        }
    }

    /** Writes {@code json} to {@code file}, pretty-printed, through {@code <file>.tmp}. */
    static void write(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(json, w);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Moves {@code file} aside to {@code <file>.bad}, replacing an older one, so a file this
     * version can't read is kept for the user rather than overwritten.
     */
    static Path moveAside(Path file) throws IOException {
        Path bad = file.resolveSibling(file.getFileName() + ".bad");
        Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        return bad;
    }

    /** Copies {@code file} to {@code <file>.bad}, replacing an older one. */
    static Path copyAside(Path file) throws IOException {
        Path bad = file.resolveSibling(file.getFileName() + ".bad");
        Files.copy(file, bad, StandardCopyOption.REPLACE_EXISTING);
        return bad;
    }
}
