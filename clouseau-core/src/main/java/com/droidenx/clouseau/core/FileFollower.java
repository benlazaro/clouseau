package com.droidenx.clouseau.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Follows a local file (UC-6, D-3): reads what is appended after a position, then waits
 * for the OS to report the next change ({@link FileChangeWatcher}), with a slow read as
 * a backstop. If the file shrinks it was rotated, so reading starts again at the top.
 */
public final class FileFollower {

    private FileFollower() {}

    /**
     * Runs until {@code cancelled} is true or the thread is interrupted (which ends the wait
     * at once).
     *
     * @param start      the byte position to read from (usually the size at load time)
     * @param onPosition told the new position after every read, so following can resume there
     * @param lines      receives each complete appended line
     * @param pollMillis the backstop: read at least this often even without a notification
     */
    public static void follow(Path file, long start, LongConsumer onPosition, Consumer<String> lines,
                              BooleanSupplier cancelled, long pollMillis) throws InterruptedException {
        long position = start;
        try (FileChangeWatcher watcher = FileChangeWatcher.watch(file)) {
            while (!cancelled.getAsBoolean()) {
                try {
                    long size = Files.size(file);
                    if (size < position) {
                        position = 0;               // rotated: the new file starts from the top
                        onPosition.accept(position);
                    }
                    if (size > position) {
                        position = FileTail.readAppendedLines(file, position, line -> {
                            if (!cancelled.getAsBoolean()) lines.accept(line);
                        });
                        onPosition.accept(position);
                    }
                } catch (IOException ignored) {
                    // The file may be missing for a moment during rotation; try again.
                }
                watcher.awaitChange(pollMillis);
            }
        }
    }
}
