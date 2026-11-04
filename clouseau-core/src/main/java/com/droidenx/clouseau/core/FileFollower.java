package com.droidenx.clouseau.core;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Follows a local file (UC-6, D-3): reads what is appended after a position, then waits
 * for the OS to report the next change ({@link FileChangeWatcher}), with a slow read as
 * a backstop.
 * <p>
 * The file is read through a channel held open on it, so rotation can be told apart
 * (D-16):
 * <ul>
 *   <li><b>Renamed</b> (logrotate's default, log4j/logback rolling): the path now names a
 *       new file. What was still written to the old one is read first, then the new file
 *       is followed from its start.</li>
 *   <li><b>Truncated in place</b> (copytruncate): the file shrank below the position, so
 *       the old text is gone; it is followed again from its start.</li>
 * </ul>
 * The channel comes from {@link Files#newByteChannel}, which on Windows shares delete
 * access, so holding it doesn't stop the writer from renaming the file.
 */
public final class FileFollower {

    /** How a followed file was rotated. */
    public enum Rotation {
        /** The path names a new file; the old one still has its text under another name. */
        RENAMED,
        /** The file was cut short in place; the text before the cut is gone. */
        TRUNCATED
    }

    /** Receives what {@link #follow(Path, long, Listener, BooleanSupplier, long)} reads. Called on its thread. */
    public interface Listener {
        /**
         * A complete line; blank lines only when following with {@code keepBlank}.
         *
         * @param offset where it starts in the file being followed now, in bytes
         * @param length its length in bytes, without its line ending
         */
        void line(long offset, int length, String text);

        /**
         * The file was rotated. Lines after this call are from the start of the file the
         * path names now.
         */
        void rotated(Rotation how);

        /** The position following resumes from, after every read and after a rotation. */
        void position(long position);
    }

    private FileFollower() {}

    /**
     * Follows {@code file} with only the text of each line, for tabs that hold entries in
     * memory. Runs until {@code cancelled} is true or the thread is interrupted (which ends
     * the wait at once).
     *
     * @param start      the byte position to read from (usually the size at load time)
     * @param onPosition told the new position after every read, so following can resume there
     * @param lines      receives each complete appended line
     * @param pollMillis the backstop: read at least this often even without a notification
     */
    public static void follow(Path file, long start, LongConsumer onPosition, Consumer<String> lines,
                              BooleanSupplier cancelled, long pollMillis) throws InterruptedException {
        follow(file, start, new Listener() {
            @Override public void line(long offset, int length, String text) { lines.accept(text); }
            @Override public void rotated(Rotation how) {}
            @Override public void position(long position) { onPosition.accept(position); }
        }, cancelled, pollMillis);
    }

    /**
     * Follows {@code file} from byte position {@code start}, reporting each line with its
     * position, and each rotation. Runs until {@code cancelled} is true or the thread is
     * interrupted (which ends the wait at once). {@code cancelled} is checked after every
     * line, and the position reported covers exactly the lines handed over.
     *
     * @param pollMillis the backstop: read at least this often even without a notification
     */
    public static void follow(Path file, long start, Listener listener, BooleanSupplier cancelled,
                              long pollMillis) throws InterruptedException {
        follow(file, start, listener, cancelled, pollMillis, false);
    }

    /**
     * Like {@link #follow(Path, long, Listener, BooleanSupplier, long)}; with
     * {@code keepBlank}, blank lines are reported too (the plain-text view, UC-13).
     */
    public static void follow(Path file, long start, Listener listener, BooleanSupplier cancelled,
                              long pollMillis, boolean keepBlank) throws InterruptedException {
        long position = start;
        SeekableByteChannel channel = null;
        Object fileKey = null;
        try (FileChangeWatcher watcher = FileChangeWatcher.watch(file)) {
            while (!cancelled.getAsBoolean()) {
                try {
                    if (channel == null) {
                        channel = Files.newByteChannel(file);
                        fileKey = fileKey(file);
                    }
                    if (channel.size() < position) {
                        // The file we hold shrank: truncated in place, the old text is gone.
                        position = 0;
                        listener.rotated(Rotation.TRUNCATED);
                        listener.position(position);
                    }
                    long read = FileTail.readAppendedLines(channel, position, listener::line, cancelled, keepBlank);
                    if (read != position) {
                        position = read;
                        listener.position(position);
                    }
                    if (!cancelled.getAsBoolean() && replaced(file, fileKey, position)) {
                        // Read what was written to the old file since, then switch to the new one.
                        read = FileTail.readAppendedLines(channel, position, listener::line, cancelled, keepBlank);
                        if (read != position) {
                            position = read;
                            listener.position(position);
                        }
                        if (cancelled.getAsBoolean()) break;
                        SeekableByteChannel old = channel;
                        channel = null;
                        old.close();
                        position = 0;
                        listener.rotated(Rotation.RENAMED);
                        listener.position(position);
                        continue;                   // read the new file at once
                    }
                } catch (IOException ignored) {
                    // The file may be missing for a moment during rotation; try again.
                    // An interrupt during a read closes the channel: open it again next time.
                    if (channel != null && !channel.isOpen()) channel = null;
                }
                watcher.awaitChange(pollMillis);
            }
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * True if the path no longer names the file being read: it is smaller than what was
     * read from the held one, or (where the platform has file keys) it is another file.
     * False while nothing is at the path (renamed, new file not created yet).
     */
    private static boolean replaced(Path file, Object heldKey, long position) throws IOException {
        BasicFileAttributes now;
        try {
            now = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            return false;
        }
        if (heldKey != null && now.fileKey() != null && !Objects.equals(heldKey, now.fileKey())) return true;
        return now.size() < position;
    }

    private static Object fileKey(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        } catch (IOException e) {
            return null;
        }
    }
}
