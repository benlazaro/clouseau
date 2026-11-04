package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileTailTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("app.log");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static void append(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.APPEND);
    }

    @Test
    void decodesUtf8() throws IOException {
        Path file = write("Café ünïcødé 日本語 🚀\n");
        List<String> lines = new ArrayList<>();

        FileTail.readAppendedLines(file, 0, lines::add);

        assertThat(lines).containsExactly("Café ünïcødé 日本語 🚀");
    }

    @Test
    void readsOnlyBytesAfterOffset() throws IOException {
        Path file = write("first\n");
        long offset = Files.size(file);
        append(file, "second\nthird\n".getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();

        long next = FileTail.readAppendedLines(file, offset, lines::add);

        assertThat(lines).containsExactly("second", "third");
        assertThat(next).isEqualTo(Files.size(file));
    }

    @Test
    void holdsBackIncompleteLastLine() throws IOException {
        Path file = write("complete\npart");
        List<String> lines = new ArrayList<>();

        long next = FileTail.readAppendedLines(file, 0, lines::add);
        assertThat(lines).containsExactly("complete");
        assertThat(next).isEqualTo("complete\n".length());

        append(file, "ial\n".getBytes(StandardCharsets.UTF_8));
        FileTail.readAppendedLines(file, next, lines::add);
        assertThat(lines).containsExactly("complete", "partial");
    }

    @Test
    void holdsBackLineEndingMidCharacter() throws IOException {
        byte[] euro = "€\n".getBytes(StandardCharsets.UTF_8); // 3-byte character + newline
        Path file = dir.resolve("app.log");
        Files.write(file, new byte[] { euro[0], euro[1] });
        List<String> lines = new ArrayList<>();

        long next = FileTail.readAppendedLines(file, 0, lines::add);
        assertThat(lines).isEmpty();
        assertThat(next).isZero();

        append(file, new byte[] { euro[2], euro[3] });
        FileTail.readAppendedLines(file, next, lines::add);
        assertThat(lines).containsExactly("€");
    }

    @Test
    void stripsCrlfAndSkipsBlankLines() throws IOException {
        Path file = write("one\r\n\r\n   \ntwo\r\n");
        List<String> lines = new ArrayList<>();

        FileTail.readAppendedLines(file, 0, lines::add);

        assertThat(lines).containsExactly("one", "two");
    }

    @Test
    void returnsOffsetUnchangedWhenNothingNew() throws IOException {
        Path file = write("only\n");
        long size = Files.size(file);
        List<String> lines = new ArrayList<>();

        assertThat(FileTail.readAppendedLines(file, size, lines::add)).isEqualTo(size);
        assertThat(lines).isEmpty();
    }

    private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    @Test
    void skipsBomAtStartOfFile() throws IOException {
        Path file = dir.resolve("app.log");
        Files.write(file, BOM);
        append(file, "first\nsecond\n".getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();

        long next = FileTail.readAppendedLines(file, 0, lines::add);

        assertThat(lines).containsExactly("first", "second");
        assertThat(next).isEqualTo(Files.size(file));
    }

    @Test
    void bomOnlyFileHoldsItsPositionUntilALineArrives() throws IOException {
        Path file = dir.resolve("app.log");
        Files.write(file, BOM);
        List<String> lines = new ArrayList<>();

        long next = FileTail.readAppendedLines(file, 0, lines::add);
        assertThat(lines).isEmpty();

        append(file, "hello\n".getBytes(StandardCharsets.UTF_8));
        FileTail.readAppendedLines(file, next, lines::add);
        assertThat(lines).containsExactly("hello");
    }

    @Test
    void bomBytesAfterOffsetZeroAreLeftAlone() throws IOException {
        // Only the start of the file is checked; mid-file U+FEFF is data.
        Path file = write("first\n");
        long offset = Files.size(file);
        append(file, BOM);
        append(file, "second\n".getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();

        FileTail.readAppendedLines(file, offset, lines::add);

        assertThat(lines).containsExactly("﻿second");
    }
}
