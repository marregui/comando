package dev.marregui.comando.claude;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [GitBranch] finds a repository branch from `.git/HEAD` without running a process.
///
/// - Walks parent directories until it finds a repository.
/// - Returns a short object id for a detached HEAD.
/// - Returns empty outside a repository.
/// - Names the top folder of the repository holding a folder, which need not exist.
class GitBranchTest {

    /// A nested `a/b` directory inherits `feature/x` from its ancestor's symbolic `HEAD`, proving upward repository
    /// discovery rather than requiring the caller to pass the root.
    @Test
    void readsTheBranchFromAnyDirectoryInsideTheRepository(@TempDir final Path repo) throws Exception {
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/feature/x\n");
        final Path deep = Files.createDirectories(repo.resolve("a/b"));
        assertThat(GitBranch.of(deep)).isEqualTo("feature/x");
    }

    /// A detached forty-character HEAD exposes its first eight characters, the compact identifier shown to users.
    @Test
    void detachedHeadShowsTheShortId() {
        assertThat(GitBranch.parseHead("3b56f4c0a1b2c3d4e5f6\n")).isEqualTo("3b56f4c0");
    }

    /// A directory with no `.git` ancestor returns an empty branch name rather than attempting to parse a missing HEAD.
    @Test
    void outsideARepositoryIsEmpty(@TempDir final Path dir) {
        assertThat(GitBranch.of(dir)).isEmpty();
    }

    /// A folder not yet on disk inside `repo` gives `repo`, as a session may have moved the file it wrote; a folder
    /// outside any repository gives none.
    @Test
    void theTopIsTheNearestFolderHoldingGit(@TempDir final Path tmp) throws Exception {
        final Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        assertThat(GitBranch.top(repo.resolve("gone/deep"))).contains(repo);
        assertThat(GitBranch.top(tmp.resolve("other"))).isEmpty();
    }
}
