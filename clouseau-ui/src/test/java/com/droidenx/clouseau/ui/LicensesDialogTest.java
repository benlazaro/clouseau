package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LicensesDialogTest {

    @Test
    void showsClouseausLicenseThenTheNotices() {
        String text = LicensesDialog.text();

        assertThat(text).startsWith("Clouseau\n========\n\nMIT License");
        assertThat(text.indexOf("third-party notices")).isGreaterThan(text.indexOf("MIT License"));
        // The licenses that require their text to ship with the software.
        assertThat(text).contains("SIL OPEN FONT LICENSE Version 1.1")
                .contains("Apache License")
                .contains("Robert Futrell")
                .contains("Legion of the Bouncy Castle");
    }

    @Test
    void splitsTheNoticesIntoOneTabPerLicense() {
        List<LicenseNotices.Tab> tabs = LicensesDialog.tabs();

        assertThat(tabs).extracting(LicenseNotices.Tab::label)
                .containsExactly("Clouseau", "Apache 2.0", "MIT", "OFL 1.1", "BSD 3-Clause");
        LicenseNotices.Tab clouseau = tabs.get(0);
        assertThat(clouseau.note()).contains("OpenJDK").contains("Classpath Exception");
        assertThat(clouseau.texts()).singleElement().satisfies(t -> assertThat(t.body()).startsWith("MIT License"));

        LicenseNotices.Tab mit = tabs.get(2);
        assertThat(mit.components()).extracting(LicenseNotices.Component::name)
                .containsExactly("Java SemVer", "Bouncy Castle", "SLF4J API");
        assertThat(mit.texts()).hasSize(3);
        assertThat(mit.components().get(1).copyright()).endsWith("The Legion of the Bouncy Castle Inc.");
        assertThat(mit.components().get(1).url()).isEqualTo("https://www.bouncycastle.org");
        assertThat(mit.components().get(1).coordinates()).contains("org.bouncycastle:bcutil-jdk18on");

        LicenseNotices.Component log4j = tabs.get(1).components().stream()
                .filter(c -> c.name().equals("Apache Log4j")).findFirst().orElseThrow();
        assertThat(log4j.notes()).contains("NOTICE:").contains("The Apache Software Foundation");
    }

    /** Nothing in the file may go missing from the tabs: every module listed, every license text. */
    @Test
    void everyComponentAndLicenseTextIsInATab() {
        String notices = LicensesDialog.text();
        List<LicenseNotices.Tab> tabs = LicensesDialog.tabs();

        String coordinates = String.join(" ", tabs.stream().flatMap(t -> t.components().stream())
                .map(LicenseNotices.Component::coordinates).toList());
        Matcher module = Pattern.compile("\\(([\\w.-]+:[\\w.-]+)").matcher(notices);
        while (module.find()) assertThat(coordinates).contains(module.group(1));

        long headers = Pattern.compile("(?m)^={40,}$").matcher(notices).results().count() / 2;
        long texts = tabs.stream().mapToLong(t -> t.texts().size()).sum();
        assertThat(texts).isEqualTo(headers + 1);   // and Clouseau's own

        for (LicenseNotices.Tab tab : tabs) assertThat(tab.texts()).as(tab.label()).isNotEmpty();
    }

    @Test
    void namesTheLicenseFamilies() {
        assertThat(LicenseNotices.family("Apache License 2.0")).isEqualTo("Apache 2.0");
        assertThat(LicenseNotices.family("Bouncy Castle License (MIT)")).isEqualTo("MIT");
        assertThat(LicenseNotices.family("CC0 1.0 Universal (public domain dedication)")).isEqualTo("CC0");
        assertThat(LicenseNotices.family("SIL Open Font License 1.1")).isEqualTo("OFL 1.1");
        assertThat(LicenseNotices.family("Eclipse Public License 2.0 (EPL)")).isEqualTo("Eclipse Public License 2.0");
    }
}
