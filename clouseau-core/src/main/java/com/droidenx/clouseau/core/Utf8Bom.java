package com.droidenx.clouseau.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

/**
 * Removes the UTF-8 byte-order mark ({@code EF BB BF}) that some Windows tools, such as
 * PowerShell and Notepad, write at the start of a file. Java's UTF-8 decoder keeps it as
 * {@code U+FEFF}, which would make the first line fail to parse.
 */
public final class Utf8Bom {

    /** The BOM's encoded length in bytes. */
    public static final int LENGTH = 3;

    private Utf8Bom() {}

    /**
     * Returns a stream that yields {@code in}'s bytes, minus a leading BOM if there is
     * one. Only the very start of the stream is checked.
     */
    public static InputStream skip(InputStream in) throws IOException {
        PushbackInputStream pin = new PushbackInputStream(in, LENGTH);
        byte[] head = pin.readNBytes(LENGTH);
        if (!isBom(head, head.length)) pin.unread(head);
        return pin;
    }

    /** True if the first {@code len} bytes of {@code b} are exactly a BOM. */
    public static boolean isBom(byte[] b, int len) {
        return len == LENGTH
                && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }
}
