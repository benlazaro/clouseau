package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SessionStore;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.formdev.flatlaf.FlatLaf;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import javax.swing.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
public final class ClouseauApp {

    public static void main(String[] args) {
        // Configure Log4j2 from <data dir>/log4j2.xml (the bundled copy is named default-log4j2.xml
        // so Log4j2 does not auto-load it and create a log file before the data dir is known)
        configureLogging();

        String version = AppPrefs.getAppVersion();
        log.info("Starting Clouseau Log Viewer {} (Java {}, {} {})", version,
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"));
        log.info("Data dir: {}{}", AppDirs.dataDir(), AppDirs.isPortable() ? " (portable)" : "");

        // Use an array so the IPC lambda can capture the reference set later on the EDT.
        WindowManager[] windowsRef = new WindowManager[1];
        // Files the OS asked us to open before the window existed (EDT only).
        List<Path> pendingOpens = new java.util.ArrayList<>();

        // Checked before the theme, splash and plugins, so a launch that only hands its files
        // to the running window shows nothing of its own and hands them over quickly.
        boolean isPrimary = SingleInstanceManager.tryBecomePrimary(version, filePaths ->
                SwingUtilities.invokeLater(() -> {
                    List<Path> files = filePaths.stream().map(Path::of).toList();
                    WindowManager windows = windowsRef[0];
                    if (windows != null && windows.isShown()) {
                        windows.handOver(files);   // brings a window forward too (#5)
                    } else {
                        pendingOpens.addAll(files);   // window not shown yet; it comes up anyway
                    }
                }));

        if (!isPrimary) {
            // Forward any CLI file args to the running instance, then exit. Paths are made
            // absolute here: the running instance has its own working directory. With no
            // files, an empty message still brings the running window to the front.
            List<String> paths = java.util.Arrays.stream(args)
                    .map(a -> Path.of(a).toAbsolutePath().toString())
                    .toList();
            SingleInstanceManager.Reply reply = SingleInstanceManager.sendToPrimary(version, paths);
            switch (reply.delivery()) {
                case DELIVERED -> {
                    log.info("Delegated to existing instance — exiting.");
                    System.exit(0);
                }
                // Handing over would show the running version, not the one the user started
                // (after an upgrade, the old one still running): say so instead.
                case OTHER_VERSION -> {
                    showOtherVersionRunning(reply.running(), version);
                    System.exit(0);
                }
                case FAILED -> {
                    if (!paths.isEmpty()) {
                        showNotDelivered(paths);
                        System.exit(1);
                    }
                    System.exit(0);
                }
                // The port couldn't be bound, yet nobody answers on it (on Windows, a range
                // reserved by Hyper-V, WSL or Docker): start anyway rather than exit silently.
                case NO_INSTANCE -> log.warn("No running instance answered; starting without "
                        + "single-instance support, so later launches open their own window.");
            }
        }

        applyTheme();
        installOpenFileHandler(windowsRef, pendingOpens);
        SplashWindow splash = SplashWindow.createAndShow();

        // Plugin parsers — loaded from the plugins directory; may be empty if dir not found
        String pluginsDirProp = System.getProperty("clouseau.plugins.dir");
        Path pluginsDir = pluginsDirProp != null
                ? Path.of(pluginsDirProp)
                : AppDirs.dataDir().resolve("plugins");

        if (splash != null) splash.setStatus(Messages.get("splash.loading.plugins"));
        ClouseauPluginManager pluginManager = new ClouseauPluginManager(pluginsDir);
        pluginManager.loadAll();
        log.info("Plugin manager loaded");

        Runtime.getRuntime().addShutdownHook(new Thread(pluginManager::stopAll, "plugin-shutdown"));

        if (splash != null) splash.setStatus(Messages.get("splash.starting"));

        // Only the running instance may do this: copies in use belong to its open tabs. An
        // instance started without the port can't know whether another one owns them.
        if (isPrimary) {
            com.droidenx.clouseau.core.LogCache.clean(AppDirs.cacheDir());
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> com.droidenx.clouseau.core.LogCache.clean(AppDirs.cacheDir()), "cache-cleanup"));
        }

        SwingUtilities.invokeLater(() -> {
            WindowManager windows = new WindowManager(pluginManager);
            SessionManager sessions = new SessionManager(windows, SessionManager.lastSessionFile());
            windows.setSessionManager(sessions);
            // UC-17: reopen the last session's windows, or start with one empty window.
            String damaged = null;
            List<MainFrame> frames = List.of();
            if (AppPrefs.isRestoreSession()) {
                switch (SessionStore.read(sessions.file())) {
                    case SessionStore.ReadResult.Read r -> {
                        frames = windows.restore(r.session(), null);
                        sessions.restored(r.session());
                    }
                    case SessionStore.ReadResult.Damaged d -> {
                        SessionStore.setAside(sessions.file());
                        damaged = d.reason();
                    }
                    case SessionStore.ReadResult.Missing m -> { }
                }
            }
            if (frames.isEmpty()) frames = List.of(windows.createFirstWindow());
            windowsRef[0] = windows;

            List<MainFrame> shown = frames;
            String damagedReason = damaged;
            Runnable show = () -> {
                for (int i = shown.size() - 1; i >= 0; i--) shown.get(i).setVisible(true);
                shown.get(0).toFront();
                windows.openFiles(java.util.Arrays.stream(args).map(Path::of).toList());
                pendingOpens.forEach(windows::openFile);
                pendingOpens.clear();
                if (AppPrefs.isRestoreSession()) sessions.start();
                windows.updates().start();
                if (damagedReason != null) {
                    log.warn("Last session could not be read ({}); set aside as session.json.bad", damagedReason);
                    JOptionPane.showMessageDialog(shown.get(0), Messages.get("session.damaged.message"),
                            Messages.get("session.damaged.title"), JOptionPane.WARNING_MESSAGE);
                }
            };

            if (splash != null) {
                splash.close(show);
            } else {
                show.run();
            }
        });
    }

    /** Sets up FlatLaf and the user's theme. Call once, before the first window. */
    private static void applyTheme() {
        // Message boxes (Yes/No, OK/Cancel, prompts) put Cancel or No before the default
        // button, as every Clouseau dialog does. Swing lays them out the other way round by
        // default. Set as a developer default, so it outlasts theme changes.
        UIManager.put("OptionPane.isYesLast", Boolean.TRUE);
        FlatLaf.registerCustomDefaultsSource("com.droidenx.clouseau.ui.theme");
        ThemeManager.applyTheme(AppPrefs.getTheme());
    }

    /** Tells the user that the running instance couldn't be reached, so the files didn't open. */
    private static void showNotDelivered(List<String> paths) {
        String message = java.text.MessageFormat.format(Messages.get("app.instance.unreachable.message"),
                String.valueOf(SingleInstanceManager.PORT), String.join("\n", paths));
        showInstanceMessage(message, JOptionPane.ERROR_MESSAGE);
    }

    /** Tells the user that another version is running, so this one won't start. */
    private static void showOtherVersionRunning(String running, String version) {
        showInstanceMessage(otherVersionMessage(running, version), JOptionPane.WARNING_MESSAGE);
    }

    /** The message for {@link #showOtherVersionRunning}; {@code running} is null for a version too old to say. */
    static String otherVersionMessage(String running, String version) {
        return running != null
                ? Messages.format("app.instance.other.version.message", running, version)
                : Messages.format("app.instance.older.version.message", version);
    }

    private static void showInstanceMessage(String message, int type) {
        applyTheme();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JOptionPane.showMessageDialog(null, message, Messages.get("app.instance.unreachable.title"), type);
            });
        } catch (Exception e) {
            log.error("Could not show the message about the running instance", e);
        }
    }

    /**
     * On macOS, files opened from Finder through a file association (or dropped on the
     * Dock icon) arrive as an "open files" event instead of command-line arguments.
     * Other platforms don't support this action and pass files as arguments.
     */
    private static void installOpenFileHandler(WindowManager[] windowsRef, List<Path> pending) {
        if (!java.awt.Desktop.isDesktopSupported()) return;
        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
        if (!desktop.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE)) return;
        desktop.setOpenFileHandler(e -> {
            List<Path> files = e.getFiles().stream().map(java.io.File::toPath).toList();
            SwingUtilities.invokeLater(() -> {
                WindowManager windows = windowsRef[0];
                if (windows != null && windows.isShown()) windows.handOver(files);
                else pending.addAll(files);
            });
        });
    }

    /** Configure Log4j2 to load config from the data dir, creating it if needed. */
    private static void configureLogging() {
        Path clouseauDir = AppDirs.dataDir();
        Path configFile = clouseauDir.resolve("log4j2.xml");

        try {
            // Create directory if it doesn't exist
            Files.createDirectories(clouseauDir);
            System.setProperty("clouseau.log.dir", clouseauDir.resolve("logs").toString());

            // Copy default config to the data dir if not present
            if (!Files.exists(configFile)) {
                try (InputStream in = ClouseauApp.class.getResourceAsStream("/default-log4j2.xml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    }
                }
            }

            // Configure Log4j2 to use the config file
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.setConfigLocation(configFile.toUri());
        } catch (Exception e) {
            System.err.println("Failed to configure logging: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
