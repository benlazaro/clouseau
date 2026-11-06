package com.droidenx.clouseau.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SshConfigTest {

    private static SshConfig at(String remotePath) {
        return new SshConfig("10.0.0.5", 22, "deploy", null, null, null, remotePath);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/var/log/app.log    | app.log",
            "~/logs/my app.log   | my app.log",
            "app.log             | app.log",
            "/var/log/myapp/     | myapp",
            "/var/log/myapp//    | myapp",
            "/                   | /",
            "~                   | ~",
            "' /var/log/x.log '  | x.log",
    })
    void fileNameDropsTheFolder(String path, String expected) {
        assertThat(at(path).fileName()).isEqualTo(expected);
    }
}
