package com.droidenx.clouseau.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/**
 * Splits a log into lines as <b>bytes</b>, reporting where each line starts in the stream
 * and how long it is, so its text can be read back later with one positioned read (D-16).
 * <p>
 * Lines end at {@code \n}, {@code \r\n} or a lone {@code \r}, the same as
 * {@link java.io.BufferedReader#readLine()}, so the lines (and the entry numbers that
 * annotations anchor to, D-9) are the same as {@link LogFileReader}'s. Splitting bytes is
 * safe for UTF-8: neither byte occurs inside a multi-byte character. Each line is decoded
 * as UTF-8 with malformed bytes replaced. A leading byte-order mark is skipped (offsets
 * still count it, so they are true positions in the stream), and blank lines are skipped.
 */
public final class LineScanner {

    /** Receives one non-blank line. */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param offset where the line starts, in bytes from the start of the stream
         * @param length the line's length in bytes, without its line ending
         * @param text   the line decoded as UTF-8
         */
        void line(long offset, int length, String text);
    }

    static final int BUFFER_SIZE = 64 * 1024;

    private LineScanner() {}

    /**
     * Reads {@code in} to the end, or until {@code cancelled} is true (checked after
     * every line). Doesn't close the stream.
     *
     * @return the number of bytes read, which is where a follow should resume
     */
    public static long scan(InputStream in, Sink sink, BooleanSupplier cancelled) throws IOException {
        return scan(in, 0, sink, cancelled, BUFFER_SIZE, false);
    }

    /**
     * Like {@link #scan(InputStream, Sink, BooleanSupplier)}, for a stream that starts
     * {@code start} bytes into the file (following, D-16): offsets are counted from the
     * start of the file, and a byte-order mark is skipped only when {@code start} is 0.
     *
     * @return the file position just past what was read
     */
    public static long scan(InputStream in, long start, Sink sink, BooleanSupplier cancelled) throws IOException {
        return scan(in, start, sink, cancelled, BUFFER_SIZE, false);
    }

    /**
     * Like {@link #scan(InputStream, long, Sink, BooleanSupplier)}, but blank lines are passed
     * on too, so every line of the file is reported and line numbers match an editor's (the
     * plain-text view, UC-13).
     */
    public static long scanAllLines(InputStream in, long start, Sink sink, BooleanSupplier cancelled)
            throws IOException {
        return scan(in, start, sink, cancelled, BUFFER_SIZE, true);
    }

    static long scan(InputStream in, Sink sink, BooleanSupplier cancelled, int bufferSize) throws IOException {
        return scan(in, 0, sink, cancelled, bufferSize, false);
    }

    static long scanAllLines(InputStream in, Sink sink, BooleanSupplier cancelled, int bufferSize) throws IOException {
        return scan(in, 0, sink, cancelled, bufferSize, true);
    }

    private static long scan(InputStream in, long start, Sink sink, BooleanSupplier cancelled, int bufferSize,
                             boolean keepBlank) throws IOException {
        byte[] buf = new byte[Math.max(bufferSize, Utf8Bom.LENGTH)];
        // Holds the start of a line that runs past the end of the buffer.
        byte[] carry = new byte[256];
        int carryLen = 0;

        long base = start;         // stream position of buf[0]
        int n = in.readNBytes(buf, 0, buf.length);
        int i = start == 0 && Utf8Bom.isBom(buf, Math.min(n, Utf8Bom.LENGTH)) ? Utf8Bom.LENGTH : 0;
        long lineStart = base + i;
        boolean skipLf = false;    // the last byte was \r; a \n right after it is the same ending

        while (n > 0) {
            int segStart = i;
            for (; i < n; i++) {
                byte b = buf[i];
                if (skipLf) {
                    skipLf = false;
                    if (b == '\n') {
                        segStart = i + 1;
                        lineStart = base + i + 1;
                        continue;
                    }
                }
                if (b != '\n' && b != '\r') continue;

                if (carryLen == 0) {
                    emit(sink, lineStart, buf, segStart, i - segStart, keepBlank);
                } else {
                    carry = append(carry, carryLen, buf, segStart, i - segStart);
                    carryLen += i - segStart;
                    emit(sink, lineStart, carry, 0, carryLen, keepBlank);
                    carryLen = 0;
                }
                if (cancelled.getAsBoolean()) return base + i + 1;
                skipLf = b == '\r';
                segStart = i + 1;
                lineStart = base + i + 1;
            }
            if (segStart < n) {
                carry = append(carry, carryLen, buf, segStart, n - segStart);
                carryLen += n - segStart;
            }
            base += n;
            n = in.read(buf);
            i = 0;
        }
        if (carryLen > 0) emit(sink, lineStart, carry, 0, carryLen, keepBlank);
        return base;
    }

    private static byte[] append(byte[] dest, int destLen, byte[] src, int from, int len) {
        if (destLen + len > dest.length) {
            dest = Arrays.copyOf(dest, Math.max(dest.length * 2, destLen + len));
        }
        System.arraycopy(src, from, dest, destLen, len);
        return dest;
    }

    private static void emit(Sink sink, long offset, byte[] bytes, int from, int len, boolean keepBlank) {
        // new String(..., UTF_8) replaces malformed input, like the decoder LogReaders uses.
        String text = new String(bytes, from, len, StandardCharsets.UTF_8);
        if (keepBlank || !text.isBlank()) sink.line(offset, len, text);
    }
}
