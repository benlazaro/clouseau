package com.droidenx.clouseau.ui;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent user preferences stored as {@code ~/.clouseau/settings.json}.
 */
@Slf4j
public final class AppPrefs {

    public static final Path PREFS_DIR  = Path.of(System.getProperty("user.home"), ".clouseau");
    private static final Path PREFS_FILE = PREFS_DIR.resolve("settings.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String KEY_TAB_CLOSE_CONFIRM            = "tab.close.confirm";
    private static final String KEY_HIGHLIGHT_CLEAR_ALL_CONFIRM  = "highlight.clear.all.confirm";
    private static final String KEY_ANNOTATION_DELETE_CONFIRM    = "annotation.delete.confirm";
    private static final String KEY_FOLLOW_BY_DEFAULT  = "follow.by.default";
    private static final String KEY_DETAIL_FONT_SIZE   = "detail.font.size";
    private static final String KEY_DETAIL_WRAP_LINES  = "detail.wrap.lines";
    private static final String KEY_ROW_HEIGHT         = "row.height";
    private static final String KEY_LAST_OPEN_DIR      = "last.open.dir";
    private static final String KEY_RECENT_FILES       = "recent.files";
    private static final String KEY_RECENT_MAX         = "recent.max";
    private static final String KEY_FAVORITES          = "favorites";
    private static final String KEY_SSH_FAVORITES      = "ssh.favorites";
    private static final String KEY_PLUGIN_REPOS         = "plugin.repos";
    private static final String KEY_MAX_ENTRIES_PER_TAB  = "max.entries.per.tab";
    private static final String KEY_COLUMN_LAYOUTS        = "column.layouts";
    private static final String KEY_COLUMN_LAYOUT_DEFAULT = "column.layout.default";
    private static final String KEY_THEME                 = "theme";
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
     */
    public record ColumnLayout(String name, java.util.List<ColumnEntry> columns) {
        public record ColumnEntry(int modelIndex, boolean visible, int width) {}
    }

    /**
     * A saved SSH connection. Passwords and passphrases are encrypted at rest
     * via {@link CredentialStore}.
     */
    public record SshFavorite(
            String name,
            String host,
            int    port,
            String user,
            String authType,      // "password" or "key"
            String password,      // null when using key auth
            String keyFilePath,   // null when using password auth
            String keyPassphrase, // may be blank
            String remotePath,
            String parserName     // null = auto-detect
    ) {}

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
                            jsonInt(co, "width", 100)));
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
        return getString(KEY_THEME, "Clouseau Dark");
    }

    public static void setTheme(String name) {
        putString(KEY_THEME, name);
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

    public static boolean isDetailWrapLines() {
        return getBool(KEY_DETAIL_WRAP_LINES, true);
    }

    public static void setDetailWrapLines(boolean wrap) {
        putBool(KEY_DETAIL_WRAP_LINES, wrap);
    }

    public static int getRowHeight() {
        return getInt(KEY_ROW_HEIGHT, 22);
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

    public static List<SshFavorite> getSshFavorites() {
        if (!ROOT.has(KEY_SSH_FAVORITES)) return new ArrayList<>();
        JsonArray arr = ROOT.get(KEY_SSH_FAVORITES).getAsJsonArray();
        List<SshFavorite> list = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            list.add(new SshFavorite(
                    jsonString(o, "name", ""),
                    jsonString(o, "host", ""),
                    jsonInt(o,    "port", 22),
                    jsonString(o, "user", ""),
                    jsonString(o, "authType", "password"),
                    CredentialStore.decrypt(jsonString(o, "password",      null)),
                    jsonString(o, "keyFilePath", null),
                    CredentialStore.decrypt(jsonString(o, "keyPassphrase", null)),
                    jsonString(o, "remotePath", ""),
                    jsonString(o, "parserName", null)
            ));
        }
        return list;
    }

    public static void saveSshFavorites(List<SshFavorite> favorites) {
        JsonArray arr = new JsonArray();
        for (SshFavorite f : favorites) {
            JsonObject o = new JsonObject();
            o.addProperty("name",       f.name());
            o.addProperty("host",       f.host());
            o.addProperty("port",       f.port());
            o.addProperty("user",       f.user());
            o.addProperty("authType",   f.authType());
            if (f.password()      != null) o.addProperty("password",      CredentialStore.encrypt(f.password()));
            if (f.keyFilePath()   != null) o.addProperty("keyFilePath",   f.keyFilePath());
            if (f.keyPassphrase() != null) o.addProperty("keyPassphrase", CredentialStore.encrypt(f.keyPassphrase()));
            o.addProperty("remotePath", f.remotePath());
            if (f.parserName()    != null) o.addProperty("parserName",    f.parserName());
            arr.add(o);
        }
        ROOT.add(KEY_SSH_FAVORITES, arr);
        save();
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

    private static String jsonString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int jsonInt(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }
}
