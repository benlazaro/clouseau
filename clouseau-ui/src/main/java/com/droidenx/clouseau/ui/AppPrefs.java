package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SshSaved;
import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent user preferences stored as {@code ~/.clouseau/settings.json}.
 */
@Slf4j
public final class AppPrefs {

    public static final Path PREFS_DIR  = AppDirs.dataDir();
    private static final Path PREFS_FILE = PREFS_DIR.resolve("settings.json");

    private static final String KEY_TAB_CLOSE_CONFIRM            = "tab.close.confirm";
    private static final String KEY_HIGHLIGHT_CLEAR_ALL_CONFIRM  = "highlight.clear.all.confirm";
    private static final String KEY_ANNOTATION_DELETE_CONFIRM    = "annotation.delete.confirm";
    private static final String KEY_FOLLOW_BY_DEFAULT  = "follow.by.default";
    private static final String KEY_STARTUP_RESTORE    = "startup.restore";
    private static final String KEY_MULTI_OPEN         = "open.several";
    private static final String KEY_DETAIL_FONT_SIZE   = "detail.font.size";
    private static final String KEY_DETAIL_WRAP_LINES  = "detail.wrap.lines";
    private static final String KEY_DETAIL_SPLIT       = "detail.split.ratio";
    private static final String KEY_ROW_HEIGHT         = "row.height";
    private static final String KEY_TABLE_STRIPES      = "table.stripes";
    private static final String KEY_SSH_SAVE           = "ssh.saveConnection";
    private static final String KEY_LAST_OPEN_DIR      = "last.open.dir";
    private static final String KEY_RECENT_FILES       = "recent.files";
    private static final String KEY_RECENT_MAX         = "recent.max";
    private static final String KEY_FAVORITES          = "favorites";
    private static final String KEY_SSH_FAVORITES      = "ssh.favorites";      // before 0.12: one entry per log
    private static final String KEY_SSH_CONNECTIONS    = "ssh.connections";
    private static final String KEY_PLUGIN_REPOS         = "plugin.repos";
    private static final String KEY_MAX_ENTRIES_PER_TAB  = "max.entries.per.tab";
    private static final String KEY_COLUMN_LAYOUTS        = "column.layouts";
    private static final String KEY_COLUMN_LAYOUT_DEFAULT = "column.layout.default";
    private static final String KEY_THEME                 = "theme";
    private static final String KEY_DISPLAY_TIMEZONE      = "display.timezone";
    private static final String KEY_SOURCE_TIMEZONES      = "source.timezones";
    private static final String KEY_SOURCE_SETTINGS       = "source.settings";
    private static final int    MAX_RECENT               = 10;

    /**
     * A configured plugin repository (Nexus 3, etc.).
     * Credentials are encrypted at rest via {@link CredentialStore}.
     */
    public record PluginRepo(
            String name,
            String type,        // "nexus3"
            String url,         // base URL of the server
            String repository,  // Nexus repo name; null = search all
            String authType,    // "none", "basic", "token"
            String username,    // for basic auth; null otherwise
            String credential   // password for basic, token for token; null for none
    ) {}

    /**
     * A saved column layout: column order (visible first, then hidden), per-column width.
     * A field column also keeps its field name ({@code field}), so the layout finds it
     * wherever the model puts it; it's null for the fixed columns and in older layouts.
     */
    public record ColumnLayout(String name, java.util.List<ColumnEntry> columns) {
        public record ColumnEntry(int modelIndex, boolean visible, int width, String field) {
            public ColumnEntry(int modelIndex, boolean visible, int width) {
                this(modelIndex, visible, width, null);
            }
        }
    }

    private static final JsonObject ROOT = load();

    private AppPrefs() {}

    public static String getAppVersion() {
        try (java.io.InputStream is = AppPrefs.class.getResourceAsStream("/version.properties")) {
            if (is == null) return "dev";
            java.util.Properties props = new java.util.Properties();
            props.load(is);
            return props.getProperty("version", "dev");
        } catch (java.io.IOException e) {
            return "dev";
        }
    }

    // ── I/O ──────────────────────────────────────────────────────────────────

    private static JsonObject load() {
        return load(PREFS_FILE);
    }

    /**
     * The settings in {@code file}, or none if it doesn't exist. A file that isn't a JSON object
     * (damaged, or cut short) is moved to {@code settings.json.bad}, replacing an older one, so
     * the next save doesn't overwrite what might still be recovered by hand (DESIGN.md 7).
     */
    static JsonObject load(Path file) {
        if (!Files.exists(file)) return new JsonObject();
        try {
            JsonElement el = JsonFiles.read(file);
            if (el.isJsonObject()) return el.getAsJsonObject();
        } catch (IOException | RuntimeException e) {     // JsonParseException is unchecked
            log.warn("Could not read settings file {}: {}", file, e.toString());
        }
        try {
            log.warn("Moved the unreadable settings to {}; starting with default settings", JsonFiles.moveAside(file));
        } catch (IOException e) {
            log.warn("Could not move {} aside", file, e);
        }
        return new JsonObject();
    }

    private static synchronized void save() {
        try {
            write(PREFS_FILE, ROOT);
        } catch (IOException e) {
            log.warn("Could not write settings file {}", PREFS_FILE, e);
        }
    }

    /**
     * Writes {@code settings} to {@code file} through a temporary file moved into place, so a
     * crash or a full disk mid-write leaves the previous settings, never half a file.
     */
    static void write(Path file, JsonObject settings) throws IOException {
        JsonFiles.write(file, settings);
    }

    // ── Typed accessors ───────────────────────────────────────────────────────
    // A value of the wrong type (written by a newer Clouseau, or edited by hand) reads as
    // missing, so it gets its default rather than an exception (DESIGN.md 7).

    private static boolean getBool(String key, boolean def) {
        return bool(ROOT, key, def);
    }

    static boolean bool(JsonObject o, String key, boolean def) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static void putBool(String key, boolean value) {
        ROOT.addProperty(key, value);
        save();
    }

    private static int getInt(String key, int def) {
        return jsonInt(ROOT, key, def);
    }

    private static void putInt(String key, int value) {
        ROOT.addProperty(key, value);
        save();
    }

    private static String getString(String key, String def) {
        return jsonString(ROOT, key, def);
    }

    private static void putString(String key, String value) {
        ROOT.addProperty(key, value);
        save();
    }

    private static List<String> getStringList(String key) {
        return stringList(ROOT, key);
    }

    static List<String> stringList(JsonObject o, String key) {
        List<String> list = new ArrayList<>();
        for (JsonElement e : array(o, key)) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) list.add(e.getAsString());
        }
        return list;
    }

    /** The array under {@code key}, or an empty one if it's missing or isn't an array. */
    static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    /** The object under {@code key}, or null if it's missing or isn't an object. */
    static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static void putStringList(String key, List<String> values) {
        JsonArray arr = new JsonArray();
        values.forEach(arr::add);
        ROOT.add(key, arr);
        save();
    }

    private static void remove(String key) {
        ROOT.remove(key);
        save();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isFollowByDefault() {
        return getBool(KEY_FOLLOW_BY_DEFAULT, true);
    }

    public static void setFollowByDefault(boolean value) {
        putBool(KEY_FOLLOW_BY_DEFAULT, value);
    }

    /** Whether Clouseau reopens the last session at startup (UC-17); on by default. */
    public static boolean isRestoreSession() {
        return getBool(KEY_STARTUP_RESTORE, true);
    }

    public static void setRestoreSession(boolean value) {
        putBool(KEY_STARTUP_RESTORE, value);
    }

    /** Whether Clouseau tells the user when a new version is released; on by default. */
    public static boolean isUpdateCheck() {
        return getBool("update.check", true);
    }

    public static void setUpdateCheck(boolean value) {
        putBool("update.check", value);
    }

    /** The editor stack frames and logger classes open in (Settings → Source Code). */
    public enum SourceEditor { JETBRAINS, VSCODE, CURSOR }

    /** Where Open in IDE opens source; a running JetBrains IDE unless the user picked another. */
    public static SourceEditor getSourceEditor() {
        try {
            return SourceEditor.valueOf(getString("source.editor", SourceEditor.JETBRAINS.name()));
        } catch (IllegalArgumentException e) {
            return SourceEditor.JETBRAINS;
        }
    }

    public static void setSourceEditor(SourceEditor editor) {
        putString("source.editor", editor.name());
    }

    /**
     * The folders searched for a stack frame's file, for editors that need its full path
     * (VS Code, Cursor): projects, or folders of projects. A JetBrains IDE finds the file itself.
     */
    public static List<String> getSourceFolders() {
        return getStringList("source.folders");
    }

    public static void setSourceFolders(List<String> folders) {
        putStringList("source.folders", folders);
    }

    /** The version the user chose to skip ("Skip This Version"), or null. */
    public static String getSkippedVersion() {
        return getString("update.skipped", null);
    }

    public static void setSkippedVersion(String version) {
        putString("update.skipped", version);
    }

    /** When the last automatic update check ran (epoch milliseconds), or 0 if never. */
    public static long getLastUpdateCheck() {
        try {
            return Long.parseLong(getString("update.last.check", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setLastUpdateCheck(long epochMillis) {
        putString("update.last.check", Long.toString(epochMillis));
    }

    /** What opening several files at once does (D-17). */
    public enum MultiOpen { ASK, MERGE, SEPARATE }

    public static MultiOpen getMultiOpen() {
        try {
            return MultiOpen.valueOf(getString(KEY_MULTI_OPEN, MultiOpen.ASK.name()));
        } catch (IllegalArgumentException e) {
            return MultiOpen.ASK;
        }
    }

    public static void setMultiOpen(MultiOpen value) {
        putString(KEY_MULTI_OPEN, value.name());
    }

    /**
     * The Open dialog's size and its two dividers (favorites | files | details), as last left;
     * null before the first time.
     */
    public static int[] getOpenDialogLayout() {
        String v = getString("open.dialog.layout", null);
        if (v == null) return null;
        try {
            int[] r = java.util.Arrays.stream(v.split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
            return r.length == 4 ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setOpenDialogLayout(int width, int height, int left, int right) {
        putString("open.dialog.layout", width + "," + height + "," + left + "," + right);
    }

    /**
     * The servers and folders folded in the SSH dialog's Saved connections, as keys the dialog
     * makes ("c:" + connection key, "f:" + connection key + "\n" + folder), so they stay folded
     * the next time.
     */
    public static java.util.Set<String> getSshCollapsed() {
        return new java.util.HashSet<>(getStringList("ssh.savedCollapsed"));
    }

    public static void setSshCollapsed(java.util.Set<String> keys) {
        putStringList("ssh.savedCollapsed", new ArrayList<>(new java.util.TreeSet<>(keys)));
    }

    /** The SSH dialog's size and its divider (saved connections | form); null before the first time. */
    public static int[] getSshDialogLayout() {
        String v = getString("ssh.dialog.layout", null);
        if (v == null) return null;
        try {
            int[] r = java.util.Arrays.stream(v.split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
            return r.length == 3 ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setSshDialogLayout(int width, int height, int divider) {
        putString("ssh.dialog.layout", width + "," + height + "," + divider);
    }

    /**
     * A window's size as last left, by a short key the window picks ("settings", "notes");
     * null before the first time.
     */
    public static java.awt.Dimension getWindowSize(String key) {
        String v = getString("window." + key + ".size", null);
        if (v == null) return null;
        try {
            String[] p = v.split(",");
            if (p.length != 2) return null;
            int w = Integer.parseInt(p[0].trim());
            int h = Integer.parseInt(p[1].trim());
            return w > 0 && h > 0 ? new java.awt.Dimension(w, h) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setWindowSize(String key, java.awt.Dimension size) {
        putString("window." + key + ".size", size.width + "," + size.height);
    }

    /** The plugin search results' sort, as {@code column:order} (such as {@code date:descending}); null when unsorted. */
    public static String getPluginSearchSort() { return getString("plugins.browse.sort", null); }

    public static void setPluginSearchSort(String sort) {
        if (sort == null) {
            if (ROOT.remove("plugins.browse.sort") != null) save();
        } else if (!sort.equals(getPluginSearchSort())) {
            putString("plugins.browse.sort", sort);
        }
    }

    /** The repository Browse showed last, by name; null before the first choice. */
    public static String getPluginBrowseRepository() { return getString("plugins.browse.repository", null); }

    public static void setPluginBrowseRepository(String name) {
        if (name != null && !name.equals(getPluginBrowseRepository())) putString("plugins.browse.repository", name);
    }

    /** The plugin search results' column widths, as {@code column:width,…}; null before the first drag. */
    public static String getPluginSearchWidths() { return getString("plugins.browse.widths", null); }

    public static void setPluginSearchWidths(String widths) {
        if (widths == null) {
            if (ROOT.remove("plugins.browse.widths") != null) save();
        } else if (!widths.equals(getPluginSearchWidths())) {
            putString("plugins.browse.widths", widths);
        }
    }

    /** Where an installed plugin came from: the repository's name as Browse lists it, and its group. */
    public record PluginOrigin(String repository, String groupId) {}

    /** Each plugin Browse installed, by artifact name: where to look for its updates (D-33). */
    public static java.util.Map<String, PluginOrigin> getPluginOrigins() {
        java.util.Map<String, PluginOrigin> out = new java.util.LinkedHashMap<>();
        JsonObject o = object(ROOT, "plugins.origins");
        if (o == null) return out;
        for (var e : o.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject v = e.getValue().getAsJsonObject();
            String repo = jsonString(v, "repository", null), group = jsonString(v, "groupId", null);
            if (repo != null && group != null) out.put(e.getKey(), new PluginOrigin(repo, group));
        }
        return out;
    }

    public static void setPluginOrigin(String artifactId, PluginOrigin origin) {
        JsonObject o = object(ROOT, "plugins.origins");
        if (o == null) {
            o = new JsonObject();
            ROOT.add("plugins.origins", o);
        }
        JsonObject v = new JsonObject();
        v.addProperty("repository", origin.repository());
        v.addProperty("groupId", origin.groupId());
        o.add(artifactId, v);
        save();
    }

    /** The newest version of each plugin the update notice has told about, by artifact name. */
    public static java.util.Map<String, String> getNotifiedPluginUpdates() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        JsonObject o = object(ROOT, "plugins.update.notified");
        if (o == null) return out;
        for (var e : o.entrySet())
            if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }

    public static void setNotifiedPluginUpdates(java.util.Map<String, String> notified) {
        JsonObject o = new JsonObject();
        notified.forEach(o::addProperty);
        ROOT.add("plugins.update.notified", o);
        save();
    }

    /** A manager dialog's size, by its id: {@link #getWindowSize}, for {@link ManagerDialogs#rememberSize}. */
    public static java.awt.Dimension getDialogSize(String id) { return getWindowSize(id); }

    public static void setDialogSize(String id, java.awt.Dimension size) { setWindowSize(id, size); }

    /** The Parser Editor's size and its divider (parser list | form); null before the first time. */
    public static int[] getParserEditorLayout() {
        String v = getString("parser.editor.layout", null);
        if (v == null) return null;
        try {
            int[] r = java.util.Arrays.stream(v.split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
            return r.length == 3 ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void setParserEditorLayout(int width, int height, int divider) {
        putString("parser.editor.layout", width + "," + height + "," + divider);
    }

    public static int getMaxEntriesPerTab() {
        return getInt(KEY_MAX_ENTRIES_PER_TAB, 200_000);
    }

    public static void setMaxEntriesPerTab(int value) {
        putInt(KEY_MAX_ENTRIES_PER_TAB, Math.max(10_000, Math.min(2_000_000, value)));
    }

    public static java.util.List<ColumnLayout> getColumnLayouts() {
        java.util.List<ColumnLayout> list = new ArrayList<>();
        for (JsonElement el : array(ROOT, KEY_COLUMN_LAYOUTS)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            java.util.List<ColumnLayout.ColumnEntry> entries = new ArrayList<>();
            for (JsonElement ce : array(o, "columns")) {
                if (!ce.isJsonObject()) continue;
                JsonObject co = ce.getAsJsonObject();
                entries.add(new ColumnLayout.ColumnEntry(
                        jsonInt(co, "modelIndex", 0),
                        bool(co, "visible", true),
                        jsonInt(co, "width", 100),
                        jsonString(co, "field", null)));
            }
            list.add(new ColumnLayout(jsonString(o, "name", ""), entries));
        }
        return list;
    }

    public static void saveColumnLayouts(java.util.List<ColumnLayout> layouts) {
        JsonArray arr = new JsonArray();
        for (ColumnLayout layout : layouts) {
            JsonObject o = new JsonObject();
            o.addProperty("name", layout.name());
            JsonArray cols = new JsonArray();
            for (ColumnLayout.ColumnEntry e : layout.columns()) {
                JsonObject co = new JsonObject();
                co.addProperty("modelIndex", e.modelIndex());
                co.addProperty("visible",    e.visible());
                co.addProperty("width",      e.width());
                if (e.field() != null) co.addProperty("field", e.field());
                cols.add(co);
            }
            o.add("columns", cols);
            arr.add(o);
        }
        ROOT.add(KEY_COLUMN_LAYOUTS, arr);
        save();
    }

    public static String getDefaultColumnLayout() {
        return getString(KEY_COLUMN_LAYOUT_DEFAULT, null);
    }

    public static void setDefaultColumnLayout(String name) {
        if (name == null) remove(KEY_COLUMN_LAYOUT_DEFAULT);
        else              putString(KEY_COLUMN_LAYOUT_DEFAULT, name);
    }

    public static String getTheme() {
        return ThemeManager.canonicalName(getString(KEY_THEME, ThemeManager.DEFAULT_THEME));
    }

    public static void setTheme(String name) {
        putString(KEY_THEME, name);
    }

    public static ZoneId getDisplayTimezone() {
        String id = getString(KEY_DISPLAY_TIMEZONE, null);
        if (id == null) return ZoneId.systemDefault();
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public static void setDisplayTimezone(ZoneId zone) {
        addRecentTimezone(zone);
        putString(KEY_DISPLAY_TIMEZONE, zone.getId());
    }

    /** Zones chosen lately, newest first: a timezone drop-down lists them near the top. */
    public static List<ZoneId> getRecentTimezones() {
        List<ZoneId> zones = new ArrayList<>();
        for (String id : getStringList("timezone.recent")) {
            try { zones.add(ZoneId.of(id)); } catch (Exception ignored) { }
        }
        return zones;
    }

    static void addRecentTimezone(ZoneId zone) {
        List<String> ids = new ArrayList<>(getStringList("timezone.recent"));
        ids.remove(zone.getId());
        ids.add(0, zone.getId());
        putStringList("timezone.recent", ids.subList(0, Math.min(8, ids.size())));
    }

    /** Returns the saved source timezone for a specific log file, or {@code null} if none saved. */
    public static ZoneId getSourceTimezone(Path file) {
        JsonObject map = object(ROOT, KEY_SOURCE_TIMEZONES);
        if (map == null) return null;
        String id = jsonString(map, file.toAbsolutePath().toString(), null);
        if (id == null) return null;
        try {
            return ZoneId.of(id);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setSourceTimezone(Path file, ZoneId zone) {
        addRecentTimezone(zone);
        if (object(ROOT, KEY_SOURCE_TIMEZONES) == null) ROOT.add(KEY_SOURCE_TIMEZONES, new JsonObject());
        object(ROOT, KEY_SOURCE_TIMEZONES).addProperty(file.toAbsolutePath().toString(), zone.getId());
        save();
    }

    public static void clearSourceTimezone(Path file) {
        JsonObject map = object(ROOT, KEY_SOURCE_TIMEZONES);
        if (map == null) return;
        map.remove(file.toAbsolutePath().toString());
        save();
    }

    public static boolean isTabCloseConfirm() {
        return getBool(KEY_TAB_CLOSE_CONFIRM, true);
    }

    public static void setTabCloseConfirm(boolean value) {
        putBool(KEY_TAB_CLOSE_CONFIRM, value);
    }

    public static boolean isHighlightClearAllConfirm() {
        return getBool(KEY_HIGHLIGHT_CLEAR_ALL_CONFIRM, true);
    }

    public static void setHighlightClearAllConfirm(boolean value) {
        putBool(KEY_HIGHLIGHT_CLEAR_ALL_CONFIRM, value);
    }

    public static boolean isAnnotationDeleteConfirm() {
        return getBool(KEY_ANNOTATION_DELETE_CONFIRM, true);
    }

    public static void setAnnotationDeleteConfirm(boolean value) {
        putBool(KEY_ANNOTATION_DELETE_CONFIRM, value);
    }

    public static int getDetailFontSize() {
        return getInt(KEY_DETAIL_FONT_SIZE, 12);
    }

    public static void setDetailFontSize(int size) {
        putInt(KEY_DETAIL_FONT_SIZE, size);
    }

    /** The share of a tab's height the log table takes over the detail pane, 0.55 at first. */
    public static double getDetailSplitRatio() {
        JsonElement e = ROOT.get(KEY_DETAIL_SPLIT);
        double r = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : DEFAULT_DETAIL_SPLIT;
        return clampSplitRatio(r);
    }

    public static void setDetailSplitRatio(double ratio) {
        ROOT.addProperty(KEY_DETAIL_SPLIT, Math.round(clampSplitRatio(ratio) * 1000) / 1000.0);
        save();
    }

    static final double DEFAULT_DETAIL_SPLIT = 0.55;

    /** Keeps both the table and the pane visible: a pane dragged shut doesn't open new tabs shut. */
    static double clampSplitRatio(double r) {
        return Double.isFinite(r) ? Math.max(0.15, Math.min(0.90, r)) : DEFAULT_DETAIL_SPLIT;
    }

    public static boolean isDetailWrapLines() {
        return getBool(KEY_DETAIL_WRAP_LINES, true);
    }

    public static void setDetailWrapLines(boolean wrap) {
        putBool(KEY_DETAIL_WRAP_LINES, wrap);
    }

    public static int getRowHeight() {
        return getInt(KEY_ROW_HEIGHT, 24);
    }

    /** Whether every other log row is shaded (Settings → Table). Off by default. */
    public static boolean isTableStripes() {
        return getBool(KEY_TABLE_STRIPES, false);
    }

    public static void setTableStripes(boolean stripes) {
        putBool(KEY_TABLE_STRIPES, stripes);
    }

    /** Whether the SSH dialog's "Save this connection" starts ticked: as it was last time. */
    public static boolean isSshSaveConnection() {
        return getBool(KEY_SSH_SAVE, false);
    }

    public static void setSshSaveConnection(boolean save) {
        putBool(KEY_SSH_SAVE, save);
    }

    public static void setRowHeight(int height) {
        putInt(KEY_ROW_HEIGHT, height);
    }

    public static java.io.File getLastOpenDir() {
        String path = getString(KEY_LAST_OPEN_DIR, null);
        if (path == null) return null;
        java.io.File dir = new java.io.File(path);
        return dir.isDirectory() ? dir : null;
    }

    public static void setLastOpenDir(java.io.File dir) {
        if (dir != null) putString(KEY_LAST_OPEN_DIR, dir.getAbsolutePath());
    }

    public static int getRecentFilesMax() {
        return getInt(KEY_RECENT_MAX, 5);
    }

    public static void setRecentFilesMax(int max) {
        putInt(KEY_RECENT_MAX, Math.max(1, Math.min(MAX_RECENT, max)));
    }

    /** Returns up to {@link #getRecentFilesMax()} recently opened files that still exist on disk. */
    public static List<Path> getRecentFiles() {
        return getStringList(KEY_RECENT_FILES).stream()
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .filter(Files::exists)
                .limit(getRecentFilesMax())
                .toList();
    }

    /** Inserts {@code path} at the front of the recent-files list, deduplicating and capping at {@value MAX_RECENT}. */
    public static void addRecentFile(Path path) {
        String abs = path.toAbsolutePath().toString();
        List<String> list = getStringList(KEY_RECENT_FILES);
        list.removeIf(s -> s.isBlank() || s.equals(abs));
        list.add(0, abs);
        if (list.size() > MAX_RECENT) list = list.subList(0, MAX_RECENT);
        putStringList(KEY_RECENT_FILES, list);
    }

    public static void removeRecentFile(Path path) {
        String abs = path.toAbsolutePath().toString();
        List<String> list = getStringList(KEY_RECENT_FILES);
        list.removeIf(s -> s.isBlank() || s.equals(abs));
        putStringList(KEY_RECENT_FILES, list);
    }

    public static void clearRecentFiles() {
        remove(KEY_RECENT_FILES);
    }

    /** Returns the saved favorite locations (directories or files) that still exist on disk. */
    public static List<Path> getFavorites() {
        return getStringList(KEY_FAVORITES).stream()
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .filter(Files::exists)
                .toList();
    }

    public static void saveFavorites(List<Path> favorites) {
        putStringList(KEY_FAVORITES, favorites.stream().map(Path::toString).toList());
    }

    /**
     * The SSH dialog's saved connections, each with the logs saved on it. Passwords and
     * passphrases are encrypted at rest via {@link CredentialStore}. The first time, connections
     * saved by earlier versions (one entry per log, names per host) are turned into these and
     * written back; the old entries are left as they were, unread from then on.
     */
    public static List<SshSaved.Connection> getSshConnections() {
        if (!ROOT.has(KEY_SSH_CONNECTIONS)) {
            if (!ROOT.has(KEY_SSH_FAVORITES)) return new ArrayList<>();
            List<SshSaved.Connection> converted = SshSaved.fromFavorites(oldSshFavorites(), oldSshHostNames());
            saveSshConnections(converted);
            return new ArrayList<>(converted);
        }
        List<SshSaved.Connection> list = new ArrayList<>();
        for (JsonElement el : array(ROOT, KEY_SSH_CONNECTIONS)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            List<SshSaved.Log> logs = new ArrayList<>();
            for (JsonElement le : array(o, "logs")) {
                if (!le.isJsonObject()) continue;
                JsonObject l = le.getAsJsonObject();
                logs.add(new SshSaved.Log(jsonString(l, "remotePath", ""), jsonString(l, "parserName", null)));
            }
            list.add(new SshSaved.Connection(
                    jsonString(o, "name", null),
                    jsonString(o, "host", ""),
                    jsonInt(o,    "port", 22),
                    jsonString(o, "user", ""),
                    jsonString(o, "authType", "password"),
                    CredentialStore.decrypt(jsonString(o, "password",      null)),
                    jsonString(o, "keyFilePath", null),
                    CredentialStore.decrypt(jsonString(o, "keyPassphrase", null)),
                    logs));
        }
        return list;
    }

    public static void saveSshConnections(List<SshSaved.Connection> connections) {
        JsonArray arr = new JsonArray();
        for (SshSaved.Connection c : connections) {
            JsonObject o = new JsonObject();
            if (c.name() != null) o.addProperty("name", c.name());
            o.addProperty("host",     c.host());
            o.addProperty("port",     c.port());
            o.addProperty("user",     c.user());
            o.addProperty("authType", c.authType());
            if (c.password()      != null) o.addProperty("password",      CredentialStore.encrypt(c.password()));
            if (c.keyFile()       != null) o.addProperty("keyFilePath",   c.keyFile());
            if (c.keyPassphrase() != null) o.addProperty("keyPassphrase", CredentialStore.encrypt(c.keyPassphrase()));
            JsonArray logs = new JsonArray();
            for (SshSaved.Log l : c.logs()) {
                JsonObject lo = new JsonObject();
                lo.addProperty("remotePath", l.remotePath());
                if (l.parserName() != null) lo.addProperty("parserName", l.parserName());
                logs.add(lo);
            }
            o.add("logs", logs);
            arr.add(o);
        }
        ROOT.add(KEY_SSH_CONNECTIONS, arr);
        save();
    }

    /** Saved logs as versions before 0.12 kept them: one entry each, with the whole sign-in. */
    private static List<SshSaved.OldFavorite> oldSshFavorites() {
        List<SshSaved.OldFavorite> list = new ArrayList<>();
        for (JsonElement el : array(ROOT, KEY_SSH_FAVORITES)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            list.add(new SshSaved.OldFavorite(
                    jsonString(o, "host", ""),
                    jsonInt(o,    "port", 22),
                    jsonString(o, "user", ""),
                    jsonString(o, "authType", "password"),
                    CredentialStore.decrypt(jsonString(o, "password",      null)),
                    jsonString(o, "keyFilePath", null),
                    CredentialStore.decrypt(jsonString(o, "keyPassphrase", null)),
                    jsonString(o, "remotePath", ""),
                    jsonString(o, "parserName", null)));
        }
        return list;
    }

    /** Server names as versions before 0.12 kept them, by host. */
    private static java.util.Map<String, String> oldSshHostNames() {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        JsonObject o = object(ROOT, "ssh.hostNames");
        if (o == null) return names;
        for (String host : o.keySet()) {
            String name = jsonString(o, host, null);
            if (name != null && !name.isBlank()) names.put(host, name);
        }
        return names;
    }

    public static List<PluginRepo> getPluginRepos() {
        List<PluginRepo> list = new ArrayList<>();
        for (JsonElement el : array(ROOT, KEY_PLUGIN_REPOS)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            list.add(new PluginRepo(
                    jsonString(o, "name",       ""),
                    jsonString(o, "type",       "nexus3"),
                    jsonString(o, "url",        ""),
                    jsonString(o, "repository", null),
                    jsonString(o, "authType",   "none"),
                    jsonString(o, "username",   null),
                    CredentialStore.decrypt(jsonString(o, "credential", null))
            ));
        }
        return list;
    }

    public static void savePluginRepos(List<PluginRepo> repos) {
        JsonArray arr = new JsonArray();
        for (PluginRepo r : repos) {
            JsonObject o = new JsonObject();
            o.addProperty("name",       r.name());
            o.addProperty("type",       r.type());
            o.addProperty("url",        r.url());
            if (r.repository()  != null) o.addProperty("repository",  r.repository());
            o.addProperty("authType",   r.authType());
            if (r.username()    != null) o.addProperty("username",    r.username());
            if (r.credential()  != null) o.addProperty("credential",  CredentialStore.encrypt(r.credential()));
            arr.add(o);
        }
        ROOT.add(KEY_PLUGIN_REPOS, arr);
        save();
    }

    /**
     * The values last used for a plugin source, keyed by setting key. {@code providerId}
     * identifies the provider (its class name). Values for {@code secretKeys} are decrypted.
     */
    public static java.util.Map<String, String> getSourceSettings(String providerId,
                                                                java.util.Set<String> secretKeys) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        JsonObject all = object(ROOT, KEY_SOURCE_SETTINGS);
        JsonObject o = all == null ? null : object(all, providerId);
        if (o == null) return values;
        for (String key : o.keySet()) {
            String stored = jsonString(o, key, null);
            String value = stored != null && secretKeys.contains(key) ? CredentialStore.decrypt(stored) : stored;
            if (value != null) values.put(key, value);   // a secret saved elsewhere reads as not saved
        }
        return values;
    }

    /**
     * Remembers the values used for a plugin source. Values whose keys are in
     * {@code secretKeys} are encrypted with {@link CredentialStore}.
     */
    public static void saveSourceSettings(String providerId, java.util.Map<String, String> values,
                                          java.util.Set<String> secretKeys) {
        JsonObject all = object(ROOT, KEY_SOURCE_SETTINGS) != null ? object(ROOT, KEY_SOURCE_SETTINGS) : new JsonObject();
        JsonObject o = new JsonObject();
        values.forEach((key, value) -> {
            String stored = secretKeys.contains(key) ? CredentialStore.encrypt(value) : value;
            if (stored != null) o.addProperty(key, stored);
        });
        all.add(providerId, o);
        ROOT.add(KEY_SOURCE_SETTINGS, all);
        save();
    }

    /** The string under {@code key}, or {@code def} if it's missing or not a string (or number). */
    static String jsonString(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && !e.getAsJsonPrimitive().isBoolean() ? e.getAsString() : def;
    }

    /** The whole number under {@code key}, or {@code def} if it's missing or not one. */
    static int jsonInt(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {    // 1.5, or too big for an int
            return def;
        }
    }
}
