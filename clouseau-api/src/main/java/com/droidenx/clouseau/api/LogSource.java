package com.droidenx.clouseau.api;

import java.util.function.Consumer;

/**
 * A live stream of raw log lines, such as a remote {@code tail -f} or a log service's
 * streaming API. The source pushes lines; Clouseau never polls it.
 *
 * <p>Plugins provide sources through a {@link LogSourceProvider}. A source is used once:
 * Clouseau calls {@link #open} on a background thread, and {@link #close} from another
 * thread when the user closes the tab or stops the source.
 */
public interface LogSource {

    /** Shown as the tab's status-bar label, for example {@code "loki: {app=\"api\"}"}. */
    String getName();

    /**
     * Connects and passes each raw line to {@code lineConsumer}, in order, without its
     * line terminator. Runs on a background thread that Clouseau provides, and should
     * block until the stream ends or {@link #close} is called; return normally then.
     * Throw if connecting or reading fails: Clouseau shows the exception's message.
     */
    void open(Consumer<String> lineConsumer) throws Exception;

    /**
     * Stops the stream and releases its resources, making a blocked {@link #open} return.
     * Called from a different thread than {@code open}, possibly more than once, and
     * possibly before {@code open} has connected. Clouseau calls it on a thread of its own,
     * never the Swing event thread, so it may block on the network briefly.
     */
    void close() throws Exception;
}
