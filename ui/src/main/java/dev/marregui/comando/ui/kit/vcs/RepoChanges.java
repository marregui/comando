package dev.marregui.comando.ui.kit.vcs;

import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.ui.kit.theme.Theme;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The watched git repositories as last read, on the EDT: what the Changes view lists and what the Files tab colours.
 * Looked up by absolute path, a file gives how it changed and a folder whether it holds a change; listeners hear of
 * every new read that differs from the one before.
 */
public final class RepoChanges {

    private List<RepoStatus> repos = List.of();
    private Map<Path, RepoStatus.Kind> files = Map.of();
    private Set<Path> folders = Set.of();
    private Map<Path, RepoStatus> roots = Map.of();
    private final List<Runnable> listeners = new ArrayList<>();

    /** The repositories as read; listeners hear of it when it differs from what was shown. */
    public void set(List<RepoStatus> next) {
        if (next.equals(repos)) {
            return;
        }
        repos = List.copyOf(next);
        Map<Path, RepoStatus.Kind> f = new HashMap<>();
        Set<Path> d = new HashSet<>();
        Map<Path, RepoStatus> top = new HashMap<>();
        for (RepoStatus r : repos) {
            Path root = Path.of(r.root());
            top.put(root.toAbsolutePath().normalize(), r);
            for (RepoStatus.FileChange c : r.changes()) {
                Path file = root.resolve(c.path()).normalize();
                f.put(file, c.kind());
                for (Path p = file.getParent(); p != null && p.startsWith(root); p = p.getParent()) {
                    if (!d.add(p)) {
                        break;
                    }
                }
            }
        }
        files = f;
        folders = d;
        roots = top;
        for (Runnable l : List.copyOf(listeners)) {
            l.run();
        }
    }

    public List<RepoStatus> repos() {
        return repos;
    }

    /**
     * How the file at {@code path} differs from {@code HEAD}; empty when it does not or is in no watched repository.
     */
    public Optional<RepoStatus.Kind> of(Path path) {
        return Optional.ofNullable(files.get(path.toAbsolutePath().normalize()));
    }

    /** Whether the folder at {@code path} holds a changed or untracked file at any depth. */
    public boolean holdsChanges(Path path) {
        return folders.contains(path.toAbsolutePath().normalize());
    }

    /** The repository whose top folder is {@code path}; empty when it is no watched repository's root. */
    public Optional<RepoStatus> repoAt(Path path) {
        return Optional.ofNullable(roots.get(path.toAbsolutePath().normalize()));
    }

    /** The repository holding {@code path}, its top folder or anything under it; the innermost when nested. */
    public Optional<RepoStatus> repoOf(Path path) {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            RepoStatus r = roots.get(p);
            if (r != null) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** The changes of {@code repo} inside {@code folder}, a path relative to its top folder; all of them when empty. */
    public static List<RepoStatus.FileChange> changesIn(RepoStatus repo, String folder) {
        if (folder.isEmpty()) {
            return repo.changes();
        }
        String prefix = folder.endsWith("/") ? folder : folder + "/";
        return repo.changes().stream().filter(c -> c.path().startsWith(prefix)).toList();
    }

    /** Calls {@code listener} after each read that changed something; the returned action removes it. */
    public Runnable listen(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** The colour a file's name takes for how it changed. */
    public static Color color(RepoStatus.Kind kind) {
        return switch (kind) {
            case MODIFIED, RENAMED, COPIED -> Theme.VCS_MODIFIED;
            case ADDED -> Theme.VCS_ADDED;
            case DELETED -> Theme.VCS_DELETED;
            case UNTRACKED -> Theme.tint().body;
            case CONFLICTED -> Theme.VCS_CONFLICTED;
        };
    }

    /** What a change is called, in a tooltip or a title. */
    public static String describe(RepoStatus.Kind kind) {
        return switch (kind) {
            case MODIFIED -> "modified";
            case ADDED -> "added";
            case DELETED -> "deleted";
            case RENAMED -> "renamed";
            case COPIED -> "copied";
            case UNTRACKED -> "untracked";
            case CONFLICTED -> "in conflict";
        };
    }
}
