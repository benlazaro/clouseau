package com.droidenx.clouseau.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which artifacts are Clouseau plugins (G-15, D-32): the naming convention, the plugin list
 * ({@code plugins.json} in the project's repository) that Browse shows for Maven Central, and
 * reading a plugin's latest version from Maven's {@code maven-metadata.xml}.
 * <p>
 * The convention follows Maven's own for its plugins: {@code clouseau-<name>-plugin} is kept for
 * plugins this project publishes (group {@code com.droidenx.clouseau}), and everyone else names
 * theirs {@code <name>-clouseau-plugin}, under their own group.
 * <p>
 * Parsing only, no I/O: the repositories fetch the files.
 */
@Slf4j
public final class PluginCatalog {

    /** The plugin list, read from the project's default branch. */
    public static final String CATALOG_URL = "https://raw.githubusercontent.com/benlazaro/clouseau/main/plugins.json";
    /** The group of the plugins this project publishes. */
    public static final String OFFICIAL_GROUP = "com.droidenx.clouseau";

    private static final Pattern THIRD_PARTY = Pattern.compile("[a-z0-9][a-z0-9._-]*-clouseau-plugin");
    private static final Pattern OFFICIAL    = Pattern.compile("clouseau-[a-z0-9][a-z0-9._-]*-plugin");

    private PluginCatalog() {}

    /** What a plugin adds to Clouseau, as the list names it ({@code "parser"}, {@code "source"}, …). */
    public enum Kind {
        PARSER, FORMATTER, HIGHLIGHTER, SOURCE;

        /** The kind {@code text} names, or null. */
        static Kind of(String text) {
            if (text == null) return null;
            for (Kind k : values()) if (k.name().equalsIgnoreCase(text.strip())) return k;
            return null;
        }
    }

    /**
     * One plugin on the list.
     *
     * @param name        what to call it; the artifact name when the list gives none
     * @param description one line, or empty
     * @param provides    what it adds, in the list's order; may be empty
     * @param homepage    where to read about it, or null
     */
    public record Entry(String groupId, String artifactId, String name, String description,
                        List<Kind> provides, String homepage) {
        public Entry {
            provides = List.copyOf(provides);
        }

        public boolean official() { return isOfficial(groupId, artifactId); }
    }

    /** True if {@code artifactId} follows the convention: {@code <name>-clouseau-plugin} or {@code clouseau-<name>-plugin}. */
    public static boolean isPluginName(String artifactId) {
        if (artifactId == null) return false;
        String a = artifactId.strip().toLowerCase(Locale.ROOT);
        return THIRD_PARTY.matcher(a).matches() || OFFICIAL.matcher(a).matches();
    }

    /** True for a plugin this project publishes: its group, named {@code clouseau-<name>-plugin}. */
    public static boolean isOfficial(String groupId, String artifactId) {
        return OFFICIAL_GROUP.equals(groupId) && artifactId != null
                && OFFICIAL.matcher(artifactId.strip().toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * The plugins in a {@code plugins.json}. An entry without a group or an artifact name, or whose
     * name doesn't follow the convention, is left out (and logged), as is a {@code clouseau-*-plugin}
     * outside this project's group, so one bad entry can't hide the rest.
     *
     * @throws IllegalArgumentException if {@code json} isn't a plugin list at all
     */
    public static List<Entry> parse(String json) {
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(json == null ? "" : json);
            if (!e.isJsonObject()) throw new IllegalArgumentException("The plugin list isn't a JSON object");
            root = e.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("The plugin list isn't valid JSON: " + e.getMessage(), e);
        }
        if (!root.has("plugins") || !root.get("plugins").isJsonArray())
            throw new IllegalArgumentException("The plugin list has no \"plugins\" array");

        List<Entry> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("plugins")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String group = string(o, "groupId"), artifact = string(o, "artifactId");
            if (group.isEmpty() || artifact.isEmpty()) {
                log.warn("Plugin list: skipping an entry without groupId and artifactId: {}", o);
                continue;
            }
            if (!isPluginName(artifact)) {
                log.warn("Plugin list: skipping {}:{}, whose name isn't <name>-clouseau-plugin", group, artifact);
                continue;
            }
            if (OFFICIAL.matcher(artifact.toLowerCase(Locale.ROOT)).matches() && !OFFICIAL_GROUP.equals(group)) {
                log.warn("Plugin list: skipping {}:{}; clouseau-<name>-plugin is kept for {}", group, artifact, OFFICIAL_GROUP);
                continue;
            }
            List<Kind> provides = new ArrayList<>();
            if (o.has("provides") && o.get("provides").isJsonArray()) {
                for (JsonElement k : o.getAsJsonArray("provides")) {
                    Kind kind = k.isJsonPrimitive() ? Kind.of(k.getAsString()) : null;
                    if (kind != null && !provides.contains(kind)) provides.add(kind);
                }
            }
            String name = string(o, "name");
            String homepage = string(o, "homepage");
            out.add(new Entry(group, artifact, name.isEmpty() ? artifact : name, string(o, "description"),
                    provides, homepage.isEmpty() ? null : homepage));
        }
        return out;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString().strip() : "";
    }

    private static final Pattern RELEASE = Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>");
    private static final Pattern LATEST  = Pattern.compile("<latest>\\s*([^<\\s]+)\\s*</latest>");
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

    /**
     * The version to offer from an artifact's {@code maven-metadata.xml}: its {@code <release>},
     * else {@code <latest>}, else the last version listed; null if it lists none.
     */
    public static String latestVersion(String metadataXml) {
        if (metadataXml == null) return null;
        for (Pattern p : new Pattern[]{ RELEASE, LATEST }) {
            Matcher m = p.matcher(metadataXml);
            if (m.find()) return m.group(1);
        }
        Matcher m = VERSION.matcher(metadataXml);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    /**
     * The versions of {@code artifactId} among {@code fileNames} (the plugins folder's files), from
     * names like {@code logfmt-clouseau-plugin-1.2.0.jar}, which is how Browse saves them.
     */
    public static List<String> installedVersions(String artifactId, Collection<String> fileNames) {
        if (artifactId == null || artifactId.isBlank()) return List.of();
        Pattern p = Pattern.compile(Pattern.quote(artifactId.strip()) + "-(\\d[^/\\\\]*?)\\.(?:jar|zip)",
                Pattern.CASE_INSENSITIVE);
        List<String> out = new ArrayList<>();
        for (String f : fileNames) {
            Matcher m = p.matcher(f);
            if (m.matches()) out.add(m.group(1));
        }
        return out;
    }
}
