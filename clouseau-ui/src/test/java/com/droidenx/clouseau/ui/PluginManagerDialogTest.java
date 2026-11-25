package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginManagerDialogTest {

    @Test
    void anUpdateSaysTheNewVersionAndTheOldOne() {
        assertThat(PluginManagerDialog.installedToast("Loki", "1.2.0", false, true, "1.1.0"))
                .isEqualTo("Updated Loki from 1.1.0 to 1.2.0");
    }

    @Test
    void anUpdateFromAnUnknownVersionSaysOnlyTheNewOne() {
        assertThat(PluginManagerDialog.installedToast("Loki", "1.2.0", false, true, null))
                .isEqualTo("Updated Loki to 1.2.0");
    }

    @Test
    void anInstallSaysWhatAndWhichVersion() {
        assertThat(PluginManagerDialog.installedToast("Loki", "1.2.0", false, false, null))
                .isEqualTo("Installed Loki 1.2.0");
    }

    @Test
    void oneThatDidntLoadSaysSo() {
        assertThat(PluginManagerDialog.installedToast("loki.jar", "", true, true, "1.1.0"))
                .isEqualTo("loki.jar is installed but didn't load; its details say why");
    }
}
