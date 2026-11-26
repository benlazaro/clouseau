package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SessionStore;
import com.droidenx.clouseau.runtime.ClouseauPluginManager;
import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.formdev.flatlaf.FlatLaf;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import javax.swing.*;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
public final class ClouseauApp {

    public static void main(String[] args) {
        // D-35: check a plugin and exit, with no window, before the single-instance check, so it
        // also runs while Clouseau is open.
        if (PluginCheckCommand.handles(args)) {
            System.setProperty("java.awt.headless", "true");
            configureLogging();
            PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            System.exit(PluginCheckCommand.run(args, out));
        }

        // Configure Log4j2 from <data dir>/log4j2.xml (the bundled copy is named default-log4j2.xml
        // so Log4j2 does not auto-load it and create a log file before the data dir is known)
        configureLogging();
        // From here on, an error no code handled is logged and shown, not lost.
        CrashHandler.install();

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
        applyTooltipTimings();
    }

    /**
     * Shows tooltips sooner and keeps them up while the mouse stays on the component, so a
     * long one (the status bar's row counts, its timezone badge) can be read to the end.
     * Swing's defaults wait 750 ms and hide every tooltip after 4 s, however long it is.
     */
    static void applyTooltipTimings() {
        ToolTipManager tooltips = ToolTipManager.sharedInstance();
        tooltips.setInitialDelay(TOOLTIP_INITIAL_DELAY_MS);
        tooltips.setReshowDelay(TOOLTIP_RESHOW_DELAY_MS);
        tooltips.setDismissDelay(Integer.MAX_VALUE);
    }

    static final int TOOLTIP_INITIAL_DELAY_MS = 300;
    static final int TOOLTIP_RESHOW_DELAY_MS = 250;

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

    /**
     * Shows the message in a dialog of its own, with a taskbar button and in front. Not
     * {@code JOptionPane.showMessageDialog(null, …)}: its dialog belongs to Swing's hidden
     * shared frame, so on Windows it has no taskbar button and can open behind other
     * windows, and the launch then waits unseen for an OK that never comes.
     */
    private static void showInstanceMessage(String message, int type) {
        applyTheme();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JOptionPane pane = new JOptionPane(message, type);
                JDialog dialog = new JDialog((java.awt.Window) null,
                        Messages.get("app.instance.unreachable.title"), java.awt.Dialog.ModalityType.APPLICATION_MODAL);
                dialog.setIconImages(MainFrame.loadAppIcons());
                dialog.setContentPane(pane);
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, e -> dialog.dispose());
                dialog.pack();
                dialog.setLocationRelativeTo(null);
                // Started from Explorer, the launch may not be allowed to take the focus; on top,
                // the message is seen anyway. Dropped once shown, so it doesn't stay over everything.
                dialog.setAlwaysOnTop(true);
                dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override public void windowOpened(java.awt.event.WindowEvent e) {
                        dialog.toFront();
                        dialog.setAlwaysOnTop(false);
                    }
                });
                dialog.setVisible(true);   // modal: returns once the user closes it
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
            System.setProperty("clouseau.log.dir", AppDirs.logsDir().toString());

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
