package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// [GitBranch] finds a repository branch from `.git/HEAD` without running a process.
///
/// - Walks parent directories until it finds a repository.
/// - Returns a short object id for a detached HEAD.
/// - Returns empty outside a repository.
class GitBranchTest {

    /// A nested `a/b` directory inherits `feature/x` from its ancestor's symbolic `HEAD`, proving upward repository
    /// discovery rather than requiring the caller to pass the root.
    @Test
    void readsTheBranchFromAnyDirectoryInsideTheRepository(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/feature/x\n");
        Path deep = Files.createDirectories(repo.resolve("a/b"));
        assertThat(GitBranch.of(deep)).isEqualTo("feature/x");
    }

    /// A detached forty-character HEAD exposes its first eight characters, the compact identifier shown to users.
    @Test
    void detachedHeadShowsTheShortId() {
        assertThat(GitBranch.parseHead("3b56f4c0a1b2c3d4e5f6\n")).isEqualTo("3b56f4c0");
    }

    /// A directory with no `.git` ancestor returns an empty branch name rather than attempting to parse a missing HEAD.
    @Test
    void outsideARepositoryIsEmpty(@TempDir Path dir) {
        assertThat(GitBranch.of(dir)).isEmpty();
    }
}
