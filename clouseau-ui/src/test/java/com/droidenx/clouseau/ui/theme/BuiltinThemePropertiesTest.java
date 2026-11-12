package com.droidenx.clouseau.ui.theme;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** The built-in themes: which there are, and the Clouseau colors they must set. */
class BuiltinThemePropertiesTest {

    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = BuiltinThemePropertiesTest.class.getResourceAsStream(name + ".properties")) {
            assertThat(in).as(name + ".properties on the classpath").isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void darkSetsTheColorsThatOtherwiseFallBackToLightThemeDefaults() throws Exception {
        assertThat(load("ClouseauDarkLaf").stringPropertyNames()).contains(
                "Clouseau.selectionBackground", "Clouseau.selectionForeground", "Clouseau.selectionRing", "Clouseau.selectionRingInactive",
                "Clouseau.stripeBackground", "Clouseau.activeFilterForeground", "Clouseau.findMarkBg", "Clouseau.findMarkBorder",
                "[style].logTable");
    }

    @Test
    void lightSetsEveryClouseauColorDarkDoes() throws Exception {
        java.util.Set<String> dark = new java.util.TreeSet<>(), light = new java.util.TreeSet<>();
        for (String k : load("ClouseauDarkLaf").stringPropertyNames()) if (k.startsWith("Clouseau.")) dark.add(k);
        for (String k : load("ClouseauLightLaf").stringPropertyNames()) if (k.startsWith("Clouseau.")) light.add(k);
        assertThat(light).containsAll(dark);
    }

    @Test
    void theBuiltinThemesAreDarkAndLight() {
        assertThat(ThemeManager.getAllThemes())
                .filteredOn(ThemeManager.ThemeEntry::builtin)
                .extracting(ThemeManager.ThemeEntry::name)
                .containsExactly("Dark", "Light");
    }

    @Test
    void aSettingThatNamesTheFormerDarkRevisedMeansDark() {
        assertThat(ThemeManager.canonicalName("Dark (revised)")).isEqualTo("Dark");
        assertThat(ThemeManager.canonicalName("Light")).isEqualTo("Light");
        assertThat(ThemeManager.canonicalName("my-theme")).isEqualTo("my-theme");
    }
}
