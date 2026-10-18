package dev.marregui.comando.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A folder the Files tab shows at its top level, configured like a connection: a name, the folder, the file its name
 * opens (its index, such as {@code ALPHA.md}), the names of entries left out besides {@link #ALWAYS_EXCLUDED}, the
 * icon of its row, whether its entries may go to the Trash and the file each of its folders lists them in. The
 * folders are configured in {@code folders.json}; the Files tab shows them, and moves entries of a deletable folder to
 * the Trash.
 *
 * <p>The record takes any name and path, as read from a hand-edited {@code folders.json}; {@link
 * dev.marregui.comando.core.resource.FolderKind} holds the rules (a name, a full path, an index inside the folder), and
 * the store applies them to each entry it reads, so one bad entry is left out rather than the whole file.</p>
 *
 * @param index    a path relative to the folder, empty when none
 * @param excludes folder or file names left out wherever they appear, matched ignoring case
 * @param icon      one of {@link #ICONS}; {@link #FOLDER} when none is set
 * @param deletable whether the Files tab moves the folder's files and subfolders to the Trash; never the folder itself
 * @param listedIn  a file name, such as {@code ALPHA.md}, that lists the entries beside it: an entry moved to the Trash
 *                  leaves the nearest such file above it, without its own lines; empty when none
 */
public record FolderRoot(String name, String path, String index, List<String> excludes, String icon, boolean deletable, String listedIn) {

    /** The plain folder icon, the default. */
    public static final String FOLDER = "folder";
    /** The icons a folder's row may have. */
    public static final List<String> ICONS = List.of(FOLDER, "scratchpad", "memory", "knowledge", "skills", "plugin");

    /** Build output and dependencies, left out of every folder: large, generated, and never a document. */
    public static final Set<String> ALWAYS_EXCLUDED = Set.of("node_modules", "build", "target", "__pycache__", "out");

    public FolderRoot {
        // a missing field, as from a hand-edited file, is empty: the folder's rules then leave the entry out, not the file
        name = name == null ? "" : name;
        path = path == null ? "" : path;
        index = index == null ? "" : index.strip();
        excludes = excludes == null ? List.of() : excludes.stream().map(String::strip).filter(e -> !e.isEmpty()).toList();
        icon = icon == null || icon.isBlank() ? FOLDER : icon.strip();
        listedIn = listedIn == null ? "" : listedIn.strip();
    }

    /** A folder whose entries stay where they are. */
    public FolderRoot(String name, String path, String index, List<String> excludes, String icon) {
        this(name, path, index, excludes, icon, false, "");
    }

    /** A folder with the plain icon. */
    public FolderRoot(String name, String path, String index, List<String> excludes) {
        this(name, path, index, excludes, FOLDER);
    }

    /**
     * Claude Code's own data under {@code ~/.claude} that a user does not read as documents: transcripts, history,
     * caches and per-session state. Left out of the default folder, so its tree stays small enough to read every
     * few seconds.
     */
    public static final List<String> CLAUDE_STATE = List.of("projects", "file-history", "shell-snapshots", "session-env", "sessions",
            "todos", "statsig", "telemetry", "debug", "cache", "paste-cache", "backups", "ide", "downloads");

    /** A user who never configured a folder sees Claude Code's own ({@link HomeDir#claudeDir}), opening its {@code CLAUDE.md}. */
    public static List<FolderRoot> defaults(Path claudeDir) {
        return List.of(new FolderRoot("Claude", claudeDir.toString(), "CLAUDE.md", CLAUDE_STATE));
    }

    /** Whether an entry called {@code entry} is left out of the tree. */
    public boolean excludes(String entry) {
        String lower = entry.toLowerCase(Locale.ROOT);
        return ALWAYS_EXCLUDED.contains(lower) || excludes.stream().anyMatch(e -> e.toLowerCase(Locale.ROOT).equals(lower));
    }

    /** The index file, when one is set. */
    public Optional<Path> indexFile() {
        return index.isEmpty() ? Optional.empty() : Optional.of(Path.of(path).resolve(index));
    }

    /** Whether {@code file}, a full path, is in this folder. */
    public boolean contains(Path file) {
        return !path.isEmpty() && file.normalize().startsWith(Path.of(path).normalize());
    }

    public FolderRoot withName(String newName) {
        return new FolderRoot(newName, path, index, excludes, icon, deletable, listedIn);
    }

    /** A folder whose entries go to the Trash from the Files tab, each leaving its lines in {@code listing}, if set. */
    public FolderRoot withTrash(String listing) {
        return new FolderRoot(name, path, index, excludes, icon, true, listing);
    }
}
