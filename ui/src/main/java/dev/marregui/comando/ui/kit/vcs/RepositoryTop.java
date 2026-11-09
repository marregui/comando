package dev.marregui.comando.ui.kit.vcs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a git repository starts above a folder: the files a session wrote and the folders it worked in both name a
 * repository by its top.
 */
public final class RepositoryTop {

    /** How far up from a folder a repository's top is looked for: as deep as a source tree goes, and no further. */
    static final int MAX_UP = 12;

    private RepositoryTop() {
    }

    /** The top of the git repository holding {@code folder}, the nearest folder with a {@code .git}; reads the disk. */
    public static Optional<Path> above(final Path folder) {
        return above(folder, MAX_UP);
    }

    /** {@link #above(Path)} looked for at most {@code levels} folders up, {@code folder} the first. */
    public static Optional<Path> above(final Path folder, final int levels) {
        Path at = folder;
        for (int i = 0; at != null && i < levels; i++, at = at.getParent()) {
            if (Files.exists(at.resolve(".git"))) {
                return Optional.of(at);
            }
        }
        return Optional.empty();
    }
}
