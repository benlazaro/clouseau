package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Indexes one log file (D-16): reads it once, parses every line into an {@link EntryIndex},
 * anchors the file's notes to its entries (D-9), and hands each entry to a sink. A
 * compressed file or zip entry is unpacked to {@code copy} in the same pass, and rows are
 * read back from that copy. Single-file tabs and merged views both index through here
 * (G-11), so a file reads the same either way. No Swing; runs on the caller's thread.
 */
@Slf4j
public final class FileIndexer {

    private FileIndexer() {}

    /** Receives every entry as it is indexed, on the indexing thread. */
    @FunctionalInterface
    public interface Sink {
        void entry(LogEntry entry);
    }

    /**
     * How indexing ended.
     *
     * @param end       the byte position after the last indexed line, where following resumes;
     *                  -1 for a compressed file (it isn't followed)
     * @param lines     lines indexed
     * @param lowMemory true if it stopped early because memory ran low
     * @param diskFull  true if it stopped early because the unpacked copy couldn't be written
     */
    public record Result(long end, long lines, boolean lowMemory, boolean diskFull) {
        /** True if the index doesn't cover the whole file, for lack of memory or disk. */
        public boolean partial() {
            return lowMemory || diskFull;
        }
    }

    /**
     * Indexes {@code file} (or {@code entry} of the zip {@code file}) into {@code index}.
     *
     * @param copy      where a compressed file is unpacked to, or null for a plain file
     * @param notes     the file's notes, anchored as lines are read; null for none
     * @param lowMemory checked every {@link LogFileReader#DEFAULT_CHECK_INTERVAL} lines; true stops
     * @param halt      checked often; true stops (a cancel, or another file of a merge stopped)
     */
    public static Result index(Path file, String entry, Path copy, LineParser parser, EntryIndex index,
                               AnnotationStore notes, Sink sink, BooleanSupplier lowMemory,
                               BooleanSupplier halt) throws IOException {
        AnnotationStore.Anchoring anchoring = notes == null ? null : notes.startAnchoring();
        long[] count = {0};
        boolean[] low = {false};
        InputStream source = copy != null
                ? new CopyingInputStream(LogReaders.openBytes(file, entry), Files.newOutputStream(copy))
                : Files.newInputStream(file);
        long end;
        try (InputStream in = source) {
            end = LineScanner.scan(in, (offset, length, text) -> {
                LogEntry e = parser.parse(text);
                index.add(offset, length, e);
                if (anchoring != null) anchoring.accept(e.rawLine());
                sink.entry(e);
                if (++count[0] % LogFileReader.DEFAULT_CHECK_INTERVAL == 0 && lowMemory.getAsBoolean()) {
                    low[0] = true;
                }
            }, () -> low[0] || halt.getAsBoolean());
        }
        boolean diskFull = false;
        if (source instanceof CopyingInputStream c && c.writeFailure() != null && !halt.getAsBoolean()) {
            log.warn("Stopped unpacking {} to {} after {} lines", entry == null ? file : file + " › " + entry,
                    copy, count[0], c.writeFailure());
            diskFull = true;
        }
        if (anchoring != null) anchoring.finish();
        return new Result(copy != null ? -1 : end, count[0], low[0], diskFull);
    }
}
