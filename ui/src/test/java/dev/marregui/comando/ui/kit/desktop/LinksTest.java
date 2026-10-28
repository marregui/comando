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

/// [Links] follows a link from rendered text to the browser, a file at a heading, or a note.
/// - A web address goes to the browser.
/// - A file link resolves against the page's folder and opens at its heading.
/// - What names no file on this machine is not followed.
/// - A note link opens the file it resolves to on the EDT, or tells that none was found.
/// - A session's links resolve against its folder; a link's heading is its fragment as written.
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

    /// An `https` link is handed to the browser and opens no file.
    @Test
    void aWebAddressGoesToTheBrowser() {
        assertThat(Links.open("https://example.org/x", null)).isTrue();
        assertThat(browsed).containsExactly("https://example.org/x");
        assertThat(opened).isEmpty();
    }

    /// A relative link resolves against the folder, `..` included, and opens at its fragment kept percent-encoded; a
    /// file of any kind is opened.
    @Test
    void aFileIsReadFromTheFolderAndOpensAtItsHeading() {
        assertThat(Links.open("../ALPHA.md#caf%C3%A9", Path.of("/kb/claude"))).isTrue();
        assertThat(Links.open("build.gradle.kts", Path.of("/kb/claude"))).as("a file of any kind").isTrue();
        assertThat(opened).containsExactly("/kb/ALPHA.md at caf%C3%A9", "/kb/claude/build.gradle.kts");
    }

    /// A `mailto` link, a relative link with no folder, a folder and a `file` URI naming another host return false
    /// and open nothing.
    @Test
    void whatNamesNoFileIsNotFollowed() {
        assertThat(Links.open("mailto:a@b.c", Path.of("/kb"))).isFalse();
        assertThat(Links.open("notes.md", null)).as("relative to nothing").isFalse();
        assertThat(Links.open("docs/", Path.of("/kb"))).as("a folder").isFalse();
        assertThat(Links.open("file://host/a.md", Path.of("/kb"))).as("another machine").isFalse();
        assertThat(opened).isEmpty();
        assertThat(browsed).isEmpty();
    }

    /// A [NoteLinks] href for a note whose front matter name matches a file in the session's repository opens that
    /// file at the heading, and the opener runs on the EDT.
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

    /// A note href that resolves to no file tells the user `No file for [[name]] under …` and opens nothing.
    @Test
    void aNoteThatNamesNoFileIsTold() throws Exception {
        SwingUtilities.invokeAndWait(() -> Links.open(NoteLinks.href("no-such-note", null), repo));
        await().atMost(Duration.ofSeconds(5)).until(() -> !told.isEmpty());
        assertThat(told.getFirst()).startsWith("No file for [[no-such-note]] under ");
        assertThat(opened).isEmpty();
    }

    /// [Links#folderOf] returns the folder the resolver gives a session, and null for an unknown or empty session.
    @Test
    void aSessionsLinksAreReadFromItsFolder() {
        assertThat(Links.folderOf("work")).isEqualTo(repo);
        assertThat(Links.folderOf("other")).isNull();
        assertThat(Links.folderOf("")).isNull();
    }

    /// A null session, as a panel without one has, has no folder and never reaches the resolver.
    @Test
    void aNullSessionHasNoFolder() {
        assertThat(Links.folderOf(null)).isNull();
    }

    /// [Links#heading] returns the text after `#`, spaces in the file name included; no `#`, or nothing after it,
    /// means the top and returns null.
    @Test
    void aLinksHeadingIsWhatFollowsTheHashAsWritten() {
        assertThat(Links.heading("setup.md#2-install")).isEqualTo("2-install");
        assertThat(Links.heading("my notes.md#top")).isEqualTo("top");
        assertThat(Links.heading("setup.md")).isNull();
        assertThat(Links.heading("setup.md#")).as("no heading: the top").isNull();
    }
}
