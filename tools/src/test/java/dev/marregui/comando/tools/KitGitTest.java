package dev.marregui.comando.tools;

import dev.marregui.comando.core.model.KitVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [KitGit] reads a kit checkout's release state and backs up an independent repository to its remote.
///
/// - Version lookup reports the checked-out tag and newer remote tags.
/// - Backup requires a repository root with a remote.
/// - Backup commits changes before pushing and surfaces push failures.
class KitGitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private final KitGit kit = new KitGit();

    @AfterEach
    void close() {
        kit.close();
    }

    /** Runs git in {@code dir}, as the test's own setup; fails the test when git fails. */
    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out.strip();
    }

    /** A checkout at {@code dir} with one commit, pushed to a bare remote {@code origin}; the remote's path. */
    private static Path checkout(Path dir, Path remote) throws Exception {
        Files.createDirectories(dir);
        Files.createDirectories(remote);
        git(remote, "init", "--bare", "-q");
        git(dir, "init", "-q");
        // the repository's own settings: no hook, signing or identity of the user's applies
        Path noHooks = Files.createDirectories(dir.getParent().resolve(dir.getFileName() + "-hooks"));
        git(dir, "config", "core.hooksPath", noHooks.toString());
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "tag.gpgsign", "false");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "user.email", "test@example.com");
        Files.writeString(dir.resolve("README.md"), "first\n");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "first");
        git(dir, "remote", "add", "origin", remote.toString());
        git(dir, "push", "-q", "-u", "origin", "HEAD");
        return remote;
    }

    /// [KitGit#version] reads tag `v1.0.0` and the newer remote `v1.1.0`, preserves the local version when the remote
    /// is unreachable, and returns unknown for a plain directory.
    @Test
    void theKitsVersionAndTheNewerReleasesOfItsRemote(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("kit");
        checkout(root, tmp.resolve("kit.git"));
        git(root, "tag", "v1.0.0");
        Files.writeString(root.resolve("README.md"), "second\n");
        git(root, "commit", "-q", "-am", "second");
        git(root, "tag", "v1.1.0");
        git(root, "push", "-q", "origin", "v1.0.0", "v1.1.0");
        git(root, "checkout", "-q", "v1.0.0");

        KitVersion v = kit.version(root);
        assertThat(v.described()).isEqualTo("v1.0.0");
        assertThat(v.newer()).containsExactly("v1.1.0");
        assertThat(v.problem()).isEmpty();

        git(root, "remote", "set-url", "origin", tmp.resolve("gone.git").toString());
        KitVersion offline = kit.version(root);
        assertThat(offline.described()).isEqualTo("v1.0.0");
        assertThat(offline.newer()).isEmpty();
        assertThat(offline.problem()).startsWith("git ls-remote failed");

        assertThat(kit.version(Files.createDirectories(tmp.resolve("plain")))).as("no checkout").isEqualTo(KitVersion.UNKNOWN);
    }

    /// [KitGit#backUp] commits a changed independent repository with the dated message, pushes it, and later pushes an
    /// unchanged repository without creating a second commit.
    @Test
    void theBrainIsCommittedAndPushedOnlyWhenItIsARepositoryOfItsOwnWithARemote(@TempDir Path tmp) throws Exception {
        Path brain = tmp.resolve("root/BRAIN");
        Path remote = checkout(brain, tmp.resolve("brain.git"));
        assertThat(kit.canBackUp(brain)).isTrue();

        Files.createDirectories(brain.resolve("MEMORY"));
        Files.writeString(brain.resolve("MEMORY/lane.md"), "remembered\n");
        Files.writeString(brain.resolve("README.md"), "second\n");
        String said = kit.backUp(brain, TODAY);
        String head = git(brain, "rev-parse", "--short", "HEAD");
        String subject = "Brain backup 2026-10-26: MEMORY 1 added; README.md 1 changed";
        assertThat(said).isEqualTo("committed " + head + " \"" + subject + "\" and pushed it");
        assertThat(git(remote, "log", "-1", "--format=%s")).isEqualTo(subject);
        assertThat(git(remote, "log", "-1", "--format=%b")).as("one line a file").isEqualTo("added MEMORY/lane.md\nchanged README.md");
        assertThat(git(brain, "status", "--porcelain")).isEmpty();

        assertThat(kit.backUp(brain, TODAY)).as("nothing to commit is no failure").isEqualTo("nothing to commit; pushed " + head);
        assertThat(git(remote, "rev-list", "--count", "HEAD")).isEqualTo("2");
    }

    /// The kit backs up as the brain does, its message naming it; renames and removals are said as such.
    @Test
    void theKitIsBackedUpUnderItsOwnNameWithRenamesAndRemovals(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("root");
        Path remote = checkout(root, tmp.resolve("root.git"));
        Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("bin/tool"), "a\nb\nc\nd\n");
        Files.writeString(root.resolve("old.md"), "x\n");
        git(root, "add", "-A");
        git(root, "commit", "-q", "-m", "setup");
        Files.move(root.resolve("bin/tool"), root.resolve("bin/tool2"));
        Files.delete(root.resolve("old.md"));

        kit.backUp(root, "Kit", TODAY);
        assertThat(git(remote, "log", "-1", "--format=%s")).isEqualTo("Kit backup 2026-10-26: bin 1 renamed; old.md 1 removed");
        assertThat(git(remote, "log", "-1", "--format=%b")).isEqualTo("renamed bin/tool -> bin/tool2\nremoved old.md");
    }

    /// The first line stops naming areas once it is long and counts the rest; the body lists at most [KitGit#LISTED]
    /// files.
    @Test
    void aLongBackupCountsWhatItCannotName() {
        List<KitGit.Change> many = new ArrayList<>();
        for (int i = 0; i < KitGit.LISTED + 5; i++) {
            many.add(new KitGit.Change("added", "area-number-" + i + "/f.md", ""));
        }
        String subject = KitGit.subject("Brain", TODAY, many);
        assertThat(subject.length()).isLessThanOrEqualTo(KitGit.SUBJECT + 20);
        assertThat(subject).startsWith("Brain backup 2026-10-26: area-number-0 1 added; ").endsWith(" more areas");
        String body = KitGit.body(many);
        assertThat(body.lines().count()).isEqualTo(KitGit.LISTED + 1);
        assertThat(body).endsWith("and 5 more");
        assertThat(KitGit.changes("M\ta b.md\nR087\tx\ty\nD\tz\n")).extracting(KitGit.Change::verb).containsExactly("changed", "renamed", "removed");
    }

    /// [KitGit#canBackUp] rejects a repository without a remote, a directory inside another repository, and a missing
    /// directory, so [KitGit#backUp] cannot write through an unintended checkout.
    @Test
    void aBrainWithoutARemoteOrInsideAnotherRepositoryIsNotBackedUp(@TempDir Path tmp) throws Exception {
        Path alone = Files.createDirectories(tmp.resolve("alone"));
        git(alone, "init", "-q");
        assertThat(kit.canBackUp(alone)).as("no remote").isFalse();
        assertThatThrownBy(() -> kit.backUp(alone, TODAY)).isInstanceOf(IOException.class).hasMessageEndingWith("is not a git repository with a remote");

        Path root = tmp.resolve("root");
        checkout(root, tmp.resolve("root.git"));
        Path inside = Files.createDirectories(root.resolve("BRAIN"));
        assertThat(kit.canBackUp(inside)).as("a folder of the kit's checkout, not a repository of its own").isFalse();
        assertThat(kit.canBackUp(tmp.resolve("missing"))).isFalse();
    }

    /// [KitGit#backUp] leaves the dated commit in place but reports an I/O failure when its remote push target is gone.
    @Test
    void aPushThatFailsSaysSo(@TempDir Path tmp) throws Exception {
        Path brain = tmp.resolve("BRAIN");
        checkout(brain, tmp.resolve("brain.git"));
        git(brain, "remote", "set-url", "origin", tmp.resolve("gone.git").toString());
        Files.writeString(brain.resolve("x.md"), "x\n");

        assertThatThrownBy(() -> kit.backUp(brain, TODAY)).isInstanceOf(IOException.class).hasMessageStartingWith("git push failed: ");
        assertThat(git(brain, "log", "-1", "--format=%s")).as("committed before the push").isEqualTo("Brain backup 2026-10-26: x.md 1 added");
    }
}
