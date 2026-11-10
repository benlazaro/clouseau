package com.droidenx.clouseau.ui;

import javax.swing.SwingWorker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link SwingWorker}s that never finish on their own, such as follow mode and live
 * sources, on threads of their own (D-22).
 * <p>
 * {@link SwingWorker#execute()} uses a JDK-wide pool of at most 10 threads, so ten
 * followed files would take every one and every later worker (opening a file, a file
 * chooser preview, a review export) would wait forever. A worker started here runs on an
 * unbounded pool of daemon threads instead; it is still a {@code SwingWorker}, so
 * {@code publish}/{@code process}, {@code done()} and {@code cancel(true)} (which
 * interrupts the thread running it) work exactly as with {@code execute()}.
 * <p>
 * One-shot work that ends by itself (loads, exports, downloads) keeps using
 * {@code execute()}: it can wait for a turn, but it never holds a thread for good.
 */
final class FollowThreads {

    private static final AtomicInteger COUNT = new AtomicInteger();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "clouseau-follow-" + COUNT.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private FollowThreads() {}

    /** Starts {@code worker} on a thread of its own, in place of {@link SwingWorker#execute()}. */
    static void start(SwingWorker<?, ?> worker) {
        POOL.execute(worker);
    }
}
