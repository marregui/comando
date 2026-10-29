package dev.marregui.comando.core.reduce;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.port.Clock;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static dev.marregui.comando.core.reduce.Shared.notice;

/**
 * The Files and Changes tabs' rules: the folders, their trees and repositories, and actions on their files.
 */
final class FolderRules {

    private FolderRules() {
    }

    /** The configured folders named, in the configuration's order; a name no folder has is skipped. */
    private static List<FolderRoot> folders(AppState s, List<String> names) {
        // a group's row reads as a folder of its own (record 118)
        return FolderRoot.withGroupRoots(s.folders()).stream().filter(f -> names.contains(f.name())).toList();
    }

    /**
     * An action on files of the repository at {@code root}: refused, with a notice, when the root is no full path or a
     * path is not relative to it or leaves it; nothing asked for is nothing done.
     */
    private static Transition inRepository(Clock clock, AppState s, String root, List<String> paths, String verb,
                                           Supplier<Effect> effect) {
        if (paths.isEmpty()) {
            return Transition.of(s);
        }
        if (root.isEmpty() || !Path.of(root).isAbsolute()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Cannot " + verb + ": no repository's top folder given", ""));
        }
        for (String p : paths) {
            Path path = Path.of(p);
            if (p.isEmpty() || path.isAbsolute() || path.normalize().startsWith("..") || path.normalize().toString().isEmpty()) {
                return Transition.of(notice(clock, s, Notice.Level.ERROR, "Cannot " + verb + " " + p + ": not a file of the repository; nothing was done", ""));
            }
        }
        return new Transition(s, List.of(effect.get()));
    }

    /**
     * Entries of a deletable folder to the Trash: refused for any other folder, for the folder itself and for a path
     * outside it.
     */
    static Transition deleteFiles(Clock clock, AppState s, Intent.DeleteFiles i) {
        Optional<FolderRoot> folder = s.folder(i.folder());
        if (folder.isEmpty()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "No folder called " + i.folder(), Notice.folder(i.folder())));
        }
        FolderRoot f = folder.get();
        if (!f.deletable()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "Folder " + f.name() + " keeps its files: \"deletable\" in folders.json lets them go to the Trash",
                    Notice.folder(f.name())));
        }
        if (i.paths().isEmpty()) {
            return Transition.of(s);
        }
        Path root = Path.of(f.path()).normalize();
        for (String p : i.paths()) {
            Path path = Path.of(p).normalize();
            if (!path.isAbsolute() || !f.contains(path) || path.equals(root)) {
                return Transition.of(notice(clock, s, Notice.Level.ERROR, (path.equals(root) ? "Folder " + f.name() + " itself stays"
                        : path.getFileName() + " is not in folder " + f.name()) + "; nothing was moved to the Trash", Notice.folder(f.name())));
            }
        }
        return new Transition(s, List.of(new Effect.DeleteFiles(f, i.paths())));
    }

    /**
     * The folders window's list replaces the folders and is saved; a list naming one folder twice changes nothing. A
     * change to {@code folders.json} read while the window was open is replaced, and the notice says so.
     */
    static Transition saveFolders(Clock clock, AppState s, Intent.SaveFolders i) {
        Set<String> names = new HashSet<>();
        for (FolderRoot f : i.folders()) {
            if (!names.add(f.name())) {
                return Transition.of(notice(clock, s, Notice.Level.ERROR, "Two folders are called " + f.name() + ": nothing saved", Notice.folder(f.name())));
            }
        }
        AppState next = s.withFolders(i.folders());
        String text = s.folders().equals(i.base()) ? "Saved the Files tab's folders"
                : "Saved the Files tab's folders, replacing a change to folders.json made while the window was open";
        return new Transition(notice(clock, next, Notice.Level.INFO, text), List.of(new Effect.SaveFolders(next.folders())));
    }

    /** A folder after the others, saved to {@code folders.json}. */
    static Transition addFolder(Clock clock, AppState s, FolderRoot spec) {
        if (s.folder(spec.name()).isPresent()) {
            return Transition.of(notice(clock, s, Notice.Level.ERROR, "A folder called " + spec.name() + " exists", Notice.folder(spec.name())));
        }
        List<FolderRoot> folders = new ArrayList<>(s.folders());
        folders.add(spec);
        AppState next = s.withFolders(folders);
        return new Transition(next, List.of(new Effect.SaveFolders(next.folders())));
    }

    static Transition foldersRead(AppState s, Intent.FoldersRead i) {
        return i.folders().equals(s.folders()) ? Transition.of(s) : Transition.of(s.withFolders(i.folders()));
    }

    static Transition browseFolders(AppState s, Intent.BrowseFolders i) {
        return new Transition(s, List.of(new Effect.ReadFolders(folders(s, i.names()))));
    }

    static Transition watchFolders(AppState s, Intent.WatchFolders i) {
        return new Transition(s, List.of(new Effect.WatchFolders(folders(s, i.names()))));
    }

    static Transition folderLoaded(AppState s, Intent.FolderLoaded i) {
        // a tree read with settings since edited, or of a folder since removed, is dropped
        return FolderRoot.withGroupRoots(s.folders()).contains(i.folder())
                ? new Transition(s, List.of(new Effect.ShowFolder(i.folder(), i.tree()))) : Transition.of(s);
    }

    static Transition reposLoaded(AppState s, Intent.ReposLoaded i) {
        return new Transition(s, List.of(new Effect.ShowRepos(i.repos())));
    }

    static Transition openChange(AppState s, Intent.OpenChange i) {
        return new Transition(s, List.of(new Effect.ReadChange(i.root(), i.change(), i.ask())));
    }

    static Transition changeRead(AppState s, Intent.ChangeRead i) {
        return new Transition(s, List.of(new Effect.ShowChange(i.texts(), i.ask())));
    }

    static Transition rollBack(Clock clock, AppState s, Intent.RollBack i) {
        return inRepository(clock, s, i.root(), i.changes().stream().map(RepoStatus.FileChange::path).toList(), "roll back",
                () -> new Effect.RollBack(i.root(), i.changes()));
    }

    static Transition removeFiles(Clock clock, AppState s, Intent.RemoveFiles i) {
        return inRepository(clock, s, i.root(), i.paths(), "move to the Trash", () -> new Effect.RemoveFiles(i.root(), i.paths()));
    }

    static Transition unstage(Clock clock, AppState s, Intent.Unstage i) {
        return inRepository(clock, s, i.root(), i.paths(), "unstage", () -> new Effect.Unstage(i.root(), i.paths()));
    }

    static Transition openFile(AppState s, Intent.OpenFile i) {
        return new Transition(s, List.of(new Effect.ShowFile(i.session(), i.path(),
                s.session(i.session()).map(x -> x.spec().workDir()).orElse(HomeDir.home()), i.line())));
    }
}
