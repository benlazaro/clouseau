package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.ToolTipManager;

import static org.assertj.core.api.Assertions.assertThat;

class TooltipTimingsTest {

    private int initial;
    private int reshow;
    private int dismiss;

    @BeforeEach
    void remember() {
        ToolTipManager tooltips = ToolTipManager.sharedInstance();
        initial = tooltips.getInitialDelay();
        reshow = tooltips.getReshowDelay();
        dismiss = tooltips.getDismissDelay();
    }

    @AfterEach
    void putBack() {
        ToolTipManager tooltips = ToolTipManager.sharedInstance();
        tooltips.setInitialDelay(initial);
        tooltips.setReshowDelay(reshow);
        tooltips.setDismissDelay(dismiss);
    }

    @Test
    void showsSoonerAndNeverClosesOnATimer() {
        ClouseauApp.applyTooltipTimings();

        ToolTipManager tooltips = ToolTipManager.sharedInstance();
        assertThat(tooltips.getInitialDelay()).isEqualTo(300);
        assertThat(tooltips.getReshowDelay()).isEqualTo(250);
        assertThat(tooltips.getDismissDelay()).isEqualTo(Integer.MAX_VALUE);
    }
}
