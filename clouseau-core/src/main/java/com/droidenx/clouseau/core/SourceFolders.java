package com.droidenx.clouseau.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a source file on disk from its path relative to a source root
 * ({@code com/acme/Foo.java}), for editors that need an absolute path: it looks under each
 * folder the user named (a project, or a folder of projects) for a file whose path ends that
 * way, so {@code src/main/java}, {@code src/test/kotlin} and every module are covered.
 *
 * <p>Build output, dependencies and version control folders are skipped. Blocking: call
 * {@link #find} off the Event Dispatch Thread.
 */
@Slf4j
public final class SourceFolders {

    /** Folders that hold no source of the user's own, and can be huge. */
    private static final Set<String> SKIP = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".vscode", "node_modules",
            "build", "target", "out", "bin", "dist", ".mvn", "__pycache__");

    /** Deep enough for a folder of projects, each with modules, under src/main/java. */
    private static final int MAX_DEPTH = 24;

    private SourceFolders() {}

    /** The first file under {@code folders} whose path ends with {@code relativePath}. Blocks. */
    public static Optional<Path> find(List<Path> folders, String relativePath) {
        Path suffix = Path.of(relativePath);
        Path fileName = suffix.getFileName();
        for (Path folder : folders) {
            // The folder may be a source root itself.
            Path direct = folder.resolve(suffix);
            if (Files.isRegularFile(direct)) return Optional.of(direct);
            if (!Files.isDirectory(folder)) continue;
            Path[] hit = {null};
            try {
                Files.walkFileTree(folder, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                        return !dir.equals(folder) && SKIP.contains(String.valueOf(dir.getFileName()))
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (fileName.equals(file.getFileName()) && file.endsWith(suffix)) {
                            hit[0] = file;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;   // an unreadable folder: look elsewhere
                    }
                });
            } catch (IOException e) {
                log.debug("Couldn't search {} for {}", folder, relativePath, e);
            }
            if (hit[0] != null) return Optional.of(hit[0]);
        }
        return Optional.empty();
    }
}
