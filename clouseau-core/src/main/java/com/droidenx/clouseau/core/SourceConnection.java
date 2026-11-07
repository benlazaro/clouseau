package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.ConnectingLogSource;
import com.droidenx.clouseau.api.LogSource;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The connection state of one run of a {@link LogSource} (UC-6, D-20): connecting until the
 * source reports it is connected or its first line arrives, then connected, and
 * disconnected once the stream ends or fails. Each state is entered at most once and
 * only forward ({@code CONNECTING → CONNECTED → DISCONNECTED}, or straight to
 * {@code DISCONNECTED}). Thread-safe: the source's thread reports the connection, the
 * caller reports the end.
 *
 * <p>Reconnecting starts a new {@code SourceConnection}; a listener can tell an old run's
 * changes apart because it is passed the connection that changed.
 */
public final class SourceConnection {

    public enum State { CONNECTING, CONNECTED, DISCONNECTED }

    private final AtomicReference<State> state = new AtomicReference<>(State.CONNECTING);
    private final Consumer<SourceConnection> listener;
    private volatile String error;

    /**
     * @param listener told after each change of state, on the thread that made it; it
     *                 reads {@link #state()} and {@link #error()}
     */
    public SourceConnection(Consumer<SourceConnection> listener) {
        this.listener = listener;
    }

    public State state() {
        return state.get();
    }

    /** Why the stream failed, or null if it is still up or ended normally. */
    public String error() {
        return error;
    }

    /** The source is up. Ignored unless still connecting. */
    public void connected() {
        if (state.compareAndSet(State.CONNECTING, State.CONNECTED)) listener.accept(this);
    }

    /**
     * The stream ended: normally ({@code error} null), failed, or was stopped. Ignored
     * after the first call.
     */
    public void disconnected(String error) {
        State before = state.get();
        while (before != State.DISCONNECTED) {
            if (state.compareAndSet(before, State.DISCONNECTED)) {
                this.error = error;
                listener.accept(this);
                return;
            }
            before = state.get();
        }
    }

    /**
     * Runs {@code source.open} on the calling thread, passing lines on to {@code lines}.
     * A {@link ConnectingLogSource} is connected when it runs its {@code onConnected};
     * any other source at its first line. Doesn't mark the connection disconnected when
     * {@code open} returns or throws: the caller does that with {@link #disconnected},
     * once it has an error message to show.
     */
    public void open(LogSource source, Consumer<String> lines) throws Exception {
        if (source instanceof ConnectingLogSource connecting) {
            connecting.open(lines, this::connected);
        } else {
            source.open(line -> {
                if (state.get() == State.CONNECTING) connected();
                lines.accept(line);
            });
        }
    }
}
