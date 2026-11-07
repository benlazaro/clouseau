package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import java.awt.Frame;

import static org.assertj.core.api.Assertions.assertThat;

class WindowActivatorTest {

    @Test
    void leavesAShownWindowAsItIs() {
        assertThat(WindowActivator.restoredState(Frame.NORMAL, Frame.NORMAL)).isEqualTo(Frame.NORMAL);
        assertThat(WindowActivator.restoredState(Frame.MAXIMIZED_BOTH, Frame.NORMAL))
                .isEqualTo(Frame.MAXIMIZED_BOTH);
    }

    @Test
    void restoresAMinimizedWindowToNormal() {
        assertThat(WindowActivator.restoredState(Frame.ICONIFIED, Frame.NORMAL)).isEqualTo(Frame.NORMAL);
    }

    @Test
    void restoresAMinimizedWindowMaximizedIfItWasMaximized() {
        assertThat(WindowActivator.restoredState(Frame.ICONIFIED, Frame.MAXIMIZED_BOTH))
                .isEqualTo(Frame.MAXIMIZED_BOTH);
        assertThat(WindowActivator.restoredState(Frame.ICONIFIED | Frame.MAXIMIZED_BOTH, Frame.NORMAL))
                .isEqualTo(Frame.MAXIMIZED_BOTH);
    }
}
