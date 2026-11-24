package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.LogEntry.LogLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes the auto-highlight rules (D-29), {@code <data>/highlight-rules.json}:
 *
 * <pre>{"version": 1, "rules": [{"enabled": true, "in": "message", "text": "timeout",
 *   "matchCase": false, "regex": false, "minLevel": "WARN", "color": 0}]}</pre>
 *
 * {@code in} is {@code any}, {@code message}, {@code logger}, {@code thread} or
 * {@code field} (with {@code field} naming it); {@code minLevel} is omitted for any level.
 * Unknown fields are ignored, and a rule that can't be read is skipped. A file that can't be
 * read at all is moved to {@code highlight-rules.json.bad}, so the next save doesn't lose it.
 */
@Slf4j
public final class HighlightRuleStore {

    public static final String FILE_NAME = "highlight-rules.json";
    public static final int VERSION = 1;
    private static final int COLORS = 8;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private HighlightRuleStore() {}

    /** The rules in {@code file}; none if it doesn't exist or can't be read. */
    public static List<HighlightRule> read(Path file) {
        if (!Files.exists(file)) return List.of();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read highlight rules {}; setting it aside", file, e);
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                log.warn("Could not set aside {}", file, moveFailed);
            }
            return List.of();
        }
    }

    /** The rules in a file's text. Throws if it isn't a rules file at all. */
    static List<HighlightRule> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int version = root.has("version") ? root.get("version").getAsInt() : VERSION;
        if (version > VERSION) log.warn("Highlight rules are version {}; reading as {}", version, VERSION);
        List<HighlightRule> rules = new ArrayList<>();
        JsonArray array = root.has("rules") ? root.getAsJsonArray("rules") : new JsonArray();
        for (JsonElement el : array) {
            try {
                rules.add(fromJson(el.getAsJsonObject()));
            } catch (RuntimeException e) {
                log.warn("Skipping a highlight rule that can't be read: {}", el, e);
            }
        }
        return List.copyOf(rules);
    }

    private static HighlightRule fromJson(JsonObject o) {
        HighlightRule.Column column = HighlightRule.Column.ANY;
        if (o.has("in")) column = HighlightRule.Column.valueOf(o.get("in").getAsString().toUpperCase(Locale.ROOT));
        String field = o.has("field") && !o.get("field").isJsonNull() ? o.get("field").getAsString() : null;
        String text = o.has("text") && !o.get("text").isJsonNull() ? o.get("text").getAsString() : "";
        LogLevel min = o.has("minLevel") && !o.get("minLevel").isJsonNull()
                ? LogLevel.valueOf(o.get("minLevel").getAsString().toUpperCase(Locale.ROOT)) : null;
        int color = o.get("color").getAsInt();
        if (color < 0 || color >= COLORS) throw new IllegalArgumentException("color " + color);
        return new HighlightRule(!o.has("enabled") || o.get("enabled").getAsBoolean(), column, field, text,
                o.has("matchCase") && o.get("matchCase").getAsBoolean(),
                o.has("regex") && o.get("regex").getAsBoolean(), min, color);
    }

    /** The file's text for {@code rules}. */
    static String toJson(List<HighlightRule> rules) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray array = new JsonArray();
        for (HighlightRule r : rules) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", r.enabled());
            o.addProperty("in", r.column().name().toLowerCase(Locale.ROOT));
            if (r.field() != null) o.addProperty("field", r.field());
            o.addProperty("text", r.text());
            o.addProperty("matchCase", r.matchCase());
            o.addProperty("regex", r.regex());
            if (r.minLevel() != null) o.addProperty("minLevel", r.minLevel().name());
            o.addProperty("color", r.color());
            array.add(o);
        }
        root.add("rules", array);
        return GSON.toJson(root);
    }

    /** Writes {@code rules} to {@code file}, through a temporary file moved into place. */
    public static void write(Path file, List<HighlightRule> rules) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(rules), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
