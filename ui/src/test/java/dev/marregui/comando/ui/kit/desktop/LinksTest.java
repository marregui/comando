package dev.marregui.comando.ui.kit.desktop;

import dev.marregui.comando.ui.kit.text.NoteLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LinksTest {

    @TempDir
    Path repo;

    private final List<String> opened = new CopyOnWriteArrayList<>();
    private final List<String> browsed = new CopyOnWriteArrayList<>();
    private final List<String> told = new CopyOnWriteArrayList<>();

    @BeforeEach
    void install() {
        Links.install((f, heading) -> opened.add(f + (heading == null ? "" : " at " + heading)), told::add,
                session -> session.equals("work") ? repo : null);
        Links.browseWith(browsed::add);
    }

    @AfterEach
    void uninstall() {
        Links.install((f, heading) -> {
        }, text -> {
        }, session -> null);
        Links.browseWith(Browser::open);
    }

    @Test
    void aWebAddressGoesToTheBrowser() {
        assertThat(Links.open("https://example.org/x", null)).isTrue();
        assertThat(browsed).containsExactly("https://example.org/x");
        assertThat(opened).isEmpty();
    }

    @Test
    void aFileIsReadFromTheFolderAndOpensAtItsHeading() {
        assertThat(Links.open("../ALPHA.md#caf%C3%A9", Path.of("/kb/claude"))).isTrue();
        assertThat(Links.open("build.gradle.kts", Path.of("/kb/claude"))).as("a file of any kind").isTrue();
        assertThat(opened).containsExactly("/kb/ALPHA.md at caf%C3%A9", "/kb/claude/build.gradle.kts");
    }

    @Test
    void whatNamesNoFileIsNotFollowed() {
        assertThat(Links.open("mailto:a@b.c", Path.of("/kb"))).isFalse();
        assertThat(Links.open("notes.md", null)).as("relative to nothing").isFalse();
        assertThat(Links.open("docs/", Path.of("/kb"))).as("a folder").isFalse();
        assertThat(Links.open("file://host/a.md", Path.of("/kb"))).as("another machine").isFalse();
        assertThat(opened).isEmpty();
        assertThat(browsed).isEmpty();
    }

    @Test
    void aNoteOpensTheFileItResolvesToOnTheEdt() throws Exception {
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(Files.createDirectories(repo.resolve("MEMORY")).resolve("project_current_lane.md"),
                "---\nname: project-current-lane\n---\nLane\n");
        List<Boolean> onEdt = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> Links.open(NoteLinks.href("project-current-lane", "rules"), Links.folderOf("work"),
                (f, heading) -> {
                    onEdt.add(SwingUtilities.isEventDispatchThread());
                    opened.add(f + " at " + heading);
                }));
        await().atMost(Duration.ofSeconds(5)).until(() -> !opened.isEmpty());
        assertThat(opened).containsExactly(repo.resolve("MEMORY/project_current_lane.md") + " at rules");
        assertThat(onEdt).containsExactly(true);
    }

    @Test
    void aNoteThatNamesNoFileIsTold() throws Exception {
        SwingUtilities.invokeAndWait(() -> Links.open(NoteLinks.href("no-such-note", null), repo));
        await().atMost(Duration.ofSeconds(5)).until(() -> !told.isEmpty());
        assertThat(told.getFirst()).startsWith("No file for [[no-such-note]] under ");
        assertThat(opened).isEmpty();
    }

    @Test
    void aSessionsLinksAreReadFromItsFolder() {
        assertThat(Links.folderOf("work")).isEqualTo(repo);
        assertThat(Links.folderOf("other")).isNull();
        assertThat(Links.folderOf("")).isNull();
    }

    @Test
    void aLinksHeadingIsWhatFollowsTheHashAsWritten() {
        assertThat(Links.heading("setup.md#2-install")).isEqualTo("2-install");
        assertThat(Links.heading("my notes.md#top")).isEqualTo("top");
        assertThat(Links.heading("setup.md")).isNull();
        assertThat(Links.heading("setup.md#")).as("no heading: the top").isNull();
    }
}
