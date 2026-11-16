package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The detail pane's control pictures: which characters get one, and which picture. */
class DetailPanelControlTest {

    @Test
    void c0ControlsAndDelAreControls() {
        assertThat(DetailPanel.isControl('\0')).isTrue();
        assertThat(DetailPanel.isControl('\u0001')).isTrue();
        assertThat(DetailPanel.isControl('\u001B')).isTrue();
        assertThat(DetailPanel.isControl('\u007F')).isTrue();
    }

    @Test
    void tabLineBreaksAndTextAreNot() {
        assertThat(DetailPanel.isControl('\t')).isFalse();
        assertThat(DetailPanel.isControl('\n')).isFalse();
        assertThat(DetailPanel.isControl('\r')).isFalse();
        assertThat(DetailPanel.isControl(' ')).isFalse();
        assertThat(DetailPanel.isControl('A')).isFalse();
        assertThat(DetailPanel.isControl('中')).isFalse();
    }

    @Test
    void picturesAreTheUnicodeControlPictures() {
        assertThat(DetailPanel.controlPicture('\0')).isEqualTo('␀');
        assertThat(DetailPanel.controlPicture('\u001B')).isEqualTo('␛');
        assertThat(DetailPanel.controlPicture('\u007F')).isEqualTo('␡');
        assertThat(DetailPanel.controlPicture('A')).isEqualTo('A');
    }
}
