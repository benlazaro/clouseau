package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.PluginCatalog;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which installed plugins have a newer version (D-33): one per app, held by {@link WindowManager}.
 * <p>
 * A plugin is checked against the repository Browse installed it from (its origin, in the
 * settings); a JAR put in the plugins folder by hand is checked against Maven Central when the
 * plugin list names it. Anything else isn't checked. The installed version comes from the file
 * name ({@code <artifact>-<version>.jar}), as in Browse.
 * <p>
 * {@link #check} asks the repositories in the background; the result, and every change to it,
 * reaches the listeners on the EDT (the Tools menu, the Plugin Manager, the notice).
 */
@Slf4j
final class PluginUpdates {

    /**
     * A newer version of an installed plugin.
     *
     * @param latest     what the repository has
     * @param installed  the version in the plugins folder
     * @param file       its file
     * @param repository the repository's name, as Browse lists it
     */
    record Update(PluginRepository.Asset latest, String installed, Path file, String repository) {
        String artifactId() { return latest.artifactId(); }
    }

    /** What to check: an installed plugin and where it came from. */
    record Candidate(String groupId, String artifactId, String repository, String installed, Path file) {}

    private final Path pluginsDir;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile List<Update> current = List.of();
    private boolean checking;

    PluginUpdates(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    /** The updates found by the last check, less those installed since. */
    List<Update> current() { return current; }

    /** The update for the plugin in {@code file}, or null. */
    Update forFile(Path file) {
        if (file == null) return null;
        Path f = file.toAbsolutePath().normalize();
        for (Update u : current) if (u.file().toAbsolutePath().normalize().equals(f)) return u;
        return null;
    }

    /** Called on the EDT whenever the updates change. */
    void addListener(Runnable r)    { listeners.add(r); }
    void removeListener(Runnable r) { listeners.remove(r); }

    /**
     * Checks in the background, then calls {@code done} on the EDT with the updates (also when
     * the check failed, with what's known). A check already running is left to finish.
     */
    void check(Consumer<List<Update>> done) {
        if (checking) return;
        checking = true;
        Map<String, AppPrefs.PluginOrigin> origins = AppPrefs.getPluginOrigins();
        List<PluginRepository> repos = PluginRepository.configured();
        new SwingWorker<List<Update>, Void>() {
            @Override protected List<Update> doInBackground() throws Exception {
                return find(fileNames(pluginsDir), pluginsDir, origins, repos);
            }
            @Override protected void done() {
                checking = false;
                try {
                    set(get());
                } catch (Exception e) {
                    log.warn("Plugin update check failed", e);
                }
                if (done != null) done.accept(current);
            }
        }.execute();   // short-lived: fine on the shared pool (D-22 is about workers that never end)
    }

    /**
     * The plugins folder changed (an install, an update, a delete): drops the updates that no
     * longer apply, without asking the repositories again.
     */
    void folderChanged() {
        List<String> files = fileNames(pluginsDir);
        set(current.stream().filter(u -> {
            String now = newest(PluginCatalog.installedVersions(u.artifactId(), files));
            return now != null && BrowsePluginsPanel.compareVersions(now, u.latest().version()) < 0;
        }).toList());
    }

    private void set(List<Update> updates) {
        boolean changed = !updates.equals(current);
        current = List.copyOf(updates);
        if (changed) listeners.forEach(Runnable::run);
    }

    // ── The check ─────────────────────────────────────────────────────────────

    /**
     * The updates for the plugins in {@code files}. Blocking; a repository that fails or
     * doesn't have a plugin only leaves that plugin out.
     */
    static List<Update> find(List<String> files, Path dir, Map<String, AppPrefs.PluginOrigin> origins,
                             List<PluginRepository> repos) throws InterruptedException {
        List<Candidate> candidates = candidates(files, dir, origins, () -> centralCatalog(repos));
        List<Update> out = new ArrayList<>();
        for (Candidate c : candidates) {
            PluginRepository repo = repos.stream().filter(r -> r.getName().equals(c.repository())).findFirst().orElse(null);
            if (repo == null) {
                log.info("Not checking {} for updates: its repository {} is gone", c.artifactId(), c.repository());
                continue;
            }
            try {
                PluginRepository.Asset latest = repo.latest(c.groupId(), c.artifactId());
                if (latest != null && BrowsePluginsPanel.compareVersions(c.installed(), latest.version()) < 0)
                    out.add(new Update(latest, c.installed(), c.file(), c.repository()));
            } catch (IOException e) {
                log.info("Could not check {} for updates on {}: {}", c.artifactId(), c.repository(), e.getMessage());
            }
        }
        log.info("Plugin update check: {} plugin(s) checked, {} update(s)", candidates.size(), out.size());
        return out;
    }

    /** The plugin list, for plugins installed by hand; empty if it can't be read. */
    private static List<PluginCatalog.Entry> centralCatalog(List<PluginRepository> repos) {
        for (PluginRepository r : repos) {
            if (r instanceof MavenCentralPluginRepository central) {
                try {
                    return central.catalog();
                } catch (IOException e) {
                    log.info("Could not read the plugin list: {}", e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return List.of();
    }

    /** Where the catalog comes from; read only if a file isn't covered by an origin. */
    interface CatalogSource { List<PluginCatalog.Entry> get(); }

    /**
     * The installed plugins to check: each origin whose artifact is in the folder, then each
     * plugin on the list whose artifact is in the folder without an origin (installed by hand,
     * checked against Maven Central).
     */
    static List<Candidate> candidates(List<String> files, Path dir, Map<String, AppPrefs.PluginOrigin> origins,
                                      CatalogSource catalog) {
        List<Candidate> out = new ArrayList<>();
        List<String> covered = new ArrayList<>();
        origins.forEach((artifact, origin) -> {
            String v = newest(PluginCatalog.installedVersions(artifact, files));
            if (v == null) return;
            out.add(new Candidate(origin.groupId(), artifact, origin.repository(), v, file(dir, files, artifact, v)));
            covered.addAll(filesOf(artifact, files));
        });
        boolean uncovered = files.stream().anyMatch(f -> !covered.contains(f) && isPluginFile(f));
        if (!uncovered) return out;
        for (PluginCatalog.Entry e : catalog.get()) {
            if (origins.containsKey(e.artifactId())) continue;
            String v = newest(PluginCatalog.installedVersions(e.artifactId(), files));
            if (v != null) out.add(new Candidate(e.groupId(), e.artifactId(), "Maven Central", v,
                    file(dir, files, e.artifactId(), v)));
        }
        return out;
    }

    private static boolean isPluginFile(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return !n.startsWith(".") && (n.endsWith(".jar") || n.endsWith(".zip"));
    }

    private static List<String> filesOf(String artifact, Collection<String> files) {
        return files.stream().filter(f -> !PluginCatalog.installedVersions(artifact, List.of(f)).isEmpty()).toList();
    }

    private static Path file(Path dir, List<String> files, String artifact, String version) {
        for (String f : files)
            if (PluginCatalog.installedVersions(artifact, List.of(f)).contains(version)) return dir.resolve(f);
        return dir.resolve(artifact + "-" + version + ".jar");
    }

    private static String newest(List<String> versions) {
        return versions.stream().max(BrowsePluginsPanel::compareVersions).orElse(null);
    }

    static List<String> fileNames(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Could not list {}", dir, e);
            return List.of();
        }
    }
}
