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

    /**
     * Snapshot of a plugin's identity and runtime state. A file in the plugins folder that
     * PF4J couldn't load is listed too, with state "Failed", its file name as id and name, and
     * the reason in {@code error}.
     *
     * @param name        what to call it: the manifest's {@code Plugin-Name}, or the id made readable
     * @param description the manifest's {@code Plugin-Description}, or empty
     * @param provider    the manifest's {@code Plugin-Provider}, or empty
     * @param license     the manifest's {@code Plugin-License}, or empty
     * @param error       why it failed, or null
     * @param file        the JAR (or folder) it came from, or null
     */
    public record PluginInfo(String id, String name, String description, String provider, String license,
                             String version, String types, String state, boolean enabled,
                             String error, Path file) {
        public boolean failed() { return "Failed".equals(state); }
    }

    private final PluginManager pf4j;

    public ClouseauPluginManager(Path pluginsDir) {
        this.pf4j = new DefaultPluginManager(pluginsDir);
    }

    public Path getPluginsRoot() {
        return pf4j.getPluginsRoot();
    }

    /** The loaded plugins, then the files in the plugins folder that didn't load, each by name. */
    public List<PluginInfo> getPluginInfos() {
        List<PluginInfo> out = new java.util.ArrayList<>();
        java.util.Set<Path> loaded = new java.util.HashSet<>();
        for (PluginWrapper pw : pf4j.getPlugins()) {
            org.pf4j.PluginDescriptor d = pw.getDescriptor();
            Path file = pw.getPluginPath();
            if (file != null) loaded.add(file.toAbsolutePath().normalize());
            Throwable failure = pw.getFailedException();
            String error = pw.getPluginState() == PluginState.FAILED
                    ? (failure != null && failure.getMessage() != null ? failure.getMessage() : "Couldn't be started.")
                    : null;
            out.add(new PluginInfo(pw.getPluginId(), displayName(pw.getPluginId(), file),
                    blank(d.getPluginDescription()), blank(d.getProvider()), blank(d.getLicense()),
                    d.getVersion(), deriveTypes(pw.getPluginId()), friendlyState(pw),
                    pw.getPluginState() == PluginState.STARTED, error, file));
        }
        out.sort(java.util.Comparator.comparing(i -> i.name().toLowerCase(java.util.Locale.ROOT)));
        for (Path file : unloadedFiles(loaded)) {
            String fileName = file.getFileName().toString();
            out.add(new PluginInfo(fileName, fileName, "", "", "", "", "\u2014", "Failed", false,
                    whyNotLoaded(file), file));
        }
        return out;
    }

    /**
     * JARs and ZIPs in the plugins folder that no loaded plugin came from. PF4J unpacks a ZIP
     * into a folder of the same name and loads that, so a ZIP counts as loaded when its folder is.
     */
    private List<Path> unloadedFiles(java.util.Set<Path> loaded) {
        Path root = getPluginsRoot();
        if (root == null || !java.nio.file.Files.isDirectory(root)) return List.of();
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(root)) {
            return files.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (n.startsWith(".") || !(n.endsWith(".jar") || n.endsWith(".zip"))) return false;
                        Path abs = f.toAbsolutePath().normalize();
                        if (loaded.contains(abs)) return false;
                        return !(n.endsWith(".zip") && loaded.contains(
                                abs.resolveSibling(f.getFileName().toString().replaceFirst("(?i)\\.zip$", ""))));
                    })
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            log.warn("Could not list {}", root, e);
            return List.of();
        }
    }

    /** Why PF4J passed over a file, in words a user can act on. */
    String whyNotLoaded(Path file) {
        java.util.jar.Manifest manifest;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file.toFile())) {
            manifest = jar.getManifest();
        } catch (java.io.IOException e) {
            return "Not a JAR or ZIP file, or it's damaged.";
        }
        String id = manifest != null ? manifest.getMainAttributes().getValue("Plugin-Id") : null;
        if (id == null || id.isBlank()) return "Not a Clouseau plugin: its manifest has no Plugin-Id.";
        if (pf4j.getPlugin(id.trim()) != null) {
            return "Another file already provides plugin \u201c" + id.trim() + "\u201d.";
        }
        return "Couldn't be loaded. The log file has the details.";
    }

    /** The manifest's Plugin-Name if the plugin gives one, otherwise its id made readable. */
    static String displayName(String id, Path file) {
        if (file != null && java.nio.file.Files.isRegularFile(file)) {
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file.toFile())) {
                java.util.jar.Manifest m = jar.getManifest();
                String name = m != null ? m.getMainAttributes().getValue("Plugin-Name") : null;
                if (name != null && !name.isBlank()) return name.trim();
            } catch (java.io.IOException ignored) {
                // fall back to the id
            }
        }
        return readableId(id);
    }

    /** "clouseau-loki-source-plugin" reads "Loki source". */
    static String readableId(String id) {
        String s = id.replaceFirst("(?i)^clouseau[-_.]", "").replaceFirst("(?i)[-_.]plugin$", "")
                .replace('-', ' ').replace('_', ' ').trim();
        if (s.isEmpty()) return id;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String blank(String s) { return s == null ? "" : s.trim(); }

    /**
     * Names, sizes and modification times of what's in the plugins folder: changes when a
     * file is added, removed or replaced, so a window can reload without a Refresh button.
     */
    public String folderStamp() {
        Path root = getPluginsRoot();
        if (root == null || !java.nio.file.Files.isDirectory(root)) return "";
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(root)) {
            return files.sorted().map(f -> {
                try {
                    return f.getFileName() + ":" + java.nio.file.Files.size(f) + ":"
                            + java.nio.file.Files.getLastModifiedTime(f).toMillis();
                } catch (java.io.IOException e) {
                    return f.getFileName().toString();
                }
            }).collect(java.util.stream.Collectors.joining("|"));
        } catch (java.io.IOException e) {
            return "";
        }
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

    /** Deletes a file in the plugins folder that didn't load as a plugin. */
    public boolean deleteUnloadedFile(Path file) {
        Path root = getPluginsRoot();
        if (file == null || root == null
                || !file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) return false;
        try {
            boolean deleted = java.nio.file.Files.deleteIfExists(file);
            log.info("Deleted unloaded plugin file {}", file);
            return deleted;
        } catch (java.io.IOException e) {
            log.warn("Could not delete {}", file, e);
            return false;
        }
    }

    public void stopAll() {
        log.info("Stopping all plugins");
        pf4j.stopPlugins();
        pf4j.unloadPlugins();
    }
}
