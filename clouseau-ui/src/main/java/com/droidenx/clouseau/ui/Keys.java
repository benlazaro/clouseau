package com.droidenx.clouseau.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shortcut key: Cmd on macOS, Ctrl elsewhere (the platform's menu shortcut key). Every
 * shortcut written as {@code Ctrl+…} in the app and the guide uses it, except
 * {@code Ctrl+Tab} and {@code Ctrl+PageUp/PageDown}, which are Ctrl on macOS too.
 *
 * <p>Headless (the tests) it is Ctrl, since the toolkit can't be asked there.
 */
final class Keys {

    /** The shortcut key's modifier, as a {@link InputEvent} {@code *_DOWN_MASK}. */
    static final int MENU = menuMask();

    /** Whether the shortcut key is Cmd, so text that says "Ctrl" should say "Cmd". */
    static final boolean CMD = MENU == InputEvent.META_DOWN_MASK;

    private static final Pattern CTRL = Pattern.compile("\\bCtrl\\b");

    private Keys() {}

    /** Whether an event's modifiers ({@code getModifiersEx()}) include the shortcut key. */
    static boolean menuDown(int modifiersEx) {
        return (modifiersEx & MENU) != 0;
    }

    /** {@code text} with "Ctrl" read as "Cmd" when the shortcut key is Cmd. */
    static String label(String text) {
        return label(text, CMD);
    }

    static String label(String text, boolean cmd) {
        if (!cmd || text == null || !text.contains("Ctrl")) return text;
        // Ctrl+Tab and Ctrl+PageUp/PageDown stay Ctrl on macOS.
        Matcher m = CTRL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String rest = text.substring(m.end());
            boolean staysCtrl = rest.startsWith("+Tab") || rest.startsWith("+Shift+Tab")
                    || rest.startsWith("+Pg") || rest.startsWith("+Page");
            m.appendReplacement(out, staysCtrl ? "Ctrl" : "Cmd");
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int menuMask() {
        if (GraphicsEnvironment.isHeadless()) return InputEvent.CTRL_DOWN_MASK;
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (UnsupportedOperationException e) {   // HeadlessException included
            return InputEvent.CTRL_DOWN_MASK;
        }
    }
}
