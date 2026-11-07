package com.droidenx.clouseau.ui;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;

/**
 * Brings the main window to the front when another launch hands it files (issue #5).
 *
 * <p>Windows' foreground-lock rule stops a background process from activating its own
 * window, so a plain {@link Frame#toFront()} only flashes the taskbar button while the
 * window stays behind Explorer. Making the window always-on-top for a moment moves it
 * above the others (the rule doesn't cover z-order), and clearing the flag right after
 * leaves it as the top normal window. No native code is involved.
 */
@Slf4j
final class WindowActivator {

    /** Client property on the frame's root pane: its last extended state that wasn't iconified. */
    private static final String LAST_SHOWN_STATE = "clouseau.lastShownState";

    private WindowActivator() {}

    /**
     * Remembers whether {@code frame} was maximized before it was minimized, so
     * {@link #bringToFront} can restore it maximized. Call once, on the EDT.
     */
    static void install(JFrame frame) {
        rememberState(frame, frame.getExtendedState());
        frame.addWindowStateListener((WindowEvent e) -> rememberState(frame, e.getNewState()));
    }

    private static void rememberState(JFrame frame, int state) {
        if ((state & Frame.ICONIFIED) == 0) frame.getRootPane().putClientProperty(LAST_SHOWN_STATE, state);
    }

    /** Restores {@code frame} if it's minimized and brings it to the front. EDT only. */
    static void bringToFront(JFrame frame) {
        if (!frame.isVisible()) return;
        Object last = frame.getRootPane().getClientProperty(LAST_SHOWN_STATE);
        int state = frame.getExtendedState();
        int restored = restoredState(state, last instanceof Integer i ? i : Frame.NORMAL);
        if (restored != state) frame.setExtendedState(restored);

        boolean toggleOnTop = !frame.isAlwaysOnTop() && frame.isAlwaysOnTopSupported();
        try {
            if (toggleOnTop) frame.setAlwaysOnTop(true);
            frame.toFront();
            frame.requestFocus();
        } catch (SecurityException e) {
            log.debug("Could not raise the window", e);
            toggleOnTop = false;
        } finally {
            // Cleared after the native calls above have been handled, so the window stays on top.
            if (toggleOnTop) SwingUtilities.invokeLater(() -> frame.setAlwaysOnTop(false));
        }
    }

    /**
     * The extended state to show a window in: {@code state} without {@link Frame#ICONIFIED},
     * maximized again if it was maximized ({@code lastShown}) before it was minimized.
     */
    static int restoredState(int state, int lastShown) {
        if ((state & Frame.ICONIFIED) == 0) return state;
        return (state & ~Frame.ICONIFIED) | (lastShown & Frame.MAXIMIZED_BOTH);
    }
}
