package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [FileSearch#bySuffix] finds a relative path where it was not read from, under the session's folders.
/// - A path written from a deeper folder is found by whole-name suffix under a root.
/// - The first root that holds a match wins, and of several matches there the newest.
/// - Build and Git folders are not searched; climbing and absolute paths find nothing.
class FileSearchTest {

    @TempDir
    Path tmp;

    private Path file(String relative) throws Exception {
        Path f = tmp.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "x");
        return f;
    }

    /// A path relative to a folder five levels down is found under the root; a suffix that cuts a file or folder name
    /// in the middle matches nothing.
    @Test
    void aPathWrittenFromADeeperFolderIsFoundUnderTheSessionsFolder() throws Exception {
        Path deep = file("session/BRAIN/SCRATCHPAD/2026-10-28/harness/07-fleet-vision.md");
        assertThat(FileSearch.bySuffix("harness/07-fleet-vision.md", List.of(tmp.resolve("session")))).contains(deep);
        assertThat(FileSearch.bySuffix("vision.md", List.of(tmp.resolve("session")))).as("a whole name, not its end").isEmpty();
        assertThat(FileSearch.bySuffix("ness/07-fleet-vision.md", List.of(tmp.resolve("session")))).as("whole folder names").isEmpty();
    }

    /// Of two matches under the first root the newer by modification time wins over a match in the second root; a
    /// missing first root is skipped.
    @Test
    void theFirstRootThatHoldsOneWinsAndOfSeveralThereTheNewest() throws Exception {
        Path old = file("a/x/notes/plan.md");
        Path newer = file("a/y/notes/plan.md");
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000L));
        Path other = file("b/notes/plan.md");
        assertThat(FileSearch.bySuffix("notes/plan.md", List.of(tmp.resolve("a"), tmp.resolve("b")))).contains(newer);
        assertThat(FileSearch.bySuffix("notes/plan.md", List.of(tmp.resolve("missing"), tmp.resolve("b")))).contains(other);
    }

    /// A file under `build` or `.git` is not found, and a query with `..` or an absolute path returns nothing.
    @Test
    void buildAndGitFoldersAreNotSearchedNorPathsThatClimbOrAreAbsolute() throws Exception {
        file("r/build/out/plan.md");
        file("r/.git/plan.md");
        assertThat(FileSearch.bySuffix("out/plan.md", List.of(tmp.resolve("r")))).isEmpty();
        assertThat(FileSearch.bySuffix("../r/plan.md", List.of(tmp.resolve("r")))).isEmpty();
        assertThat(FileSearch.bySuffix(tmp.resolve("r/build/out/plan.md").toString(), List.of(tmp.resolve("r")))).isEmpty();
    }
}
