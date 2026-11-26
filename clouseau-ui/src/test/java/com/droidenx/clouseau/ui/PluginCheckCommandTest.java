package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** --check-plugin, end to end, on the example plugin's JAR (passed in by the build). */
class PluginCheckCommandTest {

    private record Run(int exit, String out) {}

    private static Run run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = PluginCheckCommand.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    private static String exampleJar() {
        String jar = System.getProperty("clouseau.examplePlugin.jar");
        assertThat(jar).as("set by the build").isNotNull();
        return jar;
    }

    @Test
    void theExamplePluginPassesOnALogfmtSample(@TempDir Path dir) throws Exception {
        Path sample = Files.writeString(dir.resolve("app.log"), """
                time=2026-03-27T08:00:00Z level=info msg="order created" order=ORD-42
                time=2026-03-27T08:00:01+02:00 level=warn msg="disk low" free=12%
                """);
        Run run = run(PluginCheckCommand.FLAG, exampleJar(), sample.toString());

        assertThat(run.exit()).as(run.out()).isZero();
        assertThat(run.out()).contains("OK    Plugin-Id logfmt-clouseau-plugin")
                .contains("OK    Loads as \"logfmt example\" 1.0.0")
                .contains("OK    Provides 1 parser, 0 formatters, 0 highlighters, 1 log source")
                .contains("Parser \"logfmt (example plugin)\"")
                .contains("OK    Reads 2 of 2 lines")
                .contains("OK    Thread-safe")
                .contains("Log source \"Demo log stream (example plugin)\"")
                .contains("Result: 0 problems");
    }

    @Test
    void withoutASampleOnlyTheJarAndNamesAreChecked() {
        Run run = run(PluginCheckCommand.FLAG, exampleJar());
        assertThat(run.exit()).as(run.out()).isZero();
        assertThat(run.out()).contains("NOTE  No sample log given");
    }

    @Test
    void aFileThatIsNotAPluginFails(@TempDir Path dir) throws Exception {
        Path notAJar = Files.writeString(dir.resolve("notes-clouseau-plugin-1.0.0.jar"), "not a jar");
        Run run = run(PluginCheckCommand.FLAG, notAJar.toString());
        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.out()).contains("FAIL  Can't read it as a JAR");
    }

    @Test
    void badArgumentsPrintTheUsage() {
        Run run = run(PluginCheckCommand.FLAG);
        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).startsWith("Usage: --check-plugin <plugin.jar> [<sample.log>]")
                .contains("sample log's lines");
        assertThat(run(PluginCheckCommand.FLAG, "no-such.jar").out()).startsWith("No such file:");
    }
}
