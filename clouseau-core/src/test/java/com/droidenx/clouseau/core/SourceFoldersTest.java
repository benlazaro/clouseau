package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFoldersTest {

    @TempDir Path dir;

    private Path write(String path) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class X {}");
        return file;
    }

    private static SourceFolders folders(Path... roots) {
        return new SourceFolders(List.of(roots));
    }

    @Test
    void findsTheFileInAnyProjectUnderAFolderOfProjects() throws Exception {
        Path file = write("shop/orders/src/main/java/com/acme/orders/OrderService.java");
        write("shop/web/src/main/java/com/acme/web/Handler.java");
        write("billing/src/main/kotlin/com/acme/billing/Invoice.kt");
        SourceFolders folders = folders(dir);
        assertThat(folders.find("com/acme/orders/OrderService.java")).contains(file);
        assertThat(folders.find("com/acme/billing/Invoice.kt")).isPresent();
    }

    @Test
    void aFolderThatIsTheSourceRootItselfWorks() throws Exception {
        Path file = write("src/main/java/com/acme/Foo.java");
        assertThat(folders(dir.resolve("src/main/java")).find("com/acme/Foo.java")).contains(file);
    }

    @Test
    void matchesTheWholePathNotJustTheName() throws Exception {
        write("src/com/other/Foo.java");
        assertThat(folders(dir).find("com/acme/Foo.java")).isEmpty();
    }

    @Test
    void skipsBuildOutputAndMissingFolders() throws Exception {
        write("build/generated/com/acme/Foo.java");
        write("node_modules/x/com/acme/Foo.java");
        assertThat(folders(dir.resolve("missing"), dir).find("com/acme/Foo.java")).isEmpty();
        Path real = write("src/com/acme/Foo.java");
        assertThat(folders(dir.resolve("missing"), dir).find("com/acme/Foo.java")).contains(real);
    }

    @Test
    void prefersAnEarlierFolderThenMainSourceOverTests() throws Exception {
        Path test = write("a/src/test/java/com/acme/Foo.java");
        Path main = write("a/src/main/java/com/acme/Foo.java");
        Path other = write("b/src/main/java/com/acme/Foo.java");
        assertThat(folders(dir.resolve("a"), dir.resolve("b")).find("com/acme/Foo.java")).contains(main);
        assertThat(folders(dir.resolve("b"), dir.resolve("a")).find("com/acme/Foo.java")).contains(other);
        Files.delete(main);
        assertThat(folders(dir.resolve("a")).find("com/acme/Foo.java")).contains(test);
    }

    @Test
    void aMissWalksAgainButNotOnEveryClick() throws Exception {
        write("p/src/main/java/com/acme/Old.java");
        AtomicLong now = new AtomicLong();
        SourceFolders folders = new SourceFolders(List.of(dir), Duration.ofSeconds(30), now::get);
        assertThat(folders.find("com/acme/Old.java")).isPresent();

        Path added = write("p/src/main/java/com/acme/New.java");
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(folders.find("com/acme/New.java")).as("too soon to walk again").isEmpty();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(folders.find("com/acme/New.java")).contains(added);
    }

    @Test
    void aFileMovedSinceTheWalkIsFoundWhereItIsNow() throws Exception {
        Path old = write("p/src/main/java/com/acme/Foo.java");
        AtomicLong now = new AtomicLong();
        SourceFolders folders = new SourceFolders(List.of(dir), Duration.ZERO, now::get);
        assertThat(folders.find("com/acme/Foo.java")).contains(old);
        Files.delete(old);
        Path moved = write("q/src/main/java/com/acme/Foo.java");
        assertThat(folders.find("com/acme/Foo.java")).contains(moved);
    }

    @Test
    void usualRootsAreTheCommonProjectFoldersThatExist() throws Exception {
        Files.createDirectories(dir.resolve("git"));
        Files.createDirectories(dir.resolve("IdeaProjects"));
        Files.createDirectories(dir.resolve("Documents"));
        assertThat(SourceFolders.usualRoots(dir)).containsExactly(dir.resolve("git"), dir.resolve("IdeaProjects"));
    }
}
