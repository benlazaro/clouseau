package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import com.droidenx.clouseau.api.LogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The plain-text view's core (UC-13): every line kept, read back from disk, and sniffing. */
class PlainTextLinesTest {

    @TempDir Path dir;

    private record Line(long offset, int length, String text) {}

    private static List<Line> scanAll(byte[] bytes, int bufferSize) throws IOException {
        List<Line> lines = new ArrayList<>();
        LineScanner.scanAllLines(new ByteArrayInputStream(bytes),
                (o, l, t) -> lines.add(new Line(o, l, t)), () -> false, bufferSize);
        return lines;
    }

    // ── LineScanner.scanAllLines ──────────────────────────────────────────────

    @Test
    void keepsBlankLinesSoLineNumbersMatchTheFile() throws IOException {
        byte[] bytes = "first\n\n  \r\nfourth\rfifth\r\n\nlast".getBytes(StandardCharsets.UTF_8);
        for (int buffer : new int[]{3, 7, 64 * 1024}) {
            List<Line> lines = scanAll(bytes, buffer);
            assertThat(lines).extracting(Line::text)
                    .as("buffer %d", buffer)
                    .containsExactly("first", "", "  ", "fourth", "fifth", "", "last");
        }
    }

    @Test
    void scanStillSkipsBlankLines() throws IOException {
        List<String> texts = new ArrayList<>();
        LineScanner.scan(new ByteArrayInputStream("a\n\nb\n".getBytes(StandardCharsets.UTF_8)),
                (o, l, t) -> texts.add(t), () -> false);
        assertThat(texts).containsExactly("a", "b");
    }

    @Test
    void offsetsPointAtEachLineEvenAfterABom() throws IOException {
        byte[] text = "alpha\n\nbeta\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 0xEF; bytes[1] = (byte) 0xBB; bytes[2] = (byte) 0xBF;   // UTF-8 byte-order mark
        System.arraycopy(text, 0, bytes, 3, text.length);
        List<Line> lines = scanAll(bytes, 64 * 1024);
        assertThat(lines).containsExactly(new Line(3, 5, "alpha"), new Line(9, 0, ""), new Line(10, 4, "beta"));
    }

    // ── FileTail with blank lines ─────────────────────────────────────────────

    @Test
    void followingKeepsBlankLinesButNotTheHalfOfASplitCrlf() throws IOException {
        Path file = dir.resolve("follow.txt");
        Files.writeString(file, "one\r");
        List<String> got = new ArrayList<>();
        long pos;
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            pos = FileTail.readAppendedLines(ch, 0, (o, l, t) -> got.add(t), () -> false, true);
        }
        assertThat(got).containsExactly("one");

        Files.writeString(file, "\n\ntwo\n", StandardOpenOption.APPEND);   // completes the \r\n, then a blank line
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            FileTail.readAppendedLines(ch, pos, (o, l, t) -> got.add(t), () -> false, true);
        }
        assertThat(got).containsExactly("one", "", "two");
    }

    // ── TextLines ─────────────────────────────────────────────────────────────

    private TextLines index(Path file) throws IOException {
        TextLines lines = TextLines.open(file);
        try (InputStream in = Files.newInputStream(file)) {
            LineScanner.scanAllLines(in, 0, (o, l, t) -> lines.add(o, l), () -> false);
        }
        return lines;
    }

    @Test
    void readsEveryLineBackFromDisk() throws IOException {
        Path file = dir.resolve("plain.txt");
        Files.writeString(file, "héllo wörld\n\n日本語の行\r\nlast line without break", StandardCharsets.UTF_8);
        try (TextLines lines = index(file)) {
            assertThat(lines.size()).isEqualTo(4);
            assertThat(lines.line(0)).isEqualTo("héllo wörld");
            assertThat(lines.line(1)).isEmpty();
            assertThat(lines.line(2)).isEqualTo("日本語の行");
            assertThat(lines.line(3)).isEqualTo("last line without break");
        }
    }

    @Test
    void scanReadsRangesAcrossBlocks() throws IOException {
        Path file = dir.resolve("big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append("line number ").append(i).append(i % 7 == 0 ? "\n\n" : "\n");
        Files.writeString(file, sb);
        try (TextLines lines = index(file)) {
            List<String> seen = new ArrayList<>();
            lines.scan(0, lines.size(), (n, t) -> seen.add(t), () -> false);
            assertThat(seen).hasSize(lines.size());
            for (int i = 0; i < seen.size(); i += 997) assertThat(seen.get(i)).isEqualTo(lines.line(i));

            List<Integer> numbers = new ArrayList<>();
            lines.scan(10, 13, (n, t) -> numbers.add(n), () -> false);
            assertThat(numbers).containsExactly(10, 11, 12);
        }
    }

    @Test
    void growsPastOneChunkWhileReadable() throws IOException {
        Path file = dir.resolve("many.txt");
        Files.writeString(file, "x\n".repeat(70_000));
        try (TextLines lines = index(file)) {
            assertThat(lines.size()).isEqualTo(70_000);
            assertThat(lines.line(69_999)).isEqualTo("x");
        }
    }

    // ── LogSniffer ────────────────────────────────────────────────────────────

    private static LogParser parserFor(String prefix) {
        return new LogParser() {
            @Override public String getName() { return prefix; }
            @Override public boolean canParse(String rawLine) { return rawLine.startsWith(prefix); }
            @Override public LogEntry parse(String rawLine) { return null; }
        };
    }

    @Test
    void detectsTheFirstParserThatRecognizesALine() {
        LogParser a = parserFor("A:"), b = parserFor("B:");
        List<String> lines = List.of("", "garbage", "B: hello", "A: later");

        assertThat(LogSniffer.detect(lines, Optional.empty(), List.of(a, b))).contains(b);
        assertThat(LogSniffer.detect(lines, Optional.of(a), List.of(a, b))).contains(a);
        assertThat(LogSniffer.detect(List.of("nothing", "here"), Optional.empty(), List.of(a, b))).isEmpty();
        assertThat(LogSniffer.detect(List.of("B: only b"), Optional.of(a), List.of(a, b))).isEmpty();
    }

    @Test
    void sampleTakesAtMostFiftyLinesAndDropsACutLine() throws IOException {
        Path file = dir.resolve("long.txt");
        Files.writeString(file, "line\n".repeat(80));
        assertThat(LogSniffer.read(file, null).lines()).hasSize(50);

        LogSniffer.Sample cut = LogSniffer.sample("one\ntwo\npartial", true);
        assertThat(cut.lines()).containsExactly("one", "two");
        LogSniffer.Sample whole = LogSniffer.sample("one\ntwo\nend", false);
        assertThat(whole.lines()).containsExactly("one", "two", "end");
        assertThat(LogSniffer.sample("no breaks at all", true).lines()).containsExactly("no breaks at all");
    }

    @Test
    void tellsBinaryFromText() throws IOException {
        assertThat(LogSniffer.looksBinary("plain text\twith tabs\r\nand lines")).isFalse();
        assertThat(LogSniffer.looksBinary("PK\u0003\u0004\u0000\u0000\u0008\u0000binary")).isTrue();

        Path bin = dir.resolve("data.bin");
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        Files.write(bin, bytes);
        assertThat(LogSniffer.read(bin, null).binary()).isTrue();

        Path empty = dir.resolve("empty.log");
        Files.writeString(empty, "\n\n");
        assertThat(LogSniffer.read(empty, null).empty()).isTrue();
    }
}
