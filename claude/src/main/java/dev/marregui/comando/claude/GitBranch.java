package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
                    return parseHead(Files.readString(git.resolve("HEAD")));
                }
                if (Files.isRegularFile(git)) { // a worktree or submodule: "gitdir: <path>"
                    final String pointer = Files.readString(git).strip();
                    if (pointer.startsWith("gitdir:")) {
                        final Path gitDir = d.resolve(pointer.substring("gitdir:".length()).strip());
                        return parseHead(Files.readString(gitDir.resolve("HEAD")));
                    }
                }
            } catch (final IOException e) {
                return "";
            }
        }
        return "";
    }

    static String parseHead(final String head) {
        final String h = head.strip();
        if (h.startsWith("ref: refs/heads/")) {
            return h.substring("ref: refs/heads/".length());
        }
        return h.length() >= 8 ? h.substring(0, 8) : h;
    }
}
