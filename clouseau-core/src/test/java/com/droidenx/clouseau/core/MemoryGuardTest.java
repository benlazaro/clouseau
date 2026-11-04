package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryGuardTest {

    private static final long MIB = 1024 * 1024;

    @Test
    void plentyOfHeadroomIsNotLowAndSkipsGc() {
        AtomicInteger gcs = new AtomicInteger();
        MemoryGuard guard = new MemoryGuard(() -> 2048 * MIB, () -> 1000 * MIB, gcs::incrementAndGet);

        assertThat(guard.isLow()).isFalse();
        assertThat(gcs).hasValue(0);
    }

    @Test
    void lowHeadroomThatSurvivesGcIsLow() {
        // 10% of 2 GiB is ~205 MiB of reserve; 100 MiB headroom is below it.
        AtomicInteger gcs = new AtomicInteger();
        MemoryGuard guard = new MemoryGuard(() -> 2048 * MIB, () -> 1948 * MIB, gcs::incrementAndGet);

        assertThat(guard.isLow()).isTrue();
        assertThat(gcs).hasValue(1);
    }

    @Test
    void garbageFreedByGcIsNotLow() {
        AtomicLong used = new AtomicLong(1948 * MIB);
        MemoryGuard guard = new MemoryGuard(() -> 2048 * MIB, used::get, () -> used.set(900 * MIB));

        assertThat(guard.isLow()).isFalse();
    }

    @Test
    void smallHeapsKeepTheMinimumReserve() {
        // 10% of 256 MiB is ~26 MiB, but the reserve never drops below 64 MiB.
        MemoryGuard guard = new MemoryGuard(() -> 256 * MIB, () -> 200 * MIB, () -> {});

        assertThat(guard.isLow()).isTrue();
        assertThat(guard.maxMiB()).isEqualTo(256);
    }
}
