package com.droidenx.clouseau.runtime;

import com.droidenx.clouseau.api.LogFormatter;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.api.LogSourceProvider;
import com.droidenx.clouseau.api.LogSyntaxHighlighter;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import java.nio.file.Path;
import java.util.List;

/**
 * Bootstraps PF4J and exposes typed extension lookups.
 *
 * Drop a plugin JAR into the plugins/ folder and call refresh()
 * to hot-reload without restarting the app.
 */
@Slf4j
public final class ClouseauPluginManager {

    /** Snapshot of a loaded plugin's identity and runtime state. */
    public record PluginInfo(String id, String version, String types, String state, boolean enabled) {}

    private final PluginManager pf4j;

    public ClouseauPluginManager(Path pluginsDir) {
        this.pf4j = new DefaultPluginManager(pluginsDir);
    }

    public Path getPluginsRoot() {
        return pf4j.getPluginsRoot();
    }

    public List<PluginInfo> getPluginInfos() {
        return pf4j.getPlugins().stream()
                .map(pw -> new PluginInfo(
                        pw.getPluginId(),
                        pw.getDescriptor().getVersion(),
                        deriveTypes(pw.getPluginId()),
                        friendlyState(pw),
                        pw.getPluginState() == PluginState.STARTED))
                .toList();
    }

    private String deriveTypes(String pluginId) {
        List<String> types = new java.util.ArrayList<>();
        if (!pf4j.getExtensions(LogParser.class,           pluginId).isEmpty()) types.add("Parser");
        if (!pf4j.getExtensions(LogFormatter.class,        pluginId).isEmpty()) types.add("Formatter");
        if (!pf4j.getExtensions(LogSyntaxHighlighter.class, pluginId).isEmpty()) types.add("Highlighter");
        if (!pf4j.getExtensions(LogSourceProvider.class,    pluginId).isEmpty()) types.add("Source");
        return types.isEmpty() ? "\u2014" : String.join(", ", types);
    }

    /**
     * Returns a human-readable list of detected extensions for a plugin, grouped by type.
     * e.g. ["Parsers:  Log4j Pattern, Log4j JSON", "Formatter:  JSON"]
     */
    public List<String> getExtensionDetails(String pluginId) {
        List<String> details = new java.util.ArrayList<>();
        addGroup(details, "Parser",      pf4j.getExtensions(LogParser.class,            pluginId).stream().map(LogParser::getName).toList());
        addGroup(details, "Formatter",   pf4j.getExtensions(LogFormatter.class,         pluginId).stream().map(LogFormatter::getName).toList());
        addGroup(details, "Highlighter", pf4j.getExtensions(LogSyntaxHighlighter.class, pluginId).stream().map(LogSyntaxHighlighter::getName).toList());
        addGroup(details, "Source",      pf4j.getExtensions(LogSourceProvider.class,    pluginId).stream().map(LogSourceProvider::getName).toList());
        return details;
    }

    private static void addGroup(List<String> out, String label, List<String> names) {
        if (names.isEmpty()) return;
        String plural = names.size() == 1 ? label : label + "s";
        out.add(plural + ":  " + String.join(", ", names));
    }

    public void enablePlugin(String id) {
        pf4j.enablePlugin(id);
        pf4j.startPlugin(id);
        log.info("Enabled plugin '{}'", id);
    }

    public void disablePlugin(String id) {
        pf4j.stopPlugin(id);
        pf4j.disablePlugin(id);
        log.info("Disabled plugin '{}'", id);
    }

    private static String friendlyState(PluginWrapper pw) {
        return switch (pw.getPluginState()) {
            case STARTED  -> "Enabled";
            case STOPPED  -> "Stopped";
            case DISABLED -> "Disabled";
            case FAILED   -> "Failed";
            default       -> pw.getPluginState().toString();
        };
    }

    public void loadAll() {
        log.info("Loading plugins from {}", pf4j.getPluginsRoot());
        pf4j.loadPlugins();
        pf4j.startPlugins();
        log.info("Loaded {} plugin(s)", pf4j.getPlugins().size());
    }

    /** Unload stale JARs and load any new ones — supports hot-reload. */
    public void refresh() {
        log.info("Refreshing plugins");
        pf4j.stopPlugins();
        pf4j.unloadPlugins();
        pf4j.loadPlugins();
        pf4j.startPlugins();
        log.info("Refresh complete — {} plugin(s) active", pf4j.getPlugins().size());
    }

    /** Return all registered extensions of a given type across all active plugins. */
    public <T> List<T> getExtensions(Class<T> type) {
        return pf4j.getExtensions(type);
    }

    /**
     * Stops, unloads, and deletes the JAR for the given plugin id.
     * Returns true if the file was successfully deleted.
     */
    public boolean deletePlugin(String id) {
        PluginWrapper pw = pf4j.getPlugin(id);
        if (pw == null) return false;
        java.nio.file.Path jar = pw.getPluginPath();
        pf4j.deletePlugin(id);
        log.info("Deleted plugin '{}' ({})", id, jar);
        return true;
    }

    public void stopAll() {
        log.info("Stopping all plugins");
        pf4j.stopPlugins();
        pf4j.unloadPlugins();
    }
}
