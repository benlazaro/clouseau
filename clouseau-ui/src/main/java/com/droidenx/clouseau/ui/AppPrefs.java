package com.droidenx.clouseau.ui;

import com.droidenx.clouseau.core.SshSaved;
import com.droidenx.clouseau.ui.theme.ThemeManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        if (Files.exists(PREFS_FILE)) {
            try (Reader r = Files.newBufferedReader(PREFS_FILE)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el.isJsonObject()) return el.getAsJsonObject();
            } catch (IOException e) {
                log.warn("Could not read settings file {}", PREFS_FILE, e);
            }
        }
        return new JsonObject();
    }

    private static void save() {
        try {
            Files.createDirectories(PREFS_DIR);
            try (Writer w = Files.newBufferedWriter(PREFS_FILE)) {
                GSON.toJson(ROOT, w);
            }
        } catch (IOException e) {
            log.warn("Could not write settings file {}", PREFS_FILE, e);
        }
    }

    // ── Typed accessors ───────────────────────────────────────────────────────

    private static boolean getBool(String key, boolean def) {
        return ROOT.has(key) ? ROOT.get(key).getAsBoolean() : def;
    }

    private static void putBool(String key, boolean value) {
        ROOT.addProperty(key, value);
        save();
    }

    private static int getInt(String key, int def) {
        return ROOT.has(key) ? ROOT.get(key).getAsInt() : def;
    }

    private static void putInt(String key, int value) {
        ROOT.addProperty(key, value);
        save();
    }

    private static String getString(String key, String def) {
        return ROOT.has(key) ? ROOT.get(key).getAsString() : def;
    }

    private static void putString(String key, String value) {
        ROOT.addProperty(key, value);
        save();
    }

    private static List<String> getStringList(String key) {
        if (!ROOT.has(key)) return new ArrayList<>();
        JsonArray arr = ROOT.get(key).getAsJsonArray();
        List<String> list = new ArrayList<>(arr.size());
        arr.forEach(e -> list.add(e.getAsString()));
        return list;
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

    public static int getMaxEntriesPerTab() {
        return getInt(KEY_MAX_ENTRIES_PER_TAB, 200_000);
    }

    public static void setMaxEntriesPerTab(int value) {
        putInt(KEY_MAX_ENTRIES_PER_TAB, Math.max(10_000, Math.min(2_000_000, value)));
    }

    public static java.util.List<ColumnLayout> getColumnLayouts() {
        if (!ROOT.has(KEY_COLUMN_LAYOUTS)) return new ArrayList<>();
        JsonArray arr = ROOT.get(KEY_COLUMN_LAYOUTS).getAsJsonArray();
        java.util.List<ColumnLayout> list = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            java.util.List<ColumnLayout.ColumnEntry> entries = new ArrayList<>();
            if (o.has("columns")) {
                for (JsonElement ce : o.get("columns").getAsJsonArray()) {
                    JsonObject co = ce.getAsJsonObject();
                    entries.add(new ColumnLayout.ColumnEntry(
                            jsonInt(co, "modelIndex", 0),
                            !co.has("visible") || co.get("visible").getAsBoolean(),
                            jsonInt(co, "width", 100),
                            jsonString(co, "field", null)));
                }
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
        if (!ROOT.has(KEY_SOURCE_TIMEZONES)) return null;
        JsonObject map = ROOT.get(KEY_SOURCE_TIMEZONES).getAsJsonObject();
        String key = file.toAbsolutePath().toString();
        if (!map.has(key)) return null;
        try {
            return ZoneId.of(map.get(key).getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    public static void setSourceTimezone(Path file, ZoneId zone) {
        addRecentTimezone(zone);
        if (!ROOT.has(KEY_SOURCE_TIMEZONES)) ROOT.add(KEY_SOURCE_TIMEZONES, new JsonObject());
        ROOT.get(KEY_SOURCE_TIMEZONES).getAsJsonObject()
                .addProperty(file.toAbsolutePath().toString(), zone.getId());
        save();
    }

    public static void clearSourceTimezone(Path file) {
        if (!ROOT.has(KEY_SOURCE_TIMEZONES)) return;
        ROOT.get(KEY_SOURCE_TIMEZONES).getAsJsonObject()
                .remove(file.toAbsolutePath().toString());
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
        double r = ROOT.has(KEY_DETAIL_SPLIT) ? ROOT.get(KEY_DETAIL_SPLIT).getAsDouble() : DEFAULT_DETAIL_SPLIT;
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
        for (JsonElement el : ROOT.get(KEY_SSH_CONNECTIONS).getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            List<SshSaved.Log> logs = new ArrayList<>();
            if (o.has("logs")) {
                for (JsonElement le : o.getAsJsonArray("logs")) {
                    JsonObject l = le.getAsJsonObject();
                    logs.add(new SshSaved.Log(jsonString(l, "remotePath", ""), jsonString(l, "parserName", null)));
                }
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
        for (JsonElement el : ROOT.get(KEY_SSH_FAVORITES).getAsJsonArray()) {
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
        if (!ROOT.has("ssh.hostNames")) return names;
        JsonObject o = ROOT.getAsJsonObject("ssh.hostNames");
        for (String host : o.keySet()) {
            String name = jsonString(o, host, null);
            if (name != null && !name.isBlank()) names.put(host, name);
        }
        return names;
    }

    public static List<PluginRepo> getPluginRepos() {
        if (!ROOT.has(KEY_PLUGIN_REPOS)) return new ArrayList<>();
        JsonArray arr = ROOT.get(KEY_PLUGIN_REPOS).getAsJsonArray();
        List<PluginRepo> list = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
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
        if (!ROOT.has(KEY_SOURCE_SETTINGS)) return values;
        JsonObject all = ROOT.getAsJsonObject(KEY_SOURCE_SETTINGS);
        if (!all.has(providerId)) return values;
        JsonObject o = all.getAsJsonObject(providerId);
        for (String key : o.keySet()) {
            String stored = jsonString(o, key, null);
            if (stored != null) values.put(key, secretKeys.contains(key) ? CredentialStore.decrypt(stored) : stored);
        }
        return values;
    }

    /**
     * Remembers the values used for a plugin source. Values whose keys are in
     * {@code secretKeys} are encrypted with {@link CredentialStore}.
     */
    public static void saveSourceSettings(String providerId, java.util.Map<String, String> values,
                                          java.util.Set<String> secretKeys) {
        JsonObject all = ROOT.has(KEY_SOURCE_SETTINGS) ? ROOT.getAsJsonObject(KEY_SOURCE_SETTINGS) : new JsonObject();
        JsonObject o = new JsonObject();
        values.forEach((key, value) ->
                o.addProperty(key, secretKeys.contains(key) ? CredentialStore.encrypt(value) : value));
        all.add(providerId, o);
        ROOT.add(KEY_SOURCE_SETTINGS, all);
        save();
    }

    private static String jsonString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int jsonInt(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }
}
