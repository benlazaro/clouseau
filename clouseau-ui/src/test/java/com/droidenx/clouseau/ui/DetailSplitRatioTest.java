package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The table/detail split new tabs open with: the last one dragged to, kept within bounds. */
class DetailSplitRatioTest {

    @Test
    void ratiosInsideTheBoundsAreKept() {
        assertThat(AppPrefs.clampSplitRatio(0.55)).isEqualTo(0.55);
        assertThat(AppPrefs.clampSplitRatio(0.8)).isEqualTo(0.8);
    }

    @Test
    void aPaneDraggedShutOrOverTheTableIsPulledBack() {
        assertThat(AppPrefs.clampSplitRatio(1.0)).isEqualTo(0.90);
        assertThat(AppPrefs.clampSplitRatio(0.02)).isEqualTo(0.15);
    }

    @Test
    void anUnusableValueFallsBackToTheDefault() {
        assertThat(AppPrefs.clampSplitRatio(Double.NaN)).isEqualTo(AppPrefs.DEFAULT_DETAIL_SPLIT);
        assertThat(AppPrefs.DEFAULT_DETAIL_SPLIT).isEqualTo(0.55);
    }
}
