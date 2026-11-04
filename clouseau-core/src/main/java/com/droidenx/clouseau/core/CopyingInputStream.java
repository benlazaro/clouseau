package com.droidenx.clouseau.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Passes a stream through while writing every byte read to {@code copy}, unbuffered, so
 * the copy holds each byte before the reader sees it. That lets another thread read rows
 * back from the copy as soon as they are indexed (D-16, compressed files).
 *
 * <p>If writing fails (typically a full disk), the stream ends there instead of throwing:
 * the reader has then seen exactly what the copy holds. {@link #writeFailure()} says why.
 * Closing the stream closes both.
 */
public final class CopyingInputStream extends FilterInputStream {

    private final OutputStream copy;
    private IOException writeFailure;

    public CopyingInputStream(InputStream in, OutputStream copy) {
        super(in);
        this.copy = copy;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (writeFailure != null) return -1;
        int n = in.read(b, off, len);
        if (n > 0) {
            try {
                copy.write(b, off, n);
            } catch (IOException ex) {
                writeFailure = ex;
                return -1;
            }
        }
        return n;
    }

    @Override public long skip(long n) { throw new UnsupportedOperationException("every byte must be copied"); }
    @Override public boolean markSupported() { return false; }

    /** Why the copy stopped, or null if every byte read so far was written. */
    public IOException writeFailure() { return writeFailure; }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            copy.close();
        }
    }
}
