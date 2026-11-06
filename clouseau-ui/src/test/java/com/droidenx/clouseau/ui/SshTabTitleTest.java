package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SshConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SshTabTitleTest {

    private static SshConfig config(int port) {
        return new SshConfig("10.0.0.5", port, "deploy", null, null, null, "/var/log/myapp/app.log");
    }

    @Test
    void titleIsFileNameAndHost() {
        assertThat(MainFrame.sshTabTitle(config(22))).isEqualTo("app.log — 10.0.0.5");
    }

    @Test
    void tooltipHasUserHostAndPath() {
        assertThat(MainFrame.sshTabTooltip(config(22)))
                .isEqualTo("deploy@10.0.0.5:/var/log/myapp/app.log");
    }

    @Test
    void tooltipAddsAPortThatIsNot22() {
        assertThat(MainFrame.sshTabTooltip(config(2222)))
                .isEqualTo("deploy@10.0.0.5:/var/log/myapp/app.log (port 2222)");
    }
}
