package com.droidenx.clouseau.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.droidenx.clouseau.api.LogParser;
import com.droidenx.clouseau.core.RegexLogParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads log parsers from two sources, in the order auto-detection tries them:
 * <ol>
 *   <li>{@code parsers.json} in the data dir — the user's own, in their order; loaded if present.</li>
 *   <li>Bundled {@code default-parsers.json} (classpath resource) — always loaded, except one
 *       the user replaced with a parser of the same name.</li>
 * </ol>
 *
 * <p>Each entry supports:
 * <pre>{@code
 * {
 *   "name": "My App",
 *   "pattern": "(?<timestamp>...) (?<level>...) (?<logger>...) - (?<message>...)",
 *   "timestampFormat":  "yyyy-MM-dd HH:mm:ss",          // single format
 *   "timestampFormats": ["yyyy-MM-dd HH:mm:ss.SSS", ...]  // or multiple, tried in order
 * }
 * }</pre>
 *
 * Invalid entries are skipped with a warning; a missing user file is silently ignored.
 */
@Slf4j
public final class UserParsersLoader {

    private static final String DEFAULT_RESOURCE = "com/droidenx/clouseau/ui/default-parsers.json";
    private static final Path   USER_FILE = AppDirs.dataDir().resolve("parsers.json");

    private UserParsersLoader() {}

    public static List<LogParser> load() {
        // The user's parsers come first, in the order they set in the Parser Editor:
        // auto-detection takes the first parser that reads a line, and theirs are the more
        // specific. A user parser named like a bundled one replaces it.
        Map<String, LogParser> byName = new LinkedHashMap<>();

        // 1. User file
        if (Files.exists(USER_FILE)) {
            try (Reader r = Files.newBufferedReader(USER_FILE)) {
                parseArray(r, USER_FILE.toString()).forEach(p -> byName.putIfAbsent(p.getName(), p));
            } catch (IOException e) {
                log.warn("Could not read {}", USER_FILE, e);
            }
        }

        // 2. Bundled defaults, after the user's; one the user replaced is left out
        try (InputStream is = UserParsersLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (is != null) {
                parseArray(new InputStreamReader(is, StandardCharsets.UTF_8), "default-parsers.json")
                        .forEach(p -> {
                            if (byName.containsKey(p.getName())) {
                                log.info("User parser '{}' overrides bundled default", p.getName());
                            } else {
                                byName.put(p.getName(), p);
                            }
                        });
            } else {
                log.warn("Bundled default-parsers.json not found on classpath");
            }
        } catch (IOException e) {
            log.warn("Could not read bundled default-parsers.json", e);
        }

        List<LogParser> result = new ArrayList<>(byName.values());

        if (result.isEmpty()) {
            log.warn("No parsers loaded — open {} to add custom parsers", USER_FILE);
        } else {
            log.info("Loaded {} parser(s): {}", result.size(),
                    result.stream().map(LogParser::getName).toList());
        }
        return List.copyOf(result);
    }

    private static List<LogParser> parseArray(Reader reader, String source) {
        List<LogParser> result = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                log.warn("{} must be a JSON array — skipping", source);
                return result;
            }
            JsonArray arr = root.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                LogParser p = parseEntry(arr.get(i), i, source);
                if (p != null) result.add(p);
            }
        } catch (Exception e) {
            log.warn("Could not parse {}", source, e);
        }
        return result;
    }

    private static LogParser parseEntry(JsonElement el, int index, String source) {
        if (!el.isJsonObject()) {
            log.warn("{} entry [{}] is not an object — skipping", source, index);
            return null;
        }
        JsonObject obj = el.getAsJsonObject();

        String name = getString(obj, "name");
        if (name == null || name.isBlank()) {
            log.warn("{} entry [{}] missing 'name' — skipping", source, index);
            return null;
        }

        String patternStr = getString(obj, "pattern");
        if (patternStr == null || patternStr.isBlank()) {
            log.warn("{} entry [{}] ('{}') missing 'pattern' — skipping", source, index, name);
            return null;
        }

        Pattern compiled;
        try {
            compiled = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            log.warn("{} entry [{}] ('{}') invalid regex: {} — skipping", source, index, name, e.getMessage());
            return null;
        }

        String timestampFormat = getString(obj, "timestampFormat");
        if (timestampFormat == null || timestampFormat.isBlank()) {
            log.warn("{} entry [{}] ('{}') missing 'timestampFormat' — skipping", source, index, name);
            return null;
        }

        return new RegexLogParser(name, compiled, timestampFormat);
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }
}
