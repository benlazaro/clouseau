package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A source that couldn't be opened says which plugin it came from, when that's known. */
class SourceFailureMessageTest {

    @Test
    void namesThePlugin() {
        assertThat(SourceSettingsDialog.failed("Loki source", "connection refused"))
                .isEqualTo("The Loki source plugin couldn't open the source: connection refused");
    }

    @Test
    void withoutAPluginNameSaysSourcePlugin() {
        assertThat(SourceSettingsDialog.failed(null, "boom")).contains("source plugin").endsWith("boom");
    }
}
