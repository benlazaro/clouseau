package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SshSavedTest {

    private static SshSaved.OldFavorite fav(String user, String host, int port, String auth, String pw, String path) {
        return new SshSaved.OldFavorite(host, port, user, auth, pw, null, null, path, null);
    }

    @Test
    void logsWithTheSameUserHostAndPortBecomeOneConnection() {
        List<SshSaved.Connection> cs = SshSaved.fromFavorites(List.of(
                fav("deploy", "10.0.3.17", 22, "password", "s3cret", "/var/log/app.log"),
                fav("deploy", "10.0.3.17", 22, "password", "s3cret", "/var/log/audit.log"),
                fav("deploy", "10.0.3.17", 2222, "password", "other", "/var/log/app.log")), Map.of());

        assertThat(cs).hasSize(2);
        assertThat(cs.get(0).logs()).extracting(SshSaved.Log::remotePath)
                .containsExactly("/var/log/app.log", "/var/log/audit.log");
        assertThat(cs.get(0).password()).isEqualTo("s3cret");
        assertThat(cs.get(1).address()).isEqualTo("deploy@10.0.3.17:2222");
        assertThat(cs.get(0).address()).isEqualTo("deploy@10.0.3.17");
    }

    @Test
    void theFirstLogDecidesHowAConnectionSignsInAndAPathIsKeptOnce() {
        List<SshSaved.Connection> cs = SshSaved.fromFavorites(List.of(
                fav("ops", "db01", 22, "key", null, "/var/log/pg.log"),
                fav("ops", "db01", 22, "password", "pw", "/var/log/pg.log")), Map.of());

        assertThat(cs).singleElement().satisfies(c -> {
            assertThat(c.authType()).isEqualTo("key");
            assertThat(c.logs()).hasSize(1);
        });
    }

    @Test
    void aHostsNameGoesToItsFirstConnectionAndOthersGetTheirUserAfterIt() {
        List<SshSaved.Connection> cs = SshSaved.fromFavorites(List.of(
                fav("deploy", "10.0.3.17", 22, "password", "a", "/a.log"),
                fav("root", "10.0.3.17", 22, "password", "b", "/b.log"),
                fav("ops", "db01", 22, "none", null, "/c.log")), Map.of("10.0.3.17", "Payments prod"));

        assertThat(cs).extracting(SshSaved.Connection::label)
                .containsExactly("Payments prod", "Payments prod (root)", "ops@db01");
    }

    @Test
    void aConnectionIsIdentifiedByUserHostAndPortWhateverTheCaseOfTheHost() {
        assertThat(SshSaved.key("deploy", "DB01.internal", 22)).isEqualTo(SshSaved.key("deploy", "db01.internal", 22));
        assertThat(SshSaved.key("deploy", "db01", 22)).isNotEqualTo(SshSaved.key("root", "db01", 22));
    }

    @Test
    void namesAreFoundInAnyCaseExceptOnTheConnectionItself() {
        SshSaved.Connection a = new SshSaved.Connection("Payments prod", "h", 22, "u", "none", null, null, null, List.of());
        assertThat(SshSaved.named(List.of(a), "payments PROD", "x@h:22")).isSameAs(a);
        assertThat(SshSaved.named(List.of(a), "Payments prod", a.key())).isNull();
        assertThat(SshSaved.named(List.of(a), " ", "x")).isNull();
    }

    @Test
    void savingALogWithAKnownPathReplacesIt() {
        List<SshSaved.Log> logs = List.of(new SshSaved.Log("/a.log", null), new SshSaved.Log("/b.log", null));
        assertThat(SshSaved.withLog(logs, new SshSaved.Log("/a.log", "Spring Boot")))
                .containsExactly(new SshSaved.Log("/a.log", "Spring Boot"), new SshSaved.Log("/b.log", null));
        assertThat(SshSaved.withLog(logs, new SshSaved.Log("/c.log", null))).hasSize(3);
    }

    @Test
    void aConnectionGivesTheSignInForOneOfItsLogs() {
        SshSaved.Connection c = new SshSaved.Connection(null, "h", 2222, "u", "key", "ignored", "/k", "pp", List.of());
        SshConfig config = c.config("/var/log/x.log");
        assertThat(config.keyFilePath()).isEqualTo("/k");
        assertThat(config.password()).isNull();
        assertThat(config.port()).isEqualTo(2222);
        assertThat(c.sameSignIn(c.withName("n"))).isTrue();
        assertThat(c.sameSignIn(new SshSaved.Connection(null, "h", 2222, "u", "key", null, "/k", "other", List.of()))).isFalse();
    }
}
