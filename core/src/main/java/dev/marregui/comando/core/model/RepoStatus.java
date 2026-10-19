package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * What a git repository holds that its last commit does not, as {@code git status} reads it: the working tree and the
 * index together against {@code HEAD}, and the files git does not track.
 *
 * @param root      the repository's top folder, absolute
 * @param branch    the branch checked out, or "(detached)", or "" when unknown
 * @param changes   the changed and untracked files, ordered by path
 * @param truncated whether there were more changes than were kept
 * @param error     why the repository could not be read, or ""
 */
public record RepoStatus(String root, String branch, List<FileChange> changes, boolean truncated, String error) {

    /** Changes kept per repository at most. */
    public static final int MAX_CHANGES = 2_000;

    public RepoStatus {
        Objects.requireNonNull(root, "root");
        branch = branch == null ? "" : branch;
        changes = List.copyOf(changes);
        error = error == null ? "" : error;
    }

    public static RepoStatus failed(String root, String error) {
        return new RepoStatus(root, "", List.of(), false, error);
    }

    /** The repository's folder name. */
    public String name() {
        int slash = root.lastIndexOf('/');
        return slash < 0 || slash == root.length() - 1 ? root : root.substring(slash + 1);
    }

    /** How a file differs from {@code HEAD}. */
    public enum Kind {
        MODIFIED, ADDED, DELETED, RENAMED, COPIED, UNTRACKED, CONFLICTED;

        /** The letter git and IDEs mark it with. */
        public String letter() {
            return switch (this) {
                case MODIFIED -> "M";
                case ADDED -> "A";
                case DELETED -> "D";
                case RENAMED -> "R";
                case COPIED -> "C";
                case UNTRACKED -> "?";
                case CONFLICTED -> "U";
            };
        }
    }

    /**
     * A file that differs from {@code HEAD}.
     *
     * @param path its path in the repository, '/'-separated
     * @param from the path it had in {@code HEAD} when renamed or copied, else ""
     * @param kind how it differs
     */
    public record FileChange(String path, String from, Kind kind) {
        public FileChange {
            Objects.requireNonNull(path, "path");
            from = from == null ? "" : from;
            Objects.requireNonNull(kind, "kind");
        }

        /** The path whose {@code HEAD} version it is compared with. */
        public String headPath() {
            return from.isEmpty() ? path : from;
        }
    }
}
