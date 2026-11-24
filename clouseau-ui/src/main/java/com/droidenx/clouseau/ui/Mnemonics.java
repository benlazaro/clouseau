package com.droidenx.clouseau.ui;

import javax.swing.AbstractButton;

/**
 * Menu mnemonics: Alt+F opens File, then a letter picks an item. The letter is chosen in
 * code, not marked in {@code messages.properties}, since several labels are shared with
 * menus that have none (the row menu, the detail pane's Copy menu).
 */
final class Mnemonics {

    private Mnemonics() {}

    /**
     * Sets {@code letter} as {@code button}'s mnemonic, underlined where a word starts with
     * it ("Find <u>N</u>ext", not "Fi<u>n</u>d Next"), else at its first occurrence. Swing
     * moves the underline to the first occurrence on {@code setText}, so call this again
     * after changing the text.
     */
    static void set(AbstractButton button, char letter) {
        button.setMnemonic(Character.toUpperCase(letter));
        int index = index(button.getText(), letter);
        if (index >= 0) button.setDisplayedMnemonicIndex(index);
    }

    /** Where to underline {@code letter} in {@code text}: a word's first letter if any, else its first occurrence, else -1. */
    static int index(String text, char letter) {
        if (text == null) return -1;
        char want = Character.toLowerCase(letter);
        int first = -1;
        for (int i = 0; i < text.length(); i++) {
            if (Character.toLowerCase(text.charAt(i)) != want) continue;
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) return i;
            if (first < 0) first = i;
        }
        return first;
    }
}
