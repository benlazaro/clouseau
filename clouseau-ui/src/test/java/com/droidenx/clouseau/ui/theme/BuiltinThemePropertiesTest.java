package com.droidenx.clouseau.ui.theme;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The revised dark theme sets every Clouseau color the current one does, so nothing falls back to a light-theme default. */
class BuiltinThemePropertiesTest {

    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = BuiltinThemePropertiesTest.class.getResourceAsStream(name + ".properties")) {
            assertThat(in).as(name + ".properties on the classpath").isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static Set<String> clouseauKeys(Properties p) {
        Set<String> keys = new TreeSet<>();
        for (String k : p.stringPropertyNames()) if (k.startsWith("Clouseau.")) keys.add(k);
        return keys;
    }

    @Test
    void revisedDarkCoversEveryClouseauKeyOfDark() throws Exception {
        Set<String> dark = clouseauKeys(load("ClouseauDarkLaf"));
        Set<String> revised = clouseauKeys(load("ClouseauDarkRevisedLaf"));
        assertThat(revised).containsAll(dark);
        assertThat(revised).contains("Clouseau.selectionForeground", "Clouseau.selectionRing");
    }

    @Test
    void revisedDarkIsABuiltinTheme() {
        assertThat(ThemeManager.getAllThemes())
                .filteredOn(ThemeManager.ThemeEntry::builtin)
                .extracting(ThemeManager.ThemeEntry::name)
                .containsSubsequence("Dark", "Dark (revised)", "Light");
    }
}
