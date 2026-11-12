package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Follows one local file (UC-6, D-16): reads what is appended, parses it, adds each line
 * to the file's index (if it has one) and hands the entry on. It keeps the byte position
 * between runs, so following can stop and start again with the Follow toggle without
 * losing or repeating a line. Single-file tabs and every file of a merged view follow
 * through here (G-11). No Swing.
 * <p>
 * A cancelled run may still be finishing a read when the next one starts; {@link #run}
 * waits for it, so only one thread ever adds to the index.
 */
@Slf4j
public final class IndexedFollow {

    /** What a run reports, on its thread. */
    public interface Listener {
        /** A line was read (and added to the index, if there is one). */
        void entry(LogEntry entry);

        /** The file was replaced or truncated, and older indexed rows lost their text. */
        void textLost();
    }

    private final Path file;
    private volatile long position;
    private final ReentrantLock lock = new ReentrantLock();

    /** Follows {@code file} from byte {@code start}. */
    public IndexedFollow(Path file, long start) {
        this.file = file;
        this.position = start;
    }

    public Path file() { return file; }

    /** Where the next run starts: just after the last line handed on. */
    public long position() { return position; }

    /** Moves where the next run starts, e.g. to the end of the file after a Clear. */
    public void setPosition(long position) { this.position = position; }

    /**
     * Follows until {@code cancelled} or the thread is interrupted (which ends the wait at
     * once).
     *
     * @param entries the index new lines go into, or null if the tab holds them in memory
     */
    public void run(IndexedEntries entries, LineParser parser, Listener listener, BooleanSupplier cancelled,
                    long pollMillis) {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            return;                         // cancelled while the last run finished
        }
        try {
            if (cancelled.getAsBoolean()) return;
            FileFollower.follow(file, position, new FileFollower.Listener() {
                @Override
                public void line(long offset, int length, String text) {
                    LogEntry e = parser.parse(text);
                    if (entries != null) entries.index().add(offset, length, e);
                    listener.entry(e);
                }

                @Override
                public void blank() {
                    if (entries != null) entries.index().addBlank();
                }

                @Override
                public void rotated(FileFollower.Rotation how) {
                    log.info("{} was rotated ({})", file, how);
                    if (entries != null && entries.rotated()) listener.textLost();
                }

                @Override
                public void position(long p) {
                    if (!cancelled.getAsBoolean()) position = p;
                }
            }, cancelled, pollMillis);
        } catch (InterruptedException e) {
            // interrupted: following stopped
        } finally {
            lock.unlock();
        }
    }
}
