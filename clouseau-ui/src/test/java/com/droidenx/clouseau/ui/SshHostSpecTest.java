package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SshHostSpecTest {

    @Test
    void aPlainHostHasNoUserOrPort() {
        assertThat(SshConnectionDialog.parseHostSpec("db01.example.com"))
                .isEqualTo(new SshConnectionDialog.HostSpec(null, "db01.example.com", null));
    }

    @Test
    void userAndPortAreSplitOff() {
        assertThat(SshConnectionDialog.parseHostSpec(" ops@10.0.0.5:2222 "))
                .isEqualTo(new SshConnectionDialog.HostSpec("ops", "10.0.0.5", 2222));
        assertThat(SshConnectionDialog.parseHostSpec("ops@db01"))
                .isEqualTo(new SshConnectionDialog.HostSpec("ops", "db01", null));
        assertThat(SshConnectionDialog.parseHostSpec("db01:22"))
                .isEqualTo(new SshConnectionDialog.HostSpec(null, "db01", 22));
    }

    @Test
    void anythingElseIsLeftAsTyped() {
        assertThat(SshConnectionDialog.parseHostSpec("")).isNull();
        assertThat(SshConnectionDialog.parseHostSpec("ssh ops@db01")).isNull();
        assertThat(SshConnectionDialog.parseHostSpec("db01:70000")).isNull();
        assertThat(SshConnectionDialog.parseHostSpec("db01:0")).isNull();
        assertThat(SshConnectionDialog.parseHostSpec("fe80::1")).isNull();
    }
}
