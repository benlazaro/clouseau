package com.droidenx.clouseau.core;

import java.util.function.LongSupplier;

/**
 * Tells a loader when the heap is close to full, so it can stop reading and warn the
 * user instead of failing with {@link OutOfMemoryError}.
 *
 * <p>Memory counts as low when the headroom ({@code max - used}) is below a reserve of
 * {@value #RESERVE_FRACTION_PERCENT}% of the maximum heap, but at least
 * {@value #MIN_RESERVE_BYTES} bytes. Because "used" includes garbage, a low reading
 * triggers one {@link System#gc()} and is checked again before being believed.
 */
public final class MemoryGuard {

    static final int  RESERVE_FRACTION_PERCENT = 10;
    static final long MIN_RESERVE_BYTES        = 64L * 1024 * 1024;

    private final LongSupplier maxBytes;
    private final LongSupplier usedBytes;
    private final Runnable     gc;

    /** A guard over this JVM's heap. */
    public MemoryGuard() {
        this(() -> Runtime.getRuntime().maxMemory(),
             () -> Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
             System::gc);
    }

    MemoryGuard(LongSupplier maxBytes, LongSupplier usedBytes, Runnable gc) {
        this.maxBytes  = maxBytes;
        this.usedBytes = usedBytes;
        this.gc        = gc;
    }

    /** True if the heap is nearly full even after a garbage collection. */
    public boolean isLow() {
        if (!belowReserve()) return false;
        gc.run();
        return belowReserve();
    }

    private boolean belowReserve() {
        long max = maxBytes.getAsLong();
        long reserve = Math.max(MIN_RESERVE_BYTES, max / 100 * RESERVE_FRACTION_PERCENT);
        return max - usedBytes.getAsLong() < reserve;
    }

    /** The maximum heap size in MiB, for messages that suggest raising it. */
    public long maxMiB() {
        return maxBytes.getAsLong() / (1024 * 1024);
    }
}
