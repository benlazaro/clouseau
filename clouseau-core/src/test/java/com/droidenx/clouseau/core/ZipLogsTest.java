package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipLogsTest {

    @TempDir Path dir;

    private Path zip(Charset names, String... nameContentPairs) throws IOException {
        Path zip = dir.resolve("logs.zip");
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out, names)) {
            for (int i = 0; i < nameContentPairs.length; i += 2) {
                zos.putNextEntry(new ZipEntry(nameContentPairs[i]));
                String content = nameContentPairs[i + 1];
                if (content != null) zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void listsFilesButNotFolders() throws IOException {
        Path zip = zip(StandardCharsets.UTF_8,
                "app.log", "one\n", "server/", null, "server/server.log", "two\nthree\n");

        assertThat(ZipLogs.list(zip)).extracting(ZipLogs.Entry::name)
                .containsExactly("app.log", "server/server.log");
        assertThat(ZipLogs.list(zip).get(1).fileName()).isEqualTo("server.log");
        assertThat(ZipLogs.list(zip).get(1).size()).isEqualTo(10);
    }

    @Test
    void opensTheChosenEntry() throws IOException {
        Path zip = zip(StandardCharsets.UTF_8, "a.log", "first\n", "b.log", "second\n");

        try (InputStream in = ZipLogs.open(zip, "b.log")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("second\n");
        }
    }

    @Test
    void aMissingEntryIsAnIOException() throws IOException {
        Path zip = zip(StandardCharsets.UTF_8, "a.log", "first\n");

        assertThatThrownBy(() -> ZipLogs.open(zip, "nope.log")).isInstanceOf(IOException.class);
    }

    @Test
    void readsNamesFromOlderWindowsTools() throws IOException {
        Path zip = zip(Charset.forName("IBM437"), "café.log", "x\n");

        assertThat(ZipLogs.list(zip)).extracting(ZipLogs.Entry::name).containsExactly("café.log");
        try (InputStream in = ZipLogs.open(zip, "café.log")) {
            assertThat(in.readAllBytes()).hasSize(2);
        }
    }

    @Test
    void fileNameHandlesBothSlashes() {
        assertThat(new ZipLogs.Entry("server/api.log", 1).fileName()).isEqualTo("api.log");
        assertThat(new ZipLogs.Entry("server\\api.log", 1).fileName()).isEqualTo("api.log");
        assertThat(new ZipLogs.Entry("plain.log", 1).fileName()).isEqualTo("plain.log");
    }

    @Test
    void entryKeysAreSafeFileNamesNextToTheZip() {
        Path key = ZipLogs.entryKey(dir.resolve("logs.zip"), "server/a:b.log");

        assertThat(key.getParent()).isEqualTo(dir.toAbsolutePath());
        assertThat(key.getFileName().toString()).isEqualTo("logs.zip!server_a_b.log");
    }
}
