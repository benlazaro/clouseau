package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Reads a static log from start to end (UC-1): every non-blank line becomes an entry. Every
 * {@code checkInterval} lines it asks whether memory is low and, if so, stops, so a file
 * larger than the heap loads partly instead of failing (G-5).
 */
public final class LogFileReader {

    /** Lines read between memory checks by default. */
    public static final int DEFAULT_CHECK_INTERVAL = 10_000;

    /**
     * @param stoppedAtLine the number of lines read before loading stopped for lack of
     *                      memory, or -1 if the whole file was read (or it was cancelled)
     */
    public record Result(long stoppedAtLine) {
        public boolean partial() { return stoppedAtLine >= 0; }
    }

    private LogFileReader() {}

    /**
     * @param memoryLow asked every {@code checkInterval} lines; true stops the read
     * @param cancelled asked before every line; true ends the read quietly
     * @param sink      receives each entry, in file order
     */
    public static Result read(BufferedReader reader, LineParser parser, int checkInterval,
                              BooleanSupplier memoryLow, BooleanSupplier cancelled,
                              Consumer<LogEntry> sink) throws IOException {
        String line;
        long lineNo = 0;
        while ((line = reader.readLine()) != null && !cancelled.getAsBoolean()) {
            if (++lineNo % checkInterval == 0 && memoryLow.getAsBoolean()) {
                return new Result(lineNo - 1);
            }
            if (line.isBlank()) continue;
            sink.accept(parser.parse(line));
        }
        return new Result(-1);
    }
}
