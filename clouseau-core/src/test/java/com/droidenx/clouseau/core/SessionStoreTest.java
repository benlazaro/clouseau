package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    private static Session sample() {
        Session.Tab file = Session.Tab.file("C:/logs/api.log", null, "Log4j Pattern", "UTC")
                .withState(true,
                        new Session.Layout(List.of(new Session.Column(0, true, 60), new Session.Column(3, false, 120))),
                        new Session.Filters(List.of("ERROR", "WARN"), List.of("org.hibernate"),
                                Map.of("pid", List.of("42")), "10:02", "", "timeout", List.of()));
        Session.Tab merged = Session.Tab.merged(List.of(
                new Session.MergedFile("C:/logs/a.log", null, null, "UTC", 0),
                new Session.MergedFile("C:/logs/b.zip", "b.log", "Spring Boot", null, 192_000)), null);
        Session.Tab ssh = Session.Tab.ssh(
                new Session.Ssh("db01", 22, "ops", "key", "C:/k/id", false, "/var/log/db.log"), null, null);
        Session.Tab source = Session.Tab.source(
                new Session.PluginSource("com.example.Demo", "Demo", Map.of("url", "http://x")), null, null);
        return new Session("Payments", List.of(
                new Session.Window(new Session.Bounds(1920, 0, 1600, 1000), "\\\\.\\DISPLAY2", true, 1,
                        List.of(file, merged)),
                new Session.Window(new Session.Bounds(10, 10, 800, 600), null, false, 0, List.of(ssh, source))));
    }

    @Test
    void writesAndReadsBackEveryKindOfTab(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        SessionStore.write(file, sample(), null, "0.2.0");

        SessionStore.ReadResult result = SessionStore.read(file);

        assertThat(result).isInstanceOf(SessionStore.ReadResult.Read.class);
        Session back = ((SessionStore.ReadResult.Read) result).session();
        assertThat(back).isEqualTo(sample());
        assertThat(SessionStore.toJson(back)).isEqualTo(SessionStore.toJson(sample()));
        assertThat(Files.readString(file)).contains("\"format\":\"clouseau-session\"", "\"formatVersion\":1")
                .doesNotContain("password");
        assertThat(dir.resolve("session.json.tmp")).doesNotExist();
    }

    @Test
    void theSameContentGivesTheSameJson() {
        assertThat(SessionStore.toJson(sample())).isEqualTo(SessionStore.toJson(sample()));
        assertThat(SessionStore.toJson(sample().withActiveSession(null))).isNotEqualTo(SessionStore.toJson(sample()));
    }

    @Test
    void aMissingFileIsMissing(@TempDir Path dir) {
        assertThat(SessionStore.read(dir.resolve("none.json"))).isInstanceOf(SessionStore.ReadResult.Missing.class);
    }

    @Test
    void somethingElseIsDamagedAndCanBeSetAside(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("session.json"), "{ not json");
        assertThat(SessionStore.read(file)).isInstanceOf(SessionStore.ReadResult.Damaged.class);
        Files.writeString(file, "{\"format\":\"something-else\"}");
        assertThat(SessionStore.read(file)).isInstanceOf(SessionStore.ReadResult.Damaged.class);

        SessionStore.setAside(file);
        assertThat(file).doesNotExist();
        assertThat(dir.resolve("session.json.bad")).exists();
    }

    @Test
    void unknownFieldsAndNewerVersionsAreRead() {
        String json = """
                {"format":"clouseau-session","formatVersion":9,"futureThing":{"x":1},
                 "windows":[{"bounds":{"x":1,"y":2,"width":3,"height":4},"selectedTab":0,"futureToo":true,
                   "tabs":[{"kind":"FILE","path":"a.log","follow":false,"newField":1}]}]}
                """;
        SessionStore.ReadResult result = SessionStore.parse(json);
        assertThat(result).isInstanceOf(SessionStore.ReadResult.Read.class);
        Session s = ((SessionStore.ReadResult.Read) result).session();
        assertThat(s.windows()).hasSize(1);
        assertThat(s.windows().get(0).tabs().get(0).path()).isEqualTo("a.log");
    }

    @Test
    void tabsMissingWhatTheyNeedAreDropped() {
        String json = """
                {"format":"clouseau-session","windows":[{"selectedTab":2,"tabs":[
                  {"kind":"FILE"},{"kind":"WORMHOLE","path":"x"},{"kind":"MERGED","files":[]},
                  {"kind":"FILE","path":"ok.log"}]}]}
                """;
        Session s = ((SessionStore.ReadResult.Read) SessionStore.parse(json)).session();
        assertThat(s.windows().get(0).tabs()).extracting(Session.Tab::path).containsExactly("ok.log");
        assertThat(s.windows().get(0).selectedTab()).isZero();
    }

    @Test
    void namedSessionsAreSavedListedRenamedAndDeleted(@TempDir Path dir) throws IOException {
        SessionStore.save(dir, "Payments", sample(), "0.2.0");
        SessionStore.save(dir, "a/b: c?", sample(), "0.2.0");
        SessionStore.save(dir, "payments", sample().withActiveSession(null), "0.2.0");  // same name, replaced

        assertThat(SessionStore.list(dir)).extracting(SessionStore.Named::name).containsExactly("a/b: c?", "payments");   // the newer spelling wins
        Session saved = ((SessionStore.ReadResult.Read) SessionStore.read(SessionStore.find(dir, "PAYMENTS").get().file())).session();
        assertThat(saved.activeSession()).isNull();   // a named session never names itself active

        SessionStore.rename(dir, "Payments", "Billing");
        assertThat(SessionStore.list(dir)).extracting(SessionStore.Named::name).containsExactly("a/b: c?", "Billing");

        SessionStore.delete(dir, "billing");
        assertThat(SessionStore.list(dir)).extracting(SessionStore.Named::name).containsExactly("a/b: c?");
    }

    @Test
    void fileNamesAreSafe() {
        assertThat(SessionStore.fileName("a/b: c?")).isEqualTo("a_b_ c_");
        assertThat(SessionStore.fileName(" ..x.. ")).isEqualTo("x");
        assertThat(SessionStore.fileName("CON")).isEqualTo("session_CON");
        assertThat(SessionStore.fileName("...")).isEqualTo("session_");
    }

    @Test
    void namesThatCleanToTheSameFileGetTheirOwn(@TempDir Path dir) throws IOException {
        SessionStore.save(dir, "a/b", sample(), "v");
        SessionStore.save(dir, "a:b", sample(), "v");
        assertThat(SessionStore.list(dir)).hasSize(2);
    }
}
