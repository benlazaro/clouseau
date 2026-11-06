package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SshLogSourceTest {

    /** The command up to the path argument. */
    private static final String PREFIX = "sh -c 'test -r \"$1\" || exit 66; "
            + "exec tail -n 1000 -F -- \"$1\"' sh ";

    @Test
    void missingFileExitsWithUnreadableStatus() throws Exception {
        String out = runSh(SshLogSource.tailCommand("/no/such/dir/app.log") + "; echo status=$?");
        assertThat(out.trim()).isEqualTo("status=" + SshLogSource.EXIT_UNREADABLE);
    }

    /** Runs the real command through sh and tail, with a path that needs both quoting layers. */
    @Test
    @Timeout(10)
    void existingFileIsStreamed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("my app's $x.log");
        Files.writeString(file, "first\nsecond\n");
        Process p = startSh(SshLogSource.tailCommand(file.toString().replace('\\', '/')));
        try (var out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            assertThat(out.readLine()).isEqualTo("first");
            assertThat(out.readLine()).isEqualTo("second");
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void plainPathIsQuoted() {
        assertThat(SshLogSource.tailCommand("/var/log/app.log"))
                .isEqualTo(PREFIX + "'/var/log/app.log'");
    }

    @Test
    void pathWithSpacesStaysOneArgument() {
        assertThat(SshLogSource.tailCommand("/var/log/my app/server.log"))
                .isEqualTo(PREFIX + "'/var/log/my app/server.log'");
    }

    @Test
    void metacharactersAreNotExecuted() {
        assertThat(SshLogSource.tailCommand("/tmp/x.log; rm -rf ~"))
                .isEqualTo(PREFIX + "'/tmp/x.log; rm -rf ~'");
    }

    @Test
    void embeddedSingleQuoteIsEscaped() {
        assertThat(SshLogSource.tailCommand("/logs/bob's.log"))
                .isEqualTo(PREFIX + "'/logs/bob'\\''s.log'");
    }

    @Test
    void homeRelativePathStillExpands() {
        assertThat(SshLogSource.tailCommand("~/logs/my app.log"))
                .isEqualTo(PREFIX + "~/'logs/my app.log'");
    }

    @Test
    void leadingDashIsNotAnOption() {
        assertThat(SshLogSource.tailCommand("-weird.log"))
                .isEqualTo(PREFIX + "'-weird.log'");
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
        Process p = startSh(script);
        assumeTrue(p.waitFor(10, TimeUnit.SECONDS), "sh timed out");
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Process startSh(String script) throws Exception {
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
        return p;
    }
}
