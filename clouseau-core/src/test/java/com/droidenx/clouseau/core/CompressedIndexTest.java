package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** D-16 step 4: a compressed log is indexed through a decompressed copy in the cache. */
class CompressedIndexTest {

    private static final LineParser PARSER = new LineParser(Optional.empty(), List.of(new RegexLogParser("t",
            Pattern.compile("(?<timestamp>\\S+ \\S+) (?<level>[A-Z]+) (?<message>.*)"), "yyyy-MM-dd HH:mm:ss")), null);

    @TempDir Path dir;

    private static byte[] logBytes() {
        StringBuilder sb = new StringBuilder().append((char) 0xFEFF);   // a BOM, then mixed line ends
        for (int i = 0; i < 20_000; i++) {
            sb.append("2026-11-04 10:00:00 ").append(i % 3 == 0 ? "ERROR" : "INFO").append(" event ").append(i)
                    .append(" ünïcode").append(i % 2 == 0 ? "\r\n" : "\n");
            if (i % 100 == 0) sb.append("  at com.acme.Frame").append(i).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Path gz(byte[] bytes) throws IOException {
        Path file = dir.resolve("app.log.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(bytes);
        }
        return file;
    }

    /** Indexes {@code file} as LogPanel does, checking each row is readable as soon as it's indexed. */
    private static EntryIndex indexThroughCopy(Path file, String entry, Path copy) throws IOException {
        EntryIndex index = new EntryIndex(4);
        try (IndexedEntries rows = IndexedEntries.open(copy, index, PARSER);
             InputStream in = new CopyingInputStream(LogReaders.openBytes(file, entry), Files.newOutputStream(copy))) {
            LineScanner.scan(in, (offset, length, text) -> {
                LogEntry e = PARSER.parse(text);
                index.add(offset, length, e);
                assertThat(rows.get(index.size() - 1).rawLine()).isEqualTo(text);
            }, () -> false);
        }
        return index;
    }

    private static List<LogEntry> readerEntries(Path file, String entry) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        try (BufferedReader reader = LogReaders.open(file, entry)) {
            LogFileReader.read(reader, PARSER, LogFileReader.DEFAULT_CHECK_INTERVAL,
                    () -> false, () -> false, entries::add);
        }
        return entries;
    }

    @Test
    void gzipCopyGivesTheSameEntriesAsTheReader() throws Exception {
        byte[] bytes = logBytes();
        Path file = gz(bytes);
        Path copy = LogCache.newCopy(dir.resolve("cache"), file, null);

        EntryIndex index = indexThroughCopy(file, null, copy);

        assertThat(Files.readAllBytes(copy)).isEqualTo(bytes);   // byte-for-byte, BOM included
        List<LogEntry> expected = readerEntries(file, null);
        assertThat(index.size()).isEqualTo(expected.size());
        try (IndexedEntries rows = IndexedEntries.open(copy, index, PARSER)) {
            for (int i = 0; i < expected.size(); i += 97) {
                assertThat(rows.get(i)).as("entry " + i).isEqualTo(expected.get(i));
            }
            assertThat(rows.get(expected.size() - 1)).isEqualTo(expected.get(expected.size() - 1));
        }
    }

    @Test
    void zipEntryCopyGivesTheSameEntriesAsTheReader() throws Exception {
        byte[] bytes = logBytes();
        Path file = dir.resolve("logs.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("other.log"));
            zip.write("2026-11-04 10:00:00 INFO not this one\n".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("logs/app.log"));
            zip.write(bytes);
        }
        Path copy = LogCache.newCopy(dir.resolve("cache"), file, "logs/app.log");

        EntryIndex index = indexThroughCopy(file, "logs/app.log", copy);

        assertThat(LogReaders.uncompressedSize(file, "logs/app.log")).isEqualTo(bytes.length);
        List<LogEntry> expected = readerEntries(file, "logs/app.log");
        assertThat(index.size()).isEqualTo(expected.size());
        try (IndexedEntries rows = IndexedEntries.open(copy, index, PARSER)) {
            assertThat(rows.get(1)).isEqualTo(expected.get(1));
            assertThat(rows.get(expected.size() - 1)).isEqualTo(expected.get(expected.size() - 1));
        }
    }

    @Test
    void openBytesKeepsTheBomAndSizeIsOnlyKnownForZips() throws Exception {
        byte[] bytes = logBytes();
        Path file = gz(bytes);
        try (InputStream in = LogReaders.openBytes(file, null)) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(LogReaders.uncompressedSize(file, null)).isEqualTo(-1);
        Path plain = Files.write(dir.resolve("plain.log"), bytes);
        assertThat(LogReaders.uncompressedSize(plain, null)).isEqualTo(bytes.length);
    }

    @Test
    void aFailedWriteEndsTheStreamAtWhatWasCopied() throws Exception {
        byte[] bytes = new byte[10_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ('a' + i % 26);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        OutputStream fillsUp = new OutputStream() {
            @Override public void write(int b) { throw new UnsupportedOperationException(); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (copied.size() + len > 4_000) throw new IOException("There is not enough space on the disk");
                copied.write(b, off, len);
            }
        };

        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        try (CopyingInputStream in = new CopyingInputStream(new ByteArrayInputStream(bytes), fillsUp)) {
            byte[] buf = new byte[1_000];
            for (int n; (n = in.read(buf)) > 0; ) seen.write(buf, 0, n);
            assertThat(in.writeFailure()).hasMessageContaining("space");
            assertThat(in.read()).isEqualTo(-1);
        }

        assertThat(seen.toByteArray()).isEqualTo(copied.toByteArray()).hasSize(4_000);
    }

    @Test
    void copiesAreUniqueAndCleanedAway() throws Exception {
        Path cache = dir.resolve("cache");
        Path file = dir.resolve("app.log.gz");
        Path a = LogCache.newCopy(cache, file, null);
        Path b = LogCache.newCopy(cache, file, null);
        Path c = LogCache.newCopy(cache, file, "entry.log");

        assertThat(a).isNotEqualTo(b);
        assertThat(a.getFileName().toString()).startsWith(b.getFileName().toString().substring(0, 17));
        assertThat(c.getFileName().toString()).doesNotStartWith(a.getFileName().toString().substring(0, 17));

        LogCache.delete(a);
        assertThat(a).doesNotExist();
        LogCache.clean(cache);
        assertThat(cache).isEmptyDirectory();
        LogCache.clean(dir.resolve("never-made"));   // no folder: nothing to do
    }

    @Test
    void roomIsCheckedOnlyWhenTheSizeIsKnown() {
        assertThat(LogCache.hasRoom(dir, -1)).isTrue();
        assertThat(LogCache.hasRoom(dir, 1)).isTrue();
        assertThat(LogCache.hasRoom(dir, Long.MAX_VALUE / 2)).isFalse();
    }
}
