package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogSource;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Reads a log file line-by-line on a daemon thread, pushing each non-blank
 * line to the supplied consumer.
 *
 * Calling close() interrupts the reader thread so ingestion stops cleanly.
 */
@Slf4j
public final class FileLogSource implements LogSource {

    private final Path file;
    private volatile Thread readerThread;

    public FileLogSource(Path file) {
        this.file = file;
    }

    @Override
    public String getName() {
        return "File: " + file.getFileName();
    }

    /**
     * Starts a daemon thread that reads {@code file} and calls {@code lineConsumer}
     * for every non-blank line. Returns immediately; reading happens in the background.
     */
    @Override
    public void open(Consumer<String> lineConsumer) throws Exception {
        open(lineConsumer, () -> {});
    }

    /**
     * Same as {@link #open(Consumer)}, but calls {@code onComplete} on the reader
     * thread once the file has been fully read (not called if interrupted).
     */
    public void open(Consumer<String> lineConsumer, Runnable onComplete) throws Exception {
        log.info("Opening log file: {}", file);
        readerThread = new Thread(() -> {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Utf8Bom.skip(Files.newInputStream(file)), decoder))) {
                String line;
                while ((line = reader.readLine()) != null
                        && !Thread.currentThread().isInterrupted()) {
                    if (!line.isBlank()) {
                        lineConsumer.accept(line);
                    }
                }
                if (!Thread.currentThread().isInterrupted()) {
                    log.info("Finished reading {}", file);
                    onComplete.run();
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.error("Error reading {}", file, e);
                }
            }
        }, "clouseau-file-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    @Override
    public void close() {
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
            log.debug("Reader thread interrupted for {}", file);
        }
    }
}
