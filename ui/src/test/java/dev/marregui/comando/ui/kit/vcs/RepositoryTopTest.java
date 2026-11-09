package dev.marregui.comando.ui.kit.vcs;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [RepositoryTop] finds the top of the git repository above a folder, within a bound.
/// - The nearest folder with a `.git` is the top, the folder itself included.
/// - A folder under no repository, or deeper below one than the bound, has no top; a bound given reaches further.
class RepositoryTopTest {

    /// A folder inside a repository and the repository's own folder both answer the repository's top; with a nested
    /// repository the nearer one wins.
    @Test
    void theNearestFolderWithAGitEntryIsTheTop(@TempDir final Path dir) throws Exception {
        final Path outer = Files.createDirectories(dir.resolve("outer"));
        Files.createDirectories(outer.resolve(".git"));
        final Path inner = Files.createDirectories(outer.resolve("modules").resolve("inner"));
        Files.createFile(inner.resolve(".git"));
        final Path deep = Files.createDirectories(outer.resolve("src").resolve("main"));
        final Path innerDeep = Files.createDirectories(inner.resolve("src"));

        assertThat(RepositoryTop.above(deep)).as("a folder inside").contains(outer);
        assertThat(RepositoryTop.above(outer)).as("the top itself").contains(outer);
        assertThat(RepositoryTop.above(innerDeep)).as("the nearer of two").contains(inner);
    }

    /// Under no repository the answer is empty; so it is below the bound, where a repository's top is too far up
    /// to be the file's.
    @Test
    void noRepositoryOrOneBeyondTheBoundHasNoTop(@TempDir final Path dir) throws Exception {
        assertThat(RepositoryTop.above(Files.createDirectories(dir.resolve("plain")))).as("no repository").isEmpty();

        final Path top = Files.createDirectories(dir.resolve("top"));
        Files.createDirectories(top.resolve(".git"));
        Path far = top;
        for (int i = 0; i < RepositoryTop.MAX_UP - 1; i++) {
            far = far.resolve("d" + i);
        }
        assertThat(RepositoryTop.above(Files.createDirectories(far))).as("the last folder within the bound")
                .contains(top);
        assertThat(RepositoryTop.above(Files.createDirectories(far.resolve("one-more")))).as("one beyond it").isEmpty();
    }

    /// A bound given finds the top further up than the default one does, as a note's links reach to its repository
    /// however deep the note lies.
    @Test
    void aBoundGivenReachesFurtherUp(@TempDir final Path dir) throws Exception {
        final Path top = Files.createDirectories(dir.resolve("top"));
        Files.createDirectories(top.resolve(".git"));
        Path deep = top;
        for (int i = 0; i < RepositoryTop.MAX_UP + 3; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        assertThat(RepositoryTop.above(deep)).as("beyond the default bound").isEmpty();
        assertThat(RepositoryTop.above(deep, Integer.MAX_VALUE)).contains(top);
    }
}
