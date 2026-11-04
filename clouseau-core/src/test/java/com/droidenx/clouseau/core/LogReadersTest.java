package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link LogReaders#open} drops a UTF-8 BOM so the first line parses (plain, .gz, .zip). */
class LogReadersTest {

    private static final byte[] BOM_TEXT = withBom("2026-03-27 08:00:00.001 INFO first\nsecond\n");

    @TempDir
    Path dir;

    private static byte[] withBom(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String firstLine(Path file) throws IOException {
        try (BufferedReader r = LogReaders.open(file, null)) {
            return r.readLine();
        }
    }

    @Test
    void plainFile() throws IOException {
        Path file = dir.resolve("app.log");
        Files.write(file, BOM_TEXT);

        assertThat(firstLine(file)).isEqualTo("2026-03-27 08:00:00.001 INFO first");
    }

    @Test
    void gzipFile() throws IOException {
        Path file = dir.resolve("app.log.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(BOM_TEXT);
        }

        assertThat(firstLine(file)).isEqualTo("2026-03-27 08:00:00.001 INFO first");
    }

    @Test
    void zipFile() throws IOException {
        Path file = dir.resolve("app.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("app.log"));
            out.write(BOM_TEXT);
            out.closeEntry();
        }

        assertThat(firstLine(file)).isEqualTo("2026-03-27 08:00:00.001 INFO first");
    }

    @Test
    void chosenZipEntry() throws IOException {
        Path file = dir.resolve("two.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("a.log"));
            out.write("from a\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("b.log"));
            out.write(BOM_TEXT);
            out.closeEntry();
        }

        try (BufferedReader r = LogReaders.open(file, "b.log")) {
            assertThat(r.readLine()).isEqualTo("2026-03-27 08:00:00.001 INFO first");
        }
    }

    @Test
    void fileWithoutBomIsUnchanged() throws IOException {
        Path file = dir.resolve("plain.log");
        Files.writeString(file, "abc\n", StandardCharsets.UTF_8);

        assertThat(firstLine(file)).isEqualTo("abc");
    }
}
