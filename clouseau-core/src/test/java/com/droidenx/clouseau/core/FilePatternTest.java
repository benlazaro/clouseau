package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilePatternTest {

    @TempDir Path dir;

    @Test
    void expandsInOneFolderSortedByName() throws Exception {
        for (String n : new String[]{"app.log.2.gz", "app.log", "app.log.1", "db.log"}) Files.writeString(dir.resolve(n), "x");
        Files.createDirectory(dir.resolve("app.log.d"));
        assertThat(FilePattern.isPattern("app.log*")).isTrue();
        assertThat(FilePattern.isPattern("app.log")).isFalse();
        assertThat(FilePattern.expand(dir, "app.log*")).extracting(p -> p.getFileName().toString())
                .containsExactly("app.log", "app.log.1", "app.log.2.gz");
        assertThat(FilePattern.expand(dir, "???.log")).extracting(p -> p.getFileName().toString())
                .containsExactly("app.log");
        assertThat(FilePattern.expand(dir, "none*")).isEmpty();
    }
}
