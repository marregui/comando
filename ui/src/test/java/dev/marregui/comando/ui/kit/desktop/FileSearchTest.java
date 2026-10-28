package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A relative path found where it was not read from (record 111). */
class FileSearchTest {

    @TempDir
    Path tmp;

    private Path file(String relative) throws Exception {
        Path f = tmp.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "x");
        return f;
    }

    @Test
    void aPathWrittenFromADeeperFolderIsFoundUnderTheSessionsFolder() throws Exception {
        Path deep = file("session/BRAIN/SCRATCHPAD/2026-10-28/harness/07-fleet-vision.md");
        assertThat(FileSearch.bySuffix("harness/07-fleet-vision.md", List.of(tmp.resolve("session")))).contains(deep);
        assertThat(FileSearch.bySuffix("vision.md", List.of(tmp.resolve("session")))).as("a whole name, not its end").isEmpty();
        assertThat(FileSearch.bySuffix("ness/07-fleet-vision.md", List.of(tmp.resolve("session")))).as("whole folder names").isEmpty();
    }

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

    @Test
    void buildAndGitFoldersAreNotSearchedNorPathsThatClimbOrAreAbsolute() throws Exception {
        file("r/build/out/plan.md");
        file("r/.git/plan.md");
        assertThat(FileSearch.bySuffix("out/plan.md", List.of(tmp.resolve("r")))).isEmpty();
        assertThat(FileSearch.bySuffix("../r/plan.md", List.of(tmp.resolve("r")))).isEmpty();
        assertThat(FileSearch.bySuffix(tmp.resolve("r/build/out/plan.md").toString(), List.of(tmp.resolve("r")))).isEmpty();
    }
}
