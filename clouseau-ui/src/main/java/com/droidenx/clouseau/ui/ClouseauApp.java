package com.droidenx.clouseau.ui;

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

        log.info("Starting Clouseau Log Viewer");
        log.info("Data dir: {}{}", AppDirs.dataDir(), AppDirs.isPortable() ? " (portable)" : "");

        FlatLaf.registerCustomDefaultsSource("com.droidenx.clouseau.ui.theme");
        ThemeManager.applyTheme(AppPrefs.getTheme());

        SplashWindow splash = SplashWindow.createAndShow();

        // Plugin parsers — loaded from the plugins directory; may be empty if dir not found
        String pluginsDirProp = System.getProperty("clouseau.plugins.dir");
        Path pluginsDir = pluginsDirProp != null
                ? Path.of(pluginsDirProp)
                : AppDirs.dataDir().resolve("plugins");

        if (splash != null) splash.setStatus("Loading plugins...");
        ClouseauPluginManager pluginManager = new ClouseauPluginManager(pluginsDir);
        pluginManager.loadAll();
        log.info("Plugin manager loaded");

        Runtime.getRuntime().addShutdownHook(new Thread(pluginManager::stopAll, "plugin-shutdown"));

        // Use an array so the IPC lambda can capture the reference set later on the EDT.
        MainFrame[] frameRef = new MainFrame[1];

        if (splash != null) splash.setStatus("Starting...");
        boolean isPrimary = SingleInstanceManager.tryBecomePrimary(filePath ->
                SwingUtilities.invokeLater(() -> {
                    if (frameRef[0] != null) frameRef[0].openFile(Path.of(filePath));
                }));

        if (!isPrimary) {
            // Forward any CLI file args to the running instance, then exit.
            if (args.length > 0) {
                for (String arg : args) SingleInstanceManager.sendToPrimary(arg);
            }
            log.info("Delegated to existing instance — exiting.");
            System.exit(0);
        }

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(pluginManager);
            frameRef[0] = frame;

            Runnable show = () -> {
                frame.setVisible(true);
                for (String arg : args) frame.openFile(Path.of(arg));
            };

            if (splash != null) {
                splash.close(show);
            } else {
                show.run();
            }
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
