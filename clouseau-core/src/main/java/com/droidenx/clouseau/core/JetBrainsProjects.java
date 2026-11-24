package com.droidenx.clouseau.core;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which projects a running JetBrains IDE has open, for Open in IDE: a file from a project that
 * isn't open opens without the IDE's navigation, highlighting or search, no better than in a
 * text editor, so Clouseau says to open the project instead.
 *
 * <p>The IDE's built-in server doesn't say (it answers 200 to any file), so this reads the
 * IDE's own record of its recent projects, {@code options/recentProjects.xml} in its config
 * folder ({@code %APPDATA%\JetBrains\IntelliJIdea2026.3} and the like): each project's folder,
 * and {@code opened="true"} on those open now. The IDE writes it as projects open, close and
 * are activated. A project stays {@code opened} after the IDE exits, to reopen on the next
 * start, so only the running IDE's file counts: the config folder for its version, and of
 * those, the one naming its build.
 *
 * <p>Best effort: an IDE with its config elsewhere ({@code idea.config.path}) or a file this
 * can't read gives {@link Optional#empty()}, meaning "can't tell". Blocking: call off the EDT.
 */
@Slf4j
public final class JetBrainsProjects {

    /** A project the IDE knows: its folder, its name, and whether it's open now. */
    public record Project(Path root, String name, boolean open) {}

    private final List<Project> projects;

    JetBrainsProjects(List<Project> projects) {
        this.projects = List.copyOf(projects);
    }

    /**
     * The projects of the running IDE {@code name} ("IntelliJ IDEA 2026.3"), product
     * {@code product} ("IDEA") and {@code build} ("263.7307.200"), as its {@code /api/about}
     * gives them; empty if its record of them can't be found. Blocks.
     */
    public static Optional<JetBrainsProjects> of(String name, String product, String build) {
        Path home = Path.of(System.getProperty("user.home"));
        return recentProjectsFile(configRoot(home), name, product, build).flatMap(file -> {
            try {
                return Optional.of(new JetBrainsProjects(parse(Files.readString(file, StandardCharsets.UTF_8), home)));
            } catch (IOException | RuntimeException e) {
                log.debug("Couldn't read {}", file, e);
                return Optional.empty();
            }
        });
    }

    /** The folders of the projects open in the IDE now. */
    public List<Path> openRoots() {
        return projects.stream().filter(Project::open).map(Project::root).toList();
    }

    /** The project {@code file} is in (the deepest that holds it), if the IDE knows one. */
    public Optional<Project> projectOf(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return projects.stream()
                .filter(p -> abs.startsWith(p.root()))
                .max(Comparator.comparingInt(p -> p.root().getNameCount()));
    }

    /**
     * The folder to open as a project for {@code file}, when the IDE has never had it: the
     * nearest folder above it with an {@code .idea} or {@code .git}, else the file's own folder.
     */
    public static Path likelyRoot(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".idea")) || Files.exists(dir.resolve(".git"))) return dir;
        }
        return abs.getParent() != null ? abs.getParent() : abs;
    }

    /** Where JetBrains IDEs keep their config folders on this system. */
    static Path configRoot(Path home) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String appData = System.getenv("APPDATA");
            return (appData != null ? Path.of(appData) : home.resolve("AppData/Roaming")).resolve("JetBrains");
        }
        if (os.startsWith("mac")) return home.resolve("Library/Application Support/JetBrains");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".config")).resolve("JetBrains");
    }

    private static final Pattern VERSION = Pattern.compile("(\\d{4}\\.\\d+)");

    /**
     * The running IDE's {@code recentProjects.xml} under {@code root}: of the config folders for
     * its version ({@code IntelliJIdea2026.3}, {@code IdeaIC2026.3}), those whose file names its
     * build, else those named after its product; the newest of them.
     */
    static Optional<Path> recentProjectsFile(Path root, String name, String product, String build) {
        Matcher v = VERSION.matcher(name == null ? "" : name);
        if (!v.find() || !Files.isDirectory(root)) return Optional.empty();
        String version = v.group(1);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(d -> d.getFileName().toString().endsWith(version))
                    .map(d -> d.resolve("options/recentProjects.xml"))
                    .filter(Files::isRegularFile)
                    .forEach(files::add);
        } catch (IOException e) {
            return Optional.empty();
        }
        List<Path> byBuild = build == null ? List.of() : files.stream().filter(f -> mentions(f, "-" + build + "\"")).toList();
        List<Path> chosen = !byBuild.isEmpty() ? byBuild
                : files.stream().filter(f -> isProduct(f.getParent().getParent().getFileName().toString(), product)).toList();
        if (chosen.isEmpty() && files.size() == 1) chosen = files;
        return chosen.stream().max(Comparator.comparing(JetBrainsProjects::modified));
    }

    /** Whether config folder {@code dir} ("IntelliJIdea2026.3") is product {@code product} ("IDEA"). */
    static boolean isProduct(String dir, String product) {
        if (product == null || product.isBlank()) return false;
        String d = dir.toLowerCase(Locale.ROOT), p = product.toLowerCase(Locale.ROOT);
        if (p.equals("idea")) return d.startsWith("intellijidea") || d.startsWith("ideaic");
        return d.startsWith(p);
    }

    private static boolean mentions(Path file, String text) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(text);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static final Pattern ENTRY = Pattern.compile(
            "<entry key=\"([^\"]+)\">\\s*<value>\\s*<RecentProjectMetaInfo\\b([^>]*)>");

    /** The projects in a {@code recentProjects.xml}, with {@code $USER_HOME$} as {@code home}. */
    static List<Project> parse(String xml, Path home) {
        List<Project> found = new ArrayList<>();
        Matcher m = ENTRY.matcher(xml);
        while (m.find()) {
            String key = unescape(m.group(1)).replace("$USER_HOME$", home.toString().replace('\\', '/'));
            if (key.contains("$")) continue;   // another macro: a path this can't resolve
            Path root = Path.of(key);
            if (key.endsWith(".ipr")) root = root.getParent();   // an old file-based project
            if (root == null) continue;
            String attrs = m.group(2);
            String name = root.getFileName() != null ? root.getFileName().toString() : key;
            found.add(new Project(root.toAbsolutePath().normalize(), name, attrs.contains("opened=\"true\"")));
        }
        return found;
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
