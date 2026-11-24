package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import java.awt.event.KeyEvent;

import static org.assertj.core.api.Assertions.assertThat;

class MnemonicsTest {

    @Test
    void prefersTheStartOfAWord() {
        assertThat(Mnemonics.index("Find Next", 'N')).isEqualTo(5);
        assertThat(Mnemonics.index("Parser Editor…", 'E')).isEqualTo(7);
        assertThat(Mnemonics.index("Save Session As…", 'a')).isEqualTo(13);
    }

    @Test
    void fallsBackToTheFirstOccurrence() {
        assertThat(Mnemonics.index("Exit", 'X')).isEqualTo(1);
        assertThat(Mnemonics.index("Timezones…", 'Z')).isEqualTo(4);
    }

    @Test
    void missingLetterHasNoUnderline() {
        assertThat(Mnemonics.index("Reconnect", 'L')).isEqualTo(-1);
        assertThat(Mnemonics.index(null, 'L')).isEqualTo(-1);
    }

    @Test
    void setsTheKeyAndTheUnderline() {
        JMenuItem item = new JMenuItem("Find Next");
        Mnemonics.set(item, 'n');
        assertThat(item.getMnemonic()).isEqualTo(KeyEvent.VK_N);
        assertThat(item.getDisplayedMnemonicIndex()).isEqualTo(5);
    }
}
