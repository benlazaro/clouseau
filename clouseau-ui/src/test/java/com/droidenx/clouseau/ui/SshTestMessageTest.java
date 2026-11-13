package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SshConfig;
import com.droidenx.clouseau.core.SshRemote;
import com.droidenx.clouseau.core.SshRemote.Reason;
import com.droidenx.clouseau.core.SshRemote.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/** What the SSH dialog's Test (G-14) and Browse (G-13) say when a step fails. */
class SshTestMessageTest {

    private static final SshConfig CONFIG =
            new SshConfig("db01", 2222, "ops", "pw", null, null, "/var/log/app.log");

    private static String describe(Step step, Reason reason, String auth) {
        return SshConnectionDialog.describe(new SshRemote.Failure(step, reason, "detail here", null), CONFIG, auth);
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void everyReasonHasItsOwnWording(Reason reason) {
        String text = describe(Step.CONNECT, reason, "password");
        assertThat(text).doesNotContain("??").doesNotContain("{").doesNotContain("''");
    }

    @Test
    void namesWhatToCheck() {
        assertThat(describe(Step.CONNECT, Reason.UNKNOWN_HOST, "password")).contains("db01");
        assertThat(describe(Step.CONNECT, Reason.REFUSED, "password")).contains("port 2222");
        assertThat(describe(Step.CONNECT, Reason.TIMED_OUT, "password")).contains(SshRemote.TIMEOUT_SECONDS + " seconds");
        assertThat(describe(Step.SIGN_IN, Reason.REJECTED, "password")).contains("ops").contains("password");
        assertThat(describe(Step.SIGN_IN, Reason.REJECTED, "key")).contains("key");
        assertThat(describe(Step.SIGN_IN, Reason.KEY_FILE, "key")).contains("detail here");
        assertThat(describe(Step.FILE, Reason.MISSING, "password")).contains("/var/log/app.log").contains("doesn't exist");
        assertThat(describe(Step.FILE, Reason.UNREADABLE, "password")).contains("ops can't read /var/log/app.log");
        assertThat(describe(Step.FILE, Reason.OTHER, "password")).startsWith("Signed in").contains("detail here");
    }

    @Test
    void hiddenFilesAreCountedInWords() {
        assertThat(Messages.format("ssh.browse.hidden", 1, "ops")).isEqualTo("1 file not shown: ops can't read it.");
        assertThat(Messages.format("ssh.browse.hidden", 3, "ops")).isEqualTo("3 files not shown: ops can't read them.");
    }
}
