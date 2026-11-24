package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForegroundTest {

    @Test
    void findsTheProcessListeningOnAPortInNetstatOutput() {
        String netstat = """

                Active Connections

                  Proto  Local Address          Foreign Address        State           PID
                  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1288
                  TCP    127.0.0.1:41823        0.0.0.0:0              LISTENING       24084
                  TCP    127.0.0.1:63342        0.0.0.0:0              LISTENING       34196
                  TCP    127.0.0.1:63342        127.0.0.1:50123        ESTABLISHED     34196
                  TCP    127.0.0.1:50123        127.0.0.1:63342        ESTABLISHED     9999
                  TCP    [::1]:63343            [::]:0                 LISTENING       4242
                """;
        assertThat(Foreground.parseNetstat(netstat, 63342)).hasValue(34196);
        assertThat(Foreground.parseNetstat(netstat, 63343)).hasValue(4242);
        assertThat(Foreground.parseNetstat(netstat, 50123)).as("not listening").isEmpty();
        assertThat(Foreground.parseNetstat(netstat, 63344)).isEmpty();
    }

    @Test
    void readsLsofAndWmctrlOutput() {
        assertThat(Foreground.parseLsof("34196\n")).hasValue(34196);
        assertThat(Foreground.parseLsof("")).isEmpty();
        String wmctrl = """
                0x02a00003  0 1200   host Terminal
                0x04400007  0 34196  host clouseau – SourceRefs.java
                """;
        assertThat(Foreground.wmctrlWindow(wmctrl, 34196)).contains("0x04400007");
        assertThat(Foreground.wmctrlWindow(wmctrl, 1)).isEmpty();
    }

    @Test
    void findsTheMacAppBundleOfAProcess() {
        assertThat(Foreground.macBundle("/Applications/IntelliJ IDEA.app/Contents/MacOS/idea"))
                .contains("/Applications/IntelliJ IDEA.app");
        assertThat(Foreground.macBundle("/usr/bin/java")).isEmpty();
        assertThat(Foreground.macBundle(null)).isEmpty();
    }

    @Test
    void windowsActivatesTheProcessByItsId() {
        assertThat(Foreground.windowsCommand(34196)).startsWith("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand");
        String encoded = Foreground.windowsCommand(34196).get(4);
        String script = new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_16LE);
        assertThat(script).isEqualTo(Foreground.windowsScript(34196)).contains("Get-Process -Id 34196", "SetForegroundWindow($h)");
    }
}
