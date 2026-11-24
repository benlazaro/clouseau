package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFoldersTest {

    @TempDir Path dir;

    private Path write(String path) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class X {}");
        return file;
    }

    @Test
    void findsTheFileUnderAnyModulesSourceRoot() throws Exception {
        Path file = write("shop/orders/src/main/java/com/acme/orders/OrderService.java");
        write("shop/web/src/main/java/com/acme/web/Handler.java");
        assertThat(SourceFolders.find(List.of(dir), "com/acme/orders/OrderService.java")).contains(file);
    }

    @Test
    void aFolderThatIsTheSourceRootItselfWorks() throws Exception {
        Path file = write("src/main/java/com/acme/Foo.java");
        assertThat(SourceFolders.find(List.of(dir.resolve("src/main/java")), "com/acme/Foo.java")).contains(file);
    }

    @Test
    void matchesTheWholePathNotJustTheName() throws Exception {
        write("src/com/other/Foo.java");
        assertThat(SourceFolders.find(List.of(dir), "com/acme/Foo.java")).isEmpty();
    }

    @Test
    void skipsBuildOutputAndMissingFolders() throws Exception {
        write("build/generated/com/acme/Foo.java");
        write("node_modules/x/com/acme/Foo.java");
        assertThat(SourceFolders.find(List.of(dir.resolve("missing"), dir), "com/acme/Foo.java")).isEmpty();
        Path real = write("src/com/acme/Foo.java");
        assertThat(SourceFolders.find(List.of(dir.resolve("missing"), dir), "com/acme/Foo.java")).contains(real);
    }
}
