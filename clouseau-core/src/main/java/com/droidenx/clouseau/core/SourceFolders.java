package com.droidenx.clouseau.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Finds a source file on disk from its path relative to a source root
 * ({@code com/acme/Foo.java}), for Open in IDE: the folders searched are projects, or folders
 * that hold projects (a {@code git} folder with dozens of repositories), so
 * {@code src/main/java}, {@code src/test/kotlin} and every module of every project are covered.
 *
 * <p>The folders are walked once, building an index of every source file by name, and a lookup
 * reads the index. A lookup that finds nothing, or finds a file since deleted, walks them again,
 * at most once every {@link #REWALK_AFTER}, so a file added since is found without walking on
 * every click for a frame that isn't the user's own ({@code Thread.java}).
 *
 * <p>With no folders chosen, the usual places people keep code are searched
 * ({@link #usualRoots}). Build output, dependencies and version control folders are skipped.
 * Thread-safe; {@link #find} and {@link #prepare} block, so call them off the Event Dispatch
 * Thread.
 */
@Slf4j
public final class SourceFolders {

    /** Folders that hold no source of the user's own, and can be huge. */
    private static final Set<String> SKIP = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".vscode", "node_modules",
            "build", "target", "out", "bin", "dist", ".mvn", "__pycache__");

    /** The files a stack frame can name: those {@link SourceRefs} links. */
    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy", "gvy", "clj", "cljc");

    /** Folders in the home folder where people usually keep their projects. */
    static final List<String> USUAL_ROOTS = List.of(
            "git", "src", "source", "repos", "code", "projects", "dev", "workspace", "IdeaProjects");

    /** Deep enough for a folder of projects, each with modules, under src/main/java. */
    private static final int MAX_DEPTH = 24;

    /** The least time between walks a missed lookup starts. */
    static final Duration REWALK_AFTER = Duration.ofSeconds(30);

    private final List<Path> roots;
    private final long rewalkNanos;
    private final LongSupplier clock;

    /** Source files by file name; null until the first walk. */
    private Map<String, List<Path>> byName;
    private long walkedAt;

    /** Searches {@code roots}, in order: a file in an earlier one wins. */
    public SourceFolders(List<Path> roots) {
        this(roots, REWALK_AFTER, System::nanoTime);
    }

    /** For tests: another time between walks, and clock. */
    SourceFolders(List<Path> roots, Duration rewalkAfter, LongSupplier clock) {
        this.roots = List.copyOf(roots);
        this.rewalkNanos = rewalkAfter.toNanos();
        this.clock = clock;
    }

    /** The folders searched. */
    public List<Path> roots() {
        return roots;
    }

    /**
     * The folders searched when the user has chosen none: those of {@link #USUAL_ROOTS} that
     * exist under {@code home}, such as {@code ~/git} and {@code ~/IdeaProjects}.
     */
    public static List<Path> usualRoots(Path home) {
        List<Path> found = new ArrayList<>();
        for (String name : USUAL_ROOTS) {
            Path dir = home.resolve(name);
            if (Files.isDirectory(dir)) found.add(dir);
        }
        return found;
    }

    /** Builds the index now if it isn't built yet, so the first lookup is quick. Blocks. */
    public synchronized void prepare() {
        if (byName == null) walk();
    }

    /** The file under the folders whose path ends with {@code relativePath}. Blocks. */
    public synchronized Optional<Path> find(String relativePath) {
        Path suffix = Path.of(relativePath);
        // A folder may be a source root itself: no walk needed.
        for (Path root : roots) {
            Path direct = root.resolve(suffix);
            if (Files.isRegularFile(direct)) return Optional.of(direct);
        }
        boolean walked = byName == null;
        if (walked) walk();
        Optional<Path> hit = lookup(suffix);
        if (hit.isEmpty() && !walked && clock.getAsLong() - walkedAt >= rewalkNanos) {
            walk();   // added since, or moved
            hit = lookup(suffix);
        }
        return hit;
    }

    private Optional<Path> lookup(Path suffix) {
        List<Path> named = byName.getOrDefault(String.valueOf(suffix.getFileName()), List.of());
        return named.stream()
                .filter(p -> p.endsWith(suffix) && Files.isRegularFile(p))
                .findFirst();
    }

    /** Walks every folder, replacing the index. */
    private void walk() {
        long start = System.nanoTime();
        Map<String, List<Path>> index = new HashMap<>();
        for (Path root : roots) {
            if (Thread.currentThread().isInterrupted()) break;
            if (Files.isDirectory(root)) walk(root, index);
        }
        // In each root's order, a main source file before a test's copy of the same path.
        Comparator<Path> order = Comparator
                .comparingInt((Path p) -> rootIndex(p))
                .thenComparing(SourceFolders::isTest)
                .thenComparingInt(Path::getNameCount)
                .thenComparing(Path::toString);
        index.values().forEach(list -> list.sort(order));
        byName = index;
        walkedAt = clock.getAsLong();
        log.debug("Indexed {} source file names under {} in {} ms", index.size(), roots,
                (System.nanoTime() - start) / 1_000_000);
    }

    private static void walk(Path root, Map<String, List<Path>> index) {
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                    return !dir.equals(root) && SKIP.contains(String.valueOf(dir.getFileName()))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = String.valueOf(file.getFileName());
                    int dot = name.lastIndexOf('.');
                    if (dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                        index.computeIfAbsent(name, k -> new ArrayList<>()).add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;   // an unreadable folder: look elsewhere
                }
            });
        } catch (IOException e) {
            log.debug("Couldn't index {}", root, e);
        }
    }

    private int rootIndex(Path file) {
        for (int i = 0; i < roots.size(); i++) {
            if (file.startsWith(roots.get(i))) return i;
        }
        return roots.size();
    }

    /** Whether the file is under a {@code test} folder ({@code src/test/java}). */
    private static boolean isTest(Path file) {
        for (Path part : file) {
            if (part.toString().equals("test")) return true;
        }
        return false;
    }
}
