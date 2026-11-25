package com.droidenx.clouseau.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** settings.json as a file: damaged, cut short, from a newer Clouseau (DESIGN.md 7). */
class AppPrefsFileTest {

    @TempDir Path tmp;

    @Test
    void aMissingFileIsNoSettings() {
        assertThat(AppPrefs.load(tmp.resolve("settings.json")).size()).isZero();
        assertThat(tmp.resolve("settings.json.bad")).doesNotExist();
    }

    @Test
    void aDamagedFileIsMovedAsideNotOverwritten() throws Exception {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "{\"theme\": \"Dark\", \"recent.files\": [\"a.log\"");   // cut short

        JsonObject settings = AppPrefs.load(file);

        assertThat(settings.size()).isZero();
        assertThat(file).doesNotExist();
        assertThat(tmp.resolve("settings.json.bad")).hasContent("{\"theme\": \"Dark\", \"recent.files\": [\"a.log\"");
    }

    @Test
    void aFileSavedWithABomStillLoads() throws Exception {
        Path file = tmp.resolve("settings.json");
        byte[] json = "{\"theme\": \"Dark\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);
        Files.write(file, withBom);

        assertThat(AppPrefs.load(file).get("theme").getAsString()).isEqualTo("Dark");
        assertThat(tmp.resolve("settings.json.bad")).doesNotExist();
    }

    @Test
    void aFileThatIsntAnObjectIsMovedAside() throws Exception {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "[1, 2]");

        assertThat(AppPrefs.load(file).size()).isZero();
        assertThat(tmp.resolve("settings.json.bad")).exists();
    }

    @Test
    void writingKeepsKeysThisVersionDoesntKnow() throws Exception {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "{\"theme\": \"Dark\", \"from.a.newer.version\": {\"x\": 1}}");

        JsonObject settings = AppPrefs.load(file);
        settings.addProperty("theme", "Light");
        AppPrefs.write(file, settings);

        JsonObject reread = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertThat(reread.get("theme").getAsString()).isEqualTo("Light");
        assertThat(reread.getAsJsonObject("from.a.newer.version").get("x").getAsInt()).isEqualTo(1);
        assertThat(tmp.resolve("settings.json.tmp")).doesNotExist();
    }

    @Test
    void writingCreatesTheFolder() throws Exception {
        Path file = tmp.resolve("new").resolve("settings.json");
        JsonObject settings = new JsonObject();
        settings.addProperty("theme", "Dark");

        AppPrefs.write(file, settings);

        assertThat(AppPrefs.load(file).get("theme").getAsString()).isEqualTo("Dark");
    }

    @Test
    void aValueOfTheWrongTypeReadsAsItsDefault() {
        JsonObject o = JsonParser.parseString("""
                {"flag": "yes", "count": "many", "name": {"nested": true}, "list": "not a list",
                 "mixed": ["a", 1, null, {"b": 2}, "c"], "nothing": null, "ok": true, "n": 7}
                """).getAsJsonObject();

        assertThat(AppPrefs.bool(o, "flag", true)).isTrue();
        assertThat(AppPrefs.bool(o, "nothing", false)).isFalse();
        assertThat(AppPrefs.bool(o, "ok", false)).isTrue();
        assertThat(AppPrefs.jsonInt(o, "count", 5)).isEqualTo(5);
        assertThat(AppPrefs.jsonInt(o, "n", 5)).isEqualTo(7);
        assertThat(AppPrefs.jsonString(o, "name", "def")).isEqualTo("def");
        assertThat(AppPrefs.jsonString(o, "nothing", "def")).isEqualTo("def");
        assertThat(AppPrefs.stringList(o, "list")).isEmpty();
        assertThat(AppPrefs.stringList(o, "mixed")).containsExactly("a", "c");
        assertThat(AppPrefs.array(o, "flag")).isEmpty();
        assertThat(AppPrefs.object(o, "list")).isNull();
    }
}
