package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.UpdateCheck.Release;
import com.droidenx.clouseau.core.UpdateCheck.Version;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCheckTest {

    private static String release(String tag, boolean prerelease, boolean draft) {
        return """
                {"tag_name": "%s", "name": "Clouseau %s", "prerelease": %s, "draft": %s,
                 "html_url": "https://github.com/benlazaro/clouseau/releases/tag/%s"}
                """.formatted(tag, tag, prerelease, draft, tag);
    }

    private static String list(String... releases) {
        return "[" + String.join(",", releases) + "]";
    }

    @Test
    void versionsCompareAsSemver() {
        assertThat(Version.parse("0.10.0-alpha")).isGreaterThan(Version.parse("0.9.0-alpha"));
        assertThat(Version.parse("1.0.0")).isGreaterThan(Version.parse("1.0.0-rc.1"));
        assertThat(Version.parse("1.0.0-beta")).isGreaterThan(Version.parse("1.0.0-alpha"));
        assertThat(Version.parse("1.0.0-alpha.10")).isGreaterThan(Version.parse("1.0.0-alpha.2"));
        assertThat(Version.parse("1.0.0-alpha.1")).isGreaterThan(Version.parse("1.0.0-alpha"));
        assertThat(Version.parse("v1.2")).isEqualByComparingTo(Version.parse("1.2.0"));
    }

    @Test
    void notVersionsAreRejected() {
        assertThat(Version.parse("edge")).isNull();
        assertThat(Version.parse("dev")).isNull();
        assertThat(Version.parse("")).isNull();
        assertThat(Version.parse(null)).isNull();
    }

    @Test
    void developmentBuildsAreNotCompared() {
        assertThat(UpdateCheck.isDevelopment("0.1.1-SNAPSHOT")).isTrue();
        assertThat(UpdateCheck.isDevelopment("dev")).isTrue();
        assertThat(UpdateCheck.isDevelopment("0.9.0-alpha")).isFalse();
        assertThat(UpdateCheck.check("0.1.1-SNAPSHOT")).isInstanceOf(UpdateCheck.Result.DevelopmentBuild.class);
    }

    @Test
    void findsTheNewestNewerRelease() {
        String json = list(
                release("edge", true, false),
                release("v0.11.0-alpha", true, false),
                release("v0.10.0-alpha", true, false),
                release("v0.9.0-alpha", true, false));
        Optional<Release> r = UpdateCheck.newest(json, "0.9.0-alpha");
        assertThat(r).isPresent();
        assertThat(r.get().version()).isEqualTo("0.11.0-alpha");
        assertThat(r.get().name()).isEqualTo("Clouseau v0.11.0-alpha");
        assertThat(r.get().url()).endsWith("/tag/v0.11.0-alpha");
    }

    @Test
    void nothingWhenUpToDate() {
        String json = list(release("v0.9.0-alpha", true, false), release("v0.8.0-alpha", true, false));
        assertThat(UpdateCheck.newest(json, "0.9.0-alpha")).isEmpty();
    }

    @Test
    void draftsAreSkipped() {
        String json = list(release("v0.10.0-alpha", true, true), release("v0.9.0-alpha", true, false));
        assertThat(UpdateCheck.newest(json, "0.9.0-alpha")).isEmpty();
    }

    @Test
    void stableReleasesAreNotOfferedPreReleases() {
        String json = list(release("v1.1.0-beta", true, false), release("v1.0.1", false, false));
        assertThat(UpdateCheck.newest(json, "1.0.0")).map(Release::version).contains("1.0.1");
        assertThat(UpdateCheck.newest(json, "1.0.1")).isEmpty();
    }

    @Test
    void malformedJsonEntriesAreSkipped() {
        String json = "[42, {\"tag_name\": null}, {\"name\": \"x\"}, " + release("v1.0.0", false, false) + "]";
        assertThat(UpdateCheck.newest(json, "0.9.0")).map(Release::version).contains("1.0.0");
        assertThat(UpdateCheck.newest("{\"message\": \"rate limited\"}", "0.9.0")).isEmpty();
    }
}
