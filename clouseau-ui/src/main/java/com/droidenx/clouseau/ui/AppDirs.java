package com.droidenx.clouseau.ui;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the application data directory.
 *
 * <p>Priority:
 * <ol>
 *   <li>{@code -Dclouseau.data.dir=<path>} — explicit override; a leading {@code ~/} means the
 *       home folder (the macOS/Linux packages set {@code ~/.clouseau})</li>
 *   <li>Windows jpackage image (zip or per-user installer) — {@code <install>/data/}</li>
 *   <li>Anything else (dev / {@code gradlew run}, bare {@code java -jar}) — {@code ./data/}
 *       in the working directory</li>
 * </ol>
 */
public final class AppDirs {

    private static final Path DATA_DIR = resolve();

    private AppDirs() {}

    /** Returns the resolved data directory. Always the same value for a given JVM run. */
    public static Path dataDir() { return DATA_DIR; }

    /** {@code true} when data lives next to the install (Windows jpackage image). */
    public static boolean isPortable() { return findInstallRoot() != null; }

    private static Path resolve() {
        // 1. Explicit system property override
        String prop = System.getProperty("clouseau.data.dir");
        if (prop != null) {
            if (prop.equals("~") || prop.startsWith("~/") || prop.startsWith("~\\")) {
                return Path.of(System.getProperty("user.home"), prop.substring(1).replaceFirst("^[/\\\\]", ""));
            }
            return Path.of(prop);
        }

        // 2. Windows jpackage image: store data next to the launcher
        Path installRoot = findInstallRoot();
        if (installRoot != null) return installRoot.resolve("data");

        // 3. Dev / bare jar: data folder in the working directory
        return Path.of("data").toAbsolutePath();
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
