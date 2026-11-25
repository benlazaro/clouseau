package com.droidenx.clouseau.ui;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the application data directory (DESIGN.md 7).
 *
 * <p>Priority:
 * <ol>
 *   <li>{@code -Dclouseau.data.dir=<path>} — explicit override; a leading {@code ~/} means the
 *       home folder (the macOS/Linux packages set {@code ~/.clouseau})</li>
 *   <li>Windows jpackage image (zip or per-user installer) — {@code <install>/data/} if that
 *       folder exists (portable), otherwise {@code ~/.clouseau}. The folder is the switch: it
 *       can't be a setting, because the settings live in the data folder.</li>
 *   <li>Anything else (dev / {@code gradlew run}, bare {@code java -jar}) — {@code ./data/}
 *       in the working directory</li>
 * </ol>
 */
public final class AppDirs {

    /** The folder that makes a Windows install portable, next to {@code Clouseau.exe}. */
    static final String PORTABLE_FOLDER = "data";
    /** The data folder in the home folder, when Clouseau isn't portable. */
    static final String HOME_FOLDER = ".clouseau";

    private static final String PROPERTY = System.getProperty("clouseau.data.dir");
    private static final Path INSTALL_ROOT = findInstallRoot();
    private static final Path DATA_DIR = resolve(PROPERTY, INSTALL_ROOT,
            Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath());

    private AppDirs() {}

    /** Returns the resolved data directory. Always the same value for a given JVM run. */
    public static Path dataDir() { return DATA_DIR; }

    /** Where annotations go when a log's own folder can't be written (D-9). */
    public static Path annotationsDir() { return DATA_DIR.resolve("annotations"); }

    /** Decompressed copies of the compressed logs open in tabs (D-16); emptied at startup. */
    public static Path cacheDir() { return DATA_DIR.resolve("cache"); }

    /** Clouseau's own log, {@code clouseau.log} (see {@code default-log4j2.xml}). */
    public static Path logsDir() { return DATA_DIR.resolve("logs"); }

    /**
     * {@code true} when a {@code data} folder next to {@code Clouseau.exe} chooses between
     * portable and the home folder: running from a Windows jpackage image, without
     * {@code -Dclouseau.data.dir}, which would win over the folder.
     */
    public static boolean canBePortable() { return INSTALL_ROOT != null && PROPERTY == null; }

    /** {@code true} when data lives next to the install (a Windows image with a {@code data} folder). */
    public static boolean isPortable() {
        return INSTALL_ROOT != null && DATA_DIR.equals(INSTALL_ROOT.resolve(PORTABLE_FOLDER));
    }

    /** The data folder of a Windows install that isn't portable: {@code ~/.clouseau}. */
    public static Path homeDataDir() { return Path.of(System.getProperty("user.home"), HOME_FOLDER); }

    /**
     * The data folder for these facts: the {@code clouseau.data.dir} property (or null), the
     * Windows install root (or null), the home folder and the working directory.
     */
    static Path resolve(String property, Path installRoot, Path home, Path workingDir) {
        // 1. Explicit system property override
        if (property != null) {
            if (property.equals("~") || property.startsWith("~/") || property.startsWith("~\\")) {
                return home.resolve(property.substring(1).replaceFirst("^[/\\\\]", ""));
            }
            return Path.of(property);
        }

        // 2. Windows jpackage image: portable if a data folder sits next to the launcher
        if (installRoot != null) {
            Path portable = installRoot.resolve(PORTABLE_FOLDER);
            return Files.isDirectory(portable) ? portable : home.resolve(HOME_FOLDER);
        }

        // 3. Dev / bare jar: data folder in the working directory
        return workingDir.resolve("data");
    }

    /**
     * Returns the install root (two levels above the running JAR), or {@code null} when not
     * running from a Windows jpackage image. The {@code runtime} sibling check keeps a bare
     * {@code java -jar build/libs/x.jar} from treating {@code build/} as an install root.
     * <pre>
     *   &lt;install&gt;/
     *     app/
     *       clouseau.jar   (code source)
     *     runtime/
     *     Clouseau.exe
     *     data/            (optional: makes the install portable)
     * </pre>
     */
    private static Path findInstallRoot() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        try {
            URL location = AppDirs.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) return null;
            Path jarPath = Path.of(location.toURI());
            if (Files.isRegularFile(jarPath)) {
                Path root = jarPath.getParent().getParent();
                if (Files.isDirectory(root.resolve("runtime"))) return root;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
