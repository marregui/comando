package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import dev.marregui.comando.core.files.Mapped;

/** Reads the current branch from {@code .git/HEAD} without running git. */
public final class GitBranch {

    private GitBranch() {
    }

    /** The branch of the repository holding {@code dir}, a short commit id when detached, or empty. */
    public static String of(final Path dir) {
        for (Path d = dir.toAbsolutePath(); d != null; d = d.getParent()) {
            final Path git = d.resolve(".git");
            try {
                if (Files.isDirectory(git)) {
                    return parseHead(Mapped.text(git.resolve("HEAD")));
                }
                if (Files.isRegularFile(git)) { // a worktree or submodule: "gitdir: <path>"
                    final String pointer = Mapped.text(git).strip();
                    if (pointer.startsWith("gitdir:")) {
                        final Path gitDir = d.resolve(pointer.substring("gitdir:".length()).strip());
                        return parseHead(Mapped.text(gitDir.resolve("HEAD")));
                    }
                }
            } catch (final IOException e) {
                return "";
            }
        }
        return "";
    }

    /** The top folder of the repository holding {@code dir}, which need not exist; empty outside a repository. */
    static Optional<Path> top(final Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve(".git"))) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    static String parseHead(final String head) {
        final String h = head.strip();
        if (h.startsWith("ref: refs/heads/")) {
            return h.substring("ref: refs/heads/".length());
        }
        return h.length() >= 8 ? h.substring(0, 8) : h;
    }
}
