package com.droidenx.clouseau.api;

import java.util.function.Consumer;

/**
 * A {@link LogSource} that has a connecting phase (sign in, start a remote command,
 * subscribe) and can tell Clouseau when it's over. The tab shows such a source as
 * connecting until it reports the stream is live, then as connected; any other source
 * counts as connected at its first line, which on a quiet log may be minutes away.
 *
 * <p>Clouseau calls {@link #open(Consumer, Runnable)} on a source that implements this
 * interface. Implement that method; the one-argument {@link #open(Consumer)} calls it
 * with a callback that does nothing.
 */
public interface ConnectingLogSource extends LogSource {

    /**
     * Like {@link LogSource#open(Consumer)}, but runs {@code onConnected} once the stream
     * is live: connected, signed in, and ready to deliver lines, whether or not a line
     * has arrived yet.
     *
     * <p>Call {@code onConnected} at most once, from the thread running {@code open},
     * before passing the first line. Don't call it if connecting fails: throw instead, as
     * for {@link LogSource#open(Consumer)}. Clouseau doesn't rely on {@code close()}
     * running before or after it.
     *
     * @param lines       receives each raw line, as for {@link LogSource#open(Consumer)}
     * @param onConnected run once the stream is live; never null
     */
    void open(Consumer<String> lines, Runnable onConnected) throws Exception;

    /** Opens the stream without reporting when it's live. */
    @Override
    default void open(Consumer<String> lines) throws Exception {
        open(lines, () -> {});
    }
}
