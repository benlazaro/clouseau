package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SshLogSourceTest {

    @Test
    void plainPathIsQuoted() {
        assertThat(SshLogSource.tailCommand("/var/log/app.log"))
                .isEqualTo("tail -n 1000 -f -- '/var/log/app.log'");
    }

    @Test
    void pathWithSpacesStaysOneArgument() {
        assertThat(SshLogSource.tailCommand("/var/log/my app/server.log"))
                .isEqualTo("tail -n 1000 -f -- '/var/log/my app/server.log'");
    }

    @Test
    void metacharactersAreNotExecuted() {
        assertThat(SshLogSource.tailCommand("/tmp/x.log; rm -rf ~"))
                .isEqualTo("tail -n 1000 -f -- '/tmp/x.log; rm -rf ~'");
    }

    @Test
    void embeddedSingleQuoteIsEscaped() {
        assertThat(SshLogSource.tailCommand("/logs/bob's.log"))
                .isEqualTo("tail -n 1000 -f -- '/logs/bob'\\''s.log'");
    }

    @Test
    void homeRelativePathStillExpands() {
        assertThat(SshLogSource.tailCommand("~/logs/my app.log"))
                .isEqualTo("tail -n 1000 -f -- ~/'logs/my app.log'");
    }

    @Test
    void leadingDashIsNotAnOption() {
        assertThat(SshLogSource.tailCommand("-weird.log"))
                .isEqualTo("tail -n 1000 -f -- '-weird.log'");
    }

    /**
     * Round-trips hostile strings through a real POSIX shell, when one is available,
     * to prove the shell sees exactly the original text as one argument.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/var/log/app.log",
            "/var/log/my app/server.log",
            "/tmp/x.log; echo pwned",
            "$(echo pwned)",
            "`echo pwned`",
            "a'b\"c\\d",
            "''",
            "x && y || z | w > v < u & t",
            "*?[]{}!#",
    })
    void shellSeesTheLiteralPath(String path) throws Exception {
        String out = runSh("printf %s " + SshLogSource.shellQuote(path));
        assertThat(out).isEqualTo(path);
    }

    /** Feeds the script on stdin, so the OS's argv escaping (notably Windows') can't alter it. */
    private static String runSh(String script) throws Exception {
        Process p;
        try {
            p = new ProcessBuilder("sh").redirectErrorStream(true).start();
        } catch (IOException e) {
            assumeTrue(false, "no POSIX sh on PATH");
            throw e;
        }
        try (var stdin = p.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }
        assumeTrue(p.waitFor(10, TimeUnit.SECONDS), "sh timed out");
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
