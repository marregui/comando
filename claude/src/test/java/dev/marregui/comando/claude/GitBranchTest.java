package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitBranchTest {

    @Test
    void readsTheBranchFromAnyDirectoryInsideTheRepository(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/feature/x\n");
        Path deep = Files.createDirectories(repo.resolve("a/b"));
        assertThat(GitBranch.of(deep)).isEqualTo("feature/x");
    }

    @Test
    void detachedHeadShowsTheShortId() {
        assertThat(GitBranch.parseHead("3b56f4c0a1b2c3d4e5f6\n")).isEqualTo("3b56f4c0");
    }

    @Test
    void outsideARepositoryIsEmpty(@TempDir Path dir) {
        assertThat(GitBranch.of(dir)).isEmpty();
    }
}
