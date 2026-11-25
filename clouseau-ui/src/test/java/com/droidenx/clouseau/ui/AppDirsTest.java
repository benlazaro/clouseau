package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppDirsTest {

    @TempDir Path tmp;

    @Test
    void windowsInstallWithADataFolderIsPortable() throws Exception {
        Path install = Files.createDirectories(tmp.resolve("Clouseau"));
        Files.createDirectories(install.resolve("data"));

        assertThat(AppDirs.resolve(null, install, tmp.resolve("home"), tmp))
                .isEqualTo(install.resolve("data"));
    }

    @Test
    void windowsInstallWithoutADataFolderUsesTheHomeFolder() throws Exception {
        Path install = Files.createDirectories(tmp.resolve("Clouseau"));

        assertThat(AppDirs.resolve(null, install, tmp.resolve("home"), tmp))
                .isEqualTo(tmp.resolve("home").resolve(".clouseau"));
    }

    @Test
    void aFileNamedDataDoesNotMakeItPortable() throws Exception {
        Path install = Files.createDirectories(tmp.resolve("Clouseau"));
        Files.writeString(install.resolve("data"), "");

        assertThat(AppDirs.resolve(null, install, tmp.resolve("home"), tmp))
                .isEqualTo(tmp.resolve("home").resolve(".clouseau"));
    }

    @Test
    void thePropertyWinsOverAPortableFolder() throws Exception {
        Path install = Files.createDirectories(tmp.resolve("Clouseau"));
        Files.createDirectories(install.resolve("data"));
        Path chosen = tmp.resolve("elsewhere");

        assertThat(AppDirs.resolve(chosen.toString(), install, tmp.resolve("home"), tmp)).isEqualTo(chosen);
    }

    @Test
    void aTildeInThePropertyMeansTheHomeFolder() {
        Path home = tmp.resolve("home");

        assertThat(AppDirs.resolve("~/.clouseau", null, home, tmp)).isEqualTo(home.resolve(".clouseau"));
        assertThat(AppDirs.resolve("~\\.clouseau", null, home, tmp)).isEqualTo(home.resolve(".clouseau"));
        assertThat(AppDirs.resolve("~", null, home, tmp)).isEqualTo(home.resolve(""));
    }

    @Test
    void runningFromSourceUsesDataInTheWorkingDirectory() {
        assertThat(AppDirs.resolve(null, null, tmp.resolve("home"), tmp)).isEqualTo(tmp.resolve("data"));
    }
}
