package dev.marregui.comando.ui.kit.desktop;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the file a relative path names when it is not where the path is read from (record 111): a session writes
 * {@code notes/07-plan.md} for a file deep under its folder, or under a folder of the Files tab. The first root that
 * holds a file whose path ends with the one written gives it; of several there, the newest. The walk is bounded,
 * skips build and version-control folders, and runs off the EDT.
 */
public final class FileSearch {

    /** How deep under a root a match is looked for. */
    static final int MAX_DEPTH = 8;
    /** How many files and folders a search visits at most, over every root. */
    static final int MAX_VISITED = 100_000;
    private static final Set<String> SKIPPED = Set.of(".git", "node_modules", "build", ".gradle", "target", "out", ".idea");

    private FileSearch() {
    }

    /** The file under one of {@code roots}, in order, whose path ends with {@code relative}; empty when none is. */
    public static Optional<Path> bySuffix(final String relative, final List<Path> roots) {
        Path suffix;
        try {
            suffix = Path.of(relative).normalize();
        } catch (final InvalidPathException e) {
            return Optional.empty();
        }
        if (suffix.isAbsolute() || suffix.getNameCount() == 0 || suffix.startsWith("..")) {
            return Optional.empty();
        }
        final int[] visited = {0};
        for (final Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            final Optional<Path> found = under(root.toAbsolutePath().normalize(), suffix, visited);
            if (found.isPresent()) {
                return found;
            }
            if (visited[0] >= MAX_VISITED) {
                break;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> under(final Path root, final Path suffix, final int[] visited) {
        final Path[] best = {null};
        final FileTime[] bestTime = {null};
        final String last = suffix.getFileName().toString();
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    if (++visited[0] >= MAX_VISITED) {
                        return FileVisitResult.TERMINATE;
                    }
                    return !dir.equals(root) && SKIPPED.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (++visited[0] >= MAX_VISITED) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isRegularFile() && file.getFileName().toString().equals(last) && file.endsWith(suffix)
                            && (bestTime[0] == null || attrs.lastModifiedTime().compareTo(bestTime[0]) > 0)) {
                        best[0] = file;
                        bestTime[0] = attrs.lastModifiedTime();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            // a root that cannot be walked holds nothing found
        }
        return Optional.ofNullable(best[0]);
    }
}
