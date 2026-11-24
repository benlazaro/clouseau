package com.droidenx.clouseau.core;

import com.droidenx.clouseau.core.JetBrainsProjects.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JetBrainsProjectsTest {

    @TempDir Path dir;

    /** A recentProjects.xml as IntelliJ 2026.3 writes it, trimmed. */
    private static String recent(String build) {
        return """
                <application>
                  <component name="RecentProjectsManager">
                    <option name="additionalInfo">
                      <map>
                        <entry key="$USER_HOME$/git/d2xx4j">
                          <value>
                            <RecentProjectMetaInfo frameTitle="d2xx4j" projectWorkspaceId="31GOt">
                              <option name="projectOpenTimestamp" value="1787617889254" />
                            </RecentProjectMetaInfo>
                          </value>
                        </entry>
                        <entry key="$USER_HOME$/git/clouseau">
                          <value>
                            <RecentProjectMetaInfo frameTitle="clouseau – README.md" opened="true" projectWorkspaceId="3BYs">
                              <option name="build" value="IU-%s" />
                            </RecentProjectMetaInfo>
                          </value>
                        </entry>
                        <entry key="$USER_HOME$/git/clouseau/clouseau-plugin">
                          <value>
                            <RecentProjectMetaInfo frameTitle="plugin" projectWorkspaceId="3BYt">
                            </RecentProjectMetaInfo>
                          </value>
                        </entry>
                      </map>
                    </option>
                    <option name="lastOpenedProject" value="$USER_HOME$/git/clouseau" />
                  </component>
                </application>
                """.formatted(build);
    }

    @Test
    void readsEachProjectsFolderAndWhetherItsOpen() {
        Path home = dir.resolve("home");
        List<Project> projects = JetBrainsProjects.parse(recent("263.1"), home);
        assertThat(projects).extracting(Project::name).containsExactly("d2xx4j", "clouseau", "clouseau-plugin");
        assertThat(projects).extracting(Project::open).containsExactly(false, true, false);
        assertThat(projects.get(0).root()).isEqualTo(home.resolve("git/d2xx4j").toAbsolutePath().normalize());
    }

    @Test
    void listsTheFoldersOfOpenProjectsOnly() {
        Path home = dir.resolve("home");
        JetBrainsProjects projects = new JetBrainsProjects(JetBrainsProjects.parse(recent("263.1"), home));
        assertThat(projects.openRoots()).containsExactly(home.resolve("git/clouseau").toAbsolutePath().normalize());
    }

    @Test
    void aFileBelongsToTheDeepestProjectHoldingIt() {
        Path home = dir.resolve("home");
        JetBrainsProjects projects = new JetBrainsProjects(JetBrainsProjects.parse(recent("263.1"), home));
        assertThat(projects.projectOf(home.resolve("git/clouseau/clouseau-core/src/Foo.java")))
                .map(Project::name).contains("clouseau");
        assertThat(projects.projectOf(home.resolve("git/clouseau/clouseau-plugin/src/Bar.java")))
                .map(Project::name).contains("clouseau-plugin");
        assertThat(projects.projectOf(home.resolve("git/d2xx4j-extra/src/Baz.java"))).as("a sibling, not inside").isEmpty();
    }

    @Test
    void picksTheRunningIdesFileByVersionThenBuild() throws Exception {
        Path root = dir.resolve("JetBrains");
        Path old = write(root.resolve("IntelliJIdea2026.2/options/recentProjects.xml"), recent("262.9"));
        Path current = write(root.resolve("IntelliJIdea2026.3/options/recentProjects.xml"), recent("263.7"));
        Path pycharm = write(root.resolve("PyCharm2026.3/options/recentProjects.xml"), recent("263.8"));
        Files.setLastModifiedTime(pycharm, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        assertThat(JetBrainsProjects.recentProjectsFile(root, "IntelliJ IDEA 2026.3", "IDEA", "263.7")).contains(current);
        assertThat(JetBrainsProjects.recentProjectsFile(root, "PyCharm 2026.3", "PyCharm", "263.8")).contains(pycharm);
        // A build no file names yet: by product.
        assertThat(JetBrainsProjects.recentProjectsFile(root, "IntelliJ IDEA 2026.3", "IDEA", "263.99")).contains(current);
        assertThat(JetBrainsProjects.recentProjectsFile(root, "IntelliJ IDEA 2026.2", "IDEA", "262.9")).contains(old);
        assertThat(JetBrainsProjects.recentProjectsFile(root, "IntelliJ IDEA 2027.1", "IDEA", "271.1")).isEmpty();
        assertThat(JetBrainsProjects.recentProjectsFile(root, "IntelliJ IDEA", "IDEA", "263.7")).as("no version").isEmpty();
    }

    @Test
    void knowsWhichConfigFoldersAreWhichProduct() {
        assertThat(JetBrainsProjects.isProduct("IntelliJIdea2026.3", "IDEA")).isTrue();
        assertThat(JetBrainsProjects.isProduct("IdeaIC2025.1", "IDEA")).isTrue();
        assertThat(JetBrainsProjects.isProduct("PyCharm2026.3", "IDEA")).isFalse();
        assertThat(JetBrainsProjects.isProduct("PyCharmCE2026.3", "PyCharm")).isTrue();
        assertThat(JetBrainsProjects.isProduct("WebStorm2026.3", null)).isFalse();
    }

    @Test
    void aProjectNeverOpenedIsTheFolderWithGitOrIdea() throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path file = write(repo.resolve("module/src/main/java/com/acme/Foo.java"), "class Foo {}");
        assertThat(JetBrainsProjects.likelyRoot(file)).isEqualTo(repo.toAbsolutePath().normalize());

        Path loose = write(dir.resolve("loose/Bar.java"), "class Bar {}");
        assertThat(JetBrainsProjects.likelyRoot(loose)).isEqualTo(loose.getParent().toAbsolutePath().normalize());
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
