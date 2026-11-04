package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineScannerTest {

    record Line(long offset, int length, String text) {}

    private static List<Line> scan(byte[] bytes, int bufferSize) throws IOException {
        List<Line> lines = new ArrayList<>();
        long end = LineScanner.scan(new ByteArrayInputStream(bytes),
                (o, l, t) -> lines.add(new Line(o, l, t)), () -> false, bufferSize);
        assertThat(end).isEqualTo(bytes.length);
        // Every offset and length must point at exactly the line's bytes.
        for (Line line : lines) {
            byte[] at = Arrays.copyOfRange(bytes, (int) line.offset(), (int) line.offset() + line.length());
            assertThat(new String(at, StandardCharsets.UTF_8)).isEqualTo(line.text());
        }
        return lines;
    }

    private static List<Line> scan(String text) throws IOException {
        return scan(text.getBytes(StandardCharsets.UTF_8), LineScanner.BUFFER_SIZE);
    }

    private static List<String> texts(List<Line> lines) {
        return lines.stream().map(Line::text).toList();
    }

    @Test
    void everyKindOfLineEnding() throws IOException {
        List<Line> lines = scan("a\nb\r\nc\rd");

        assertThat(lines).containsExactly(
                new Line(0, 1, "a"), new Line(2, 1, "b"), new Line(5, 1, "c"), new Line(7, 1, "d"));
    }

    @Test
    void blankLinesAreSkippedButStillCountInOffsets() throws IOException {
        List<Line> lines = scan("a\n\n   \r\n\tb\n");

        assertThat(lines).containsExactly(new Line(0, 1, "a"), new Line(8, 2, "\tb"));
    }

    @Test
    void bomIsSkippedAndOffsetsCountIt() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.writeBytes("first\nsecond".getBytes(StandardCharsets.UTF_8));

        List<Line> lines = scan(out.toByteArray(), LineScanner.BUFFER_SIZE);

        assertThat(lines).containsExactly(new Line(3, 5, "first"), new Line(9, 6, "second"));
    }

    @Test
    void multiByteCharactersAndMalformedBytes() throws IOException {
        byte[] bytes = {'h', (byte) 0xC3, (byte) 0xA9, '\n', 'x', (byte) 0xFF, 'y', '\n'};

        assertThat(texts(scan(bytes, 64))).containsExactly("hé", "x�y");
    }

    @Test
    void linesAndCrLfSplitAcrossBuffers() throws IOException {
        // A 3-byte buffer splits nearly every line and puts \r and \n in different reads.
        String text = "one\r\ntwo\r\nthree is longer\rfour\n\r\nfive";

        assertThat(texts(scan(text.getBytes(StandardCharsets.UTF_8), 3)))
                .containsExactly("one", "two", "three is longer", "four", "five");
    }

    @Test
    void lineLongerThanTheBuffer() throws IOException {
        String longLine = "x".repeat(10_000);

        List<Line> lines = scan(("a\n" + longLine + "\nb").getBytes(StandardCharsets.UTF_8), 64);

        assertThat(lines).containsExactly(new Line(0, 1, "a"), new Line(2, 10_000, longLine),
                new Line(10_003, 1, "b"));
    }

    @Test
    void emptyStream() throws IOException {
        assertThat(scan("")).isEmpty();
    }

    @Test
    void cancelStopsAfterTheCurrentLine() throws IOException {
        List<String> got = new ArrayList<>();

        long end = LineScanner.scan(new ByteArrayInputStream("a\nb\nc\n".getBytes(StandardCharsets.UTF_8)),
                (o, l, t) -> got.add(t), () -> got.size() >= 2);

        assertThat(got).containsExactly("a", "b");
        assertThat(end).isEqualTo(4);
    }
}
