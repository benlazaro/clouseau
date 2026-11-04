package com.droidenx.clouseau.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * Waits for one file to change, using the operating system's change notifications
 * (D-3, follow mode). A follower calls {@link #awaitChange} between reads: it returns as
 * soon as the file is written, created or replaced, or after the timeout, so a file system
 * that doesn't send notifications (some network drives) still gets read at that interval.
 * <p>
 * If the folder can't be watched at all, {@code awaitChange} simply sleeps for the
 * timeout. Not thread-safe; {@link #close} may be called from any thread.
 */
@Slf4j
public final class FileChangeWatcher implements AutoCloseable {

    private final Path fileName;
    private final WatchService service;
    private WatchKey key;

    private FileChangeWatcher(Path fileName, WatchService service, WatchKey key) {
        this.fileName = fileName;
        this.service  = service;
        this.key      = key;
    }

    /** Starts watching {@code file}'s folder for changes to {@code file}. Never throws. */
    public static FileChangeWatcher watch(Path file) {
        Path abs = file.toAbsolutePath();
        WatchService service = null;
        try {
            service = FileSystems.getDefault().newWatchService();
            WatchKey key = abs.getParent().register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            return new FileChangeWatcher(abs.getFileName(), service, key);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.info("Can't watch {} for changes ({}); following it by polling", abs, e.toString());
            if (service != null) {
                try { service.close(); } catch (IOException ignored) {}
            }
            return new FileChangeWatcher(abs.getFileName(), null, null);
        }
    }

    /** True when change notifications are in use, false when only the timeout applies. */
    public boolean isNotified() {
        return key != null && key.isValid();
    }

    /**
     * Blocks until the file changes or {@code timeoutMillis} passes.
     *
     * @return true if a change to the file was reported (or events were lost, so it may
     *         have changed), false on timeout
     * @throws InterruptedException if the thread is interrupted, e.g. when following stops
     */
    public boolean awaitChange(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            if (!isNotified()) {
                TimeUnit.NANOSECONDS.sleep(left);
                return false;
            }
            WatchKey signalled;
            try {
                signalled = service.poll(left, TimeUnit.NANOSECONDS);
            } catch (ClosedWatchServiceException e) {
                key = null;
                continue;
            }
            if (signalled == null) return false;
            boolean ours = false;
            for (WatchEvent<?> event : signalled.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW
                        || fileName.equals(event.context())) {
                    ours = true;
                }
            }
            if (!signalled.reset()) key = null;   // folder gone: fall back to the timeout
            if (ours) return true;
        }
    }

    @Override
    public void close() {
        if (service == null) return;
        try {
            service.close();
        } catch (IOException e) {
            log.debug("Closing watch service", e);
        }
    }
}
