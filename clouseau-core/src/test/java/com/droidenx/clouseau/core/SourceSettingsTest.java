package com.droidenx.clouseau.core;

import com.droidenx.clouseau.api.SourceSetting;
import com.droidenx.clouseau.core.SourceSettings.Issue;
import com.droidenx.clouseau.core.SourceSettings.Problem;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceSettingsTest {

    private static final List<SourceSetting> SETTINGS = List.of(
            SourceSetting.text("url", "Server URL"),
            SourceSetting.password("token", "Token").optional(),
            SourceSetting.number("limit", "Limit").withDefault("100"),
            SourceSetting.choice("dir", "Order", List.of("forward", "backward")));

    @Test
    void validValuesAreTrimmedAndPassedOn() {
        var result = SourceSettings.check(SETTINGS, Map.of(
                "url", "  http://loki:3100 ", "token", " s3cret ", "limit", "50", "dir", "backward",
                "unrelated", "x"));

        assertThat(result.ok()).isTrue();
        assertThat(result.values()).isEqualTo(Map.of(
                "url", "http://loki:3100", "token", " s3cret ", "limit", "50", "dir", "backward"));
    }

    @Test
    void emptyOptionalValuesAreLeftOut() {
        var result = SourceSettings.check(SETTINGS, Map.of("url", "u", "token", "", "limit", "1", "dir", "forward"));

        assertThat(result.ok()).isTrue();
        assertThat(result.values()).doesNotContainKey("token");
    }

    @Test
    void reportsEachProblem() {
        Map<String, String> entered = new HashMap<>();
        entered.put("url", "   ");
        entered.put("limit", "ten");
        entered.put("dir", "sideways");

        var result = SourceSettings.check(SETTINGS, entered);

        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).containsExactly(
                new Issue("url", "Server URL", Problem.MISSING),
                new Issue("limit", "Limit", Problem.NOT_A_NUMBER),
                new Issue("dir", "Order", Problem.NOT_A_CHOICE));
    }

    @Test
    void settingBuildersApplyDefaultsAndRules() {
        assertThat(SourceSetting.choice("d", "D", List.of("a", "b")).defaultValue()).isEqualTo("a");
        assertThat(SourceSetting.text("k", "K").required()).isTrue();
        assertThat(SourceSetting.text("k", "K").optional().required()).isFalse();
        assertThatThrownBy(() -> new SourceSetting("k", "K", SourceSetting.Type.CHOICE, true, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceSetting("k", "K", SourceSetting.Type.TEXT, true, null, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceSetting.text(" ", "K")).isInstanceOf(IllegalArgumentException.class);
    }
}
