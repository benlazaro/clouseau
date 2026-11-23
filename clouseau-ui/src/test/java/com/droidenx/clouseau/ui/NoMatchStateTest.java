package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.assertj.core.api.Assertions.assertThat;

/** The red "nothing matches" look: on, pulsing once, staying on quietly, and off again. */
class NoMatchStateTest {

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) throws Exception {
        Object[] out = new Object[1];
        Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try { out[0] = c.call(); } catch (Exception e) { err[0] = e; }
        });
        if (err[0] != null) throw err[0];
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    @Test
    void turningRedOutlinesTintsAndPulses() throws Exception {
        JTextField field = onEdt(JTextField::new);
        NoMatchState state = onEdt(() -> new NoMatchState(field));
        onEdt(() -> { state.set(true); return null; });
        assertThat(field.getClientProperty("JComponent.outline")).isEqualTo("error");
        assertThat(onEdt(state::isPulsing)).isTrue();
        assertThat(field.getBackground()).isNotEqualTo(UIManager.getColor("TextField.background"));
    }

    @Test
    void stayingRedDoesNotPulseAgain() throws Exception {
        JTextField field = onEdt(JTextField::new);
        NoMatchState state = onEdt(() -> new NoMatchState(field));
        onEdt(() -> { state.set(true); return null; });
        long deadline = System.currentTimeMillis() + 3000;
        while (onEdt(state::isPulsing) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(onEdt(state::isPulsing)).isFalse();
        onEdt(() -> { state.set(true); return null; });
        assertThat(onEdt(state::isPulsing)).isFalse();
        assertThat(field.getBackground()).isNotEqualTo(UIManager.getColor("TextField.background"));   // the resting tint
    }

    @Test
    void matchingAgainRestoresTheField() throws Exception {
        JTextField field = onEdt(JTextField::new);
        NoMatchState state = onEdt(() -> new NoMatchState(field));
        onEdt(() -> { state.set(true); state.set(false); return null; });
        assertThat(field.getClientProperty("JComponent.outline")).isNull();
        assertThat(onEdt(state::isPulsing)).isFalse();
        assertThat(field.getBackground()).isEqualTo(UIManager.getColor("TextField.background"));
    }
}
