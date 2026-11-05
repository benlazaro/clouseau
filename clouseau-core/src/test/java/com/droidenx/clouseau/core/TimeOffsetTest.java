package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeOffsetTest {

    @Test
    void formatsFromTheLargestUnitDownToSeconds() {
        assertThat(TimeOffset.format(0)).isEqualTo("0s");
        assertThat(TimeOffset.format(192_000)).isEqualTo("+3m 12s");
        assertThat(TimeOffset.format(-7_205_000)).isEqualTo("-2h 0m 5s");
        assertThat(TimeOffset.format(250)).isEqualTo("+0.250s");
        assertThat(TimeOffset.format(3_600_000)).isEqualTo("+1h 0m 0s");
        assertThat(TimeOffset.format(-61_005)).isEqualTo("-1m 1.005s");
    }
}
