package dev.marregui.comando.tools;

import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GitTest {

    /** Runs git in {@code dir} as a test sets a repository up, with an identity of its own and no user configuration. */
    static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
                "-c", "init.defaultBranch=main", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
    }

    /**
     * A repository with one of each: a file changed, one added to the index, one deleted, one renamed in the index, one
     * untracked inside an untracked folder, and an ignored folder of build output.
     */
    static Path repo(Path dir) throws Exception {
        git(dir, "init", "-q");
        Files.writeString(dir.resolve(".gitignore"), "dist/\n");
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/A.java"), "class A {\n    int x = 1;\n}\n");
        Files.writeString(dir.resolve("gone.txt"), "bye\n");
        Files.writeString(dir.resolve("old.txt"), "same\n");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "first");
        Files.writeString(dir.resolve("src/main/A.java"), "class A {\n    int x = 2;\n}\n");
        Files.writeString(dir.resolve("added.txt"), "new\n");
        git(dir, "add", "added.txt");
        Files.delete(dir.resolve("gone.txt"));
        git(dir, "mv", "old.txt", "new name.txt");
        Files.createDirectories(dir.resolve("notes/deep"));
        Files.writeString(dir.resolve("notes/deep/n.md"), "# n\n");
        Files.createDirectories(dir.resolve("dist"));
        Files.writeString(dir.resolve("dist/bundle.js"), "x");
        return dir;
    }

    @Test
    void statusListsEveryKindOfChangeAndTheIgnoredFolder(@TempDir Path dir) throws Exception {
        Path root = repo(dir.toRealPath());
        try (Git git = new Git()) {
            Git.Read read = git.status(root);
            RepoStatus s = read.status();
            assertThat(s.error()).isEmpty();
            assertThat(s.branch()).isEqualTo("main");
            assertThat(s.changes()).containsExactly(
                    new RepoStatus.FileChange("added.txt", "", RepoStatus.Kind.ADDED),
                    new RepoStatus.FileChange("gone.txt", "", RepoStatus.Kind.DELETED),
                    new RepoStatus.FileChange("new name.txt", "old.txt", RepoStatus.Kind.RENAMED),
                    new RepoStatus.FileChange("notes/deep/n.md", "", RepoStatus.Kind.UNTRACKED),
                    new RepoStatus.FileChange("src/main/A.java", "", RepoStatus.Kind.MODIFIED));
            assertThat(read.ignored()).containsExactly(root.resolve("dist"));
        }
    }

    @Test
    void textsAreHeadsVersionAndTheWorkingTrees(@TempDir Path dir) throws Exception {
        Path root = repo(dir.toRealPath());
        try (Git git = new Git()) {
            ChangeTexts modified = git.texts(root, new RepoStatus.FileChange("src/main/A.java", "", RepoStatus.Kind.MODIFIED));
            assertThat(modified.note()).isEmpty();
            assertThat(modified.before()).contains("x = 1");
            assertThat(modified.after()).contains("x = 2");
            ChangeTexts renamed = git.texts(root, new RepoStatus.FileChange("new name.txt", "old.txt", RepoStatus.Kind.RENAMED));
            assertThat(renamed.before()).isEqualTo("same\n");
            assertThat(renamed.after()).isEqualTo("same\n");
            ChangeTexts deleted = git.texts(root, new RepoStatus.FileChange("gone.txt", "", RepoStatus.Kind.DELETED));
            assertThat(deleted.before()).isEqualTo("bye\n");
            assertThat(deleted.after()).isEmpty();
            ChangeTexts untracked = git.texts(root, new RepoStatus.FileChange("notes/deep/n.md", "", RepoStatus.Kind.UNTRACKED));
            assertThat(untracked.before()).isEmpty();
            assertThat(untracked.after()).isEqualTo("# n\n");
        }
    }

    @Test
    void aBinaryFileIsNotCompared(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        git(root, "init", "-q");
        Files.write(root.resolve("b.bin"), new byte[]{1, 0, 2});
        try (Git git = new Git()) {
            ChangeTexts t = git.texts(root, new RepoStatus.FileChange("b.bin", "", RepoStatus.Kind.UNTRACKED));
            assertThat(t.note()).contains("binary");
            assertThat(t.after()).isEmpty();
        }
    }

    @Test
    void aRepositoryWithoutCommitsHasNothingBefore(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        git(root, "init", "-q");
        Files.writeString(root.resolve("a.txt"), "a\n");
        git(root, "add", "a.txt");
        try (Git git = new Git()) {
            assertThat(git.status(root).status().changes()).containsExactly(new RepoStatus.FileChange("a.txt", "", RepoStatus.Kind.ADDED));
            ChangeTexts t = git.texts(root, new RepoStatus.FileChange("a.txt", "", RepoStatus.Kind.MODIFIED));
            assertThat(t.before()).isEmpty();
            assertThat(t.after()).isEqualTo("a\n");
        }
    }

    @Test
    void aMissingGitIsAStatusThatSaysSo(@TempDir Path dir) throws Exception {
        Path root = repo(dir.toRealPath());
        try (Git git = new Git(dir.resolve("no-such-git").toString())) {
            RepoStatus s = git.status(root).status();
            assertThat(s.error()).isNotEmpty();
            assertThat(s.changes()).isEmpty();
        }
    }

    @Test
    void reposAreTheOneAboveOrThoseDirectlyInside(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Files.createDirectories(root.resolve("a/.git"));
        Files.createDirectories(root.resolve("a/sub/deeper"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("b/.git"), "gitdir: elsewhere\n");
        Files.createDirectories(root.resolve("c"));
        assertThat(Git.repos(root.resolve("a/sub/deeper"))).containsExactly(root.resolve("a"));
        assertThat(Git.repos(root)).containsExactly(root.resolve("a"), root.resolve("b"));
        assertThat(Git.repos(root.resolve("c"))).isEmpty();
    }

    @Test
    void parseReadsEveryRecordKind() {
        Path root = Path.of("/r");
        String out = String.join("\0",
                "# branch.oid abc",
                "# branch.head topic",
                "1 .M N... 100644 100644 100644 a a src/x y.java",
                "1 A. N... 000000 100644 100644 0 a added",
                "1 AD N... 000000 100644 000000 0 a added-then-gone",
                "1 .D N... 100644 100644 000000 a a gone",
                "2 R. N... 100644 100644 100644 a a R100 to", "from",
                "u UU N... 100644 100644 100644 100644 a b c clash",
                "? new dir/f",
                "! build/",
                "! .idea/x.xml", "");
        Git.Read read = Git.parse(root, out.getBytes(StandardCharsets.UTF_8), false);
        assertThat(read.status().branch()).isEqualTo("topic");
        assertThat(read.status().changes()).containsExactly(
                new RepoStatus.FileChange("added", "", RepoStatus.Kind.ADDED),
                new RepoStatus.FileChange("clash", "", RepoStatus.Kind.CONFLICTED),
                new RepoStatus.FileChange("gone", "", RepoStatus.Kind.DELETED),
                new RepoStatus.FileChange("new dir/f", "", RepoStatus.Kind.UNTRACKED),
                new RepoStatus.FileChange("src/x y.java", "", RepoStatus.Kind.MODIFIED),
                new RepoStatus.FileChange("to", "from", RepoStatus.Kind.RENAMED));
        assertThat(read.ignored()).containsExactlyInAnyOrder(root.resolve("build"), root.resolve(".idea/x.xml"));
        assertThat(read.status().truncated()).isFalse();
    }

    @Test
    void anIntentToAddIsAddedAndARenameThenDeletedIsTheOldPathDeleted() {
        String out = String.join("\0",
                "1 .A N... 000000 000000 100644 0 0 intent",
                "2 RD N... 100644 100644 000000 a a R100 moved", "orig", "");
        assertThat(Git.parse(Path.of("/r"), out.getBytes(StandardCharsets.UTF_8), false).status().changes()).containsExactly(
                new RepoStatus.FileChange("intent", "", RepoStatus.Kind.ADDED),
                new RepoStatus.FileChange("orig", "", RepoStatus.Kind.DELETED));
    }

    @Test
    void onlyAPathMissingFromHeadReadsAsNothingBefore() {
        assertThat(Git.notInHead("fatal: path 'nope' does not exist in 'HEAD'")).isTrue();
        assertThat(Git.notInHead("fatal: invalid object name 'HEAD'.")).isTrue();
        assertThat(Git.notInHead("fatal: Not a valid object name HEAD:sub")).as("a submodule").isFalse();
        assertThat(Git.notInHead("fatal: detected dubious ownership in repository")).isFalse();
    }

    @Test
    void aSubmoduleIsNotCompared(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        git(root, "init", "-q");
        Files.writeString(root.resolve("a.txt"), "a\n");
        git(root, "add", "a.txt");
        git(root, "update-index", "--add", "--cacheinfo", "160000,0123456789abcdef0123456789abcdef01234567,sub");
        git(root, "commit", "-q", "-m", "with a submodule");
        git(root, "update-index", "--force-remove", "sub");
        try (Git git = new Git()) {
            assertThat(git.status(root).status().changes()).containsExactly(new RepoStatus.FileChange("sub", "", RepoStatus.Kind.DELETED));
            ChangeTexts t = git.texts(root, new RepoStatus.FileChange("sub", "", RepoStatus.Kind.DELETED));
            assertThat(t.note()).isEqualTo("a submodule: not compared");
        }
    }

    @Test
    void lineEndsAreComparedAsGitCommitsThemAndALinkAsItsTarget(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        git(root, "init", "-q");
        git(root, "config", "core.autocrlf", "input");
        Files.writeString(root.resolve("t.txt"), "one\ntwo\n");
        git(root, "add", "t.txt");
        git(root, "commit", "-q", "-m", "lf");
        Files.writeString(root.resolve("t.txt"), "one\r\nTWO\r\n");
        Files.createSymbolicLink(root.resolve("link"), Path.of("t.txt"));
        try (Git git = new Git()) {
            ChangeTexts t = git.texts(root, new RepoStatus.FileChange("t.txt", "", RepoStatus.Kind.MODIFIED));
            assertThat(t.after()).isEqualTo("one\nTWO\n");
            ChangeTexts link = git.texts(root, new RepoStatus.FileChange("link", "", RepoStatus.Kind.UNTRACKED));
            assertThat(link.after()).isEqualTo("t.txt");
        }
    }

    @Test
    void parseKeepsAtMostTheBoundAndSaysSo() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < RepoStatus.MAX_CHANGES + 5; i++) {
            out.append("? f").append(String.format("%05d", i)).append('\0');
        }
        RepoStatus s = Git.parse(Path.of("/r"), out.toString().getBytes(StandardCharsets.UTF_8), false).status();
        assertThat(s.changes()).hasSize(RepoStatus.MAX_CHANGES);
        assertThat(s.truncated()).isTrue();
    }
}
