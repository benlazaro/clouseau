package com.droidenx.clouseau.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Tab switching from the keyboard (UC-15): {@code Ctrl+Tab} / {@code Ctrl+Shift+Tab} and
 * {@code Ctrl+PageDown} / {@code Ctrl+PageUp} go to the next / previous tab with
 * wrap-around, {@code Ctrl+1}…{@code Ctrl+8} jump to that tab and {@code Ctrl+9} to the
 * last one.
 *
 * <p>A {@link KeyEventDispatcher} rather than key bindings, because {@code Ctrl+Tab} is a
 * focus-traversal key (consumed before any binding runs) and {@code JTable} binds
 * {@code Ctrl+PageUp/Down} itself; a dispatcher sees the key before both. It acts only
 * while {@code window} is the active window, so dialogs keep their keys, and consumes only
 * the keys it handles.
 */
final class TabShortcuts implements KeyEventDispatcher {

    private static final int MODIFIERS = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK
            | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;
    private static final int CTRL       = InputEvent.CTRL_DOWN_MASK;
    private static final int CTRL_SHIFT = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

    private final Window window;
    private final JTabbedPane tabs;

    TabShortcuts(Window window, JTabbedPane tabs) {
        this.window = window;
        this.tabs = tabs;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED || !window.isActive()) return false;
        int target = targetIndex(e.getKeyCode(), e.getModifiersEx(),
                tabs.getSelectedIndex(), tabs.getTabCount());
        if (target < 0) return false;
        if (target != tabs.getSelectedIndex()) tabs.setSelectedIndex(target);
        e.consume();
        return true;
    }

    /**
     * The tab a key press selects, or {@code -1} if it isn't a tab shortcut (or there is no
     * tab to go to), in which case the key is left alone.
     *
     * @param keyCode   {@link KeyEvent#getKeyCode()}
     * @param modifiers {@link KeyEvent#getModifiersEx()}
     * @param selected  the selected tab, or {@code -1}
     * @param count     the number of tabs
     */
    static int targetIndex(int keyCode, int modifiers, int selected, int count) {
        if (count <= 0) return -1;
        int mods = modifiers & MODIFIERS;
        // With no selection, "next" goes to the first tab and "previous" to the last.
        int current = selected >= 0 && selected < count ? selected : -1;
        int next = (current + 1) % count;
        int prev = current < 0 ? count - 1 : (current - 1 + count) % count;
        if (mods == CTRL) {
            switch (keyCode) {
                case KeyEvent.VK_TAB, KeyEvent.VK_PAGE_DOWN -> { return next; }
                case KeyEvent.VK_PAGE_UP -> { return prev; }
                case KeyEvent.VK_9 -> { return count - 1; }
                default -> {
                    if (keyCode >= KeyEvent.VK_1 && keyCode <= KeyEvent.VK_8) {
                        int idx = keyCode - KeyEvent.VK_1;
                        return idx < count ? idx : -1;
                    }
                    return -1;
                }
            }
        }
        if (mods == CTRL_SHIFT && keyCode == KeyEvent.VK_TAB) return prev;
        return -1;
    }
}
