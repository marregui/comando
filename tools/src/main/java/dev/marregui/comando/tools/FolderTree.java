package dev.marregui.comando.tools;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.result.Formats;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * A folder of the folder tabs (Kit, Brain, Scratchpad) as a tree, every level ordered the same way, names ignoring
 * case: folders not named after a date first, by name; then date-named folders ({@code yyyy-MM-dd}) oldest first; then
 * files by name. The folder's index file, when it sits at the top level, comes before everything else there. A file
 * links to itself; a folder has no link and its label has no trailing slash. Hidden entries, the folder's excluded
 * names and the entries a caller skips (those git ignores) are left out; a symbolic link to a folder is listed as a
 * folder and not followed into. A subfolder that cannot be read says so and the rest of the tree stands. The depth is
 * bounded, and so is the read: at most {@link #MAX_LISTED} names are listed and {@link #MAX_ENTRIES} entries kept in
 * the whole tree, so a folder read every few seconds while open costs a bounded read however large it is. Past a bound
 * a folder ends with {@link ViewSpec.TreeNode#MORE}. A folder the folder tabs show in part is read only as deep as it
 * shows: inside a subfolder that is not open, nothing is listed. The tree's root is labelled with the folder's name and
 * details its path.
 */
final class FolderTree {

    static final int MAX_DEPTH = 12;
    /** Entries kept in the whole tree at most; past it a folder ends with {@link ViewSpec.TreeNode#MORE}. */
    static final int MAX_ENTRIES = 20_000;
    /**
     * Names listed in the whole tree at most, hidden and left-out ones included: a folder of millions of hidden entries
     * costs a bounded listing too.
     */
    static final int MAX_LISTED = 4 * MAX_ENTRIES;

    /** Lists a directory; {@link Files#list} but for tests that count what is read. */
    interface Lister {
        Stream<Path> list(Path dir) throws IOException;
    }

    /** What the whole tree may still take: entries shown and names listed. */
    private static final class Budget {
        int entries = MAX_ENTRIES;
        int listed = MAX_LISTED;
    }

    /** The error mark the sidebar's tree shows in red, then why. */
    static final String UNREADABLE = "✗ cannot be read: ";

    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private FolderTree() {
    }

    /** The folder's tree; throws when the folder itself cannot be read. */
    static ViewSpec.TreeNode tree(final FolderRoot folder) throws IOException {
        return tree(folder, Files::list, p -> false);
    }

    static ViewSpec.TreeNode tree(final FolderRoot folder, final Lister lister) throws IOException {
        return tree(folder, lister, p -> false);
    }

    /** The folder's tree without the entries {@code skip} names, such as those git ignores. */
    static ViewSpec.TreeNode tree(final FolderRoot folder, final Lister lister, final Predicate<Path> skip) throws IOException {
        return tree(folder, lister, skip, WHOLE);
    }

    /** Reads every subfolder: {@link #tree(FolderRoot, Lister, Predicate, Predicate)} for a folder read whole. */
    static final Predicate<Path> WHOLE = p -> true;

    /**
     * The folder's tree as deep as {@code open} asks: the folder's own entries, and the entries of each subfolder of a
     * folder read so far that is open ({@code open} true of its path) or the top folder itself, so every subfolder the
     * folder tabs show has its entries and its count. A subfolder inside one that is not open is not listed: all it
     * holds is a {@link ViewSpec.TreeNode#MORE} entry detailed {@link ViewSpec.TreeNode#NOT_READ}. {@link #WHOLE} reads
     * every level.
     */
    static ViewSpec.TreeNode tree(final FolderRoot folder, final Lister lister, final Predicate<Path> skip, final Predicate<Path> open)
            throws IOException {
        return new ViewSpec.TreeNode(folder.name(), HomeDir.shorten(folder.path()),
                children(folder, Path.of(folder.path()), 1, new Budget(), lister, skip, open));
    }

    /** What a subfolder not read holds: one entry saying so. */
    private static final List<ViewSpec.TreeNode> NOT_READ = List.of(new ViewSpec.TreeNode(ViewSpec.TreeNode.MORE, ViewSpec.TreeNode.NOT_READ, List.of()));

    /** What a folder that cannot be read shows: its name and path, and one entry saying why, marked as an error. */
    static ViewSpec.TreeNode unreadable(final FolderRoot folder, final Exception e) {
        return new ViewSpec.TreeNode(folder.name(), HomeDir.shorten(folder.path()), List.of(error(e)));
    }

    /** Whether {@code tree} is what {@link #unreadable} made. */
    static boolean isUnreadable(final ViewSpec.TreeNode tree) {
        return tree.children().size() == 1 && tree.children().getFirst().label().startsWith(UNREADABLE);
    }

    private static ViewSpec.TreeNode error(final Exception e) {
        final String why = e instanceof NoSuchFileException ? "no such folder"
                : e instanceof AccessDeniedException ? "permission denied"
                : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ViewSpec.TreeNode(UNREADABLE + why, "", List.of());
    }

    /**
     * The entries of {@code dir}, ordered, within what {@code budget} leaves: no more names are listed than it allows
     * (plus one, to know there are more), and no more entries are looked at or sorted than it shows.
     */
    private static List<ViewSpec.TreeNode> children(final FolderRoot folder, final Path dir, final int depth, final Budget budget, final Lister lister,
                                                    final Predicate<Path> skip, final Predicate<Path> open) throws IOException {
        if (budget.listed <= 0 || budget.entries <= 0) {
            // the tree's bound is spent: the folder is not listed at all
            return List.of(new ViewSpec.TreeNode(ViewSpec.TreeNode.MORE, "more entries than the tab reads", List.of()));
        }
        final int canList = budget.listed;
        List<Path> listed;
        try (final Stream<Path> list = lister.list(dir)) {
            listed = list.limit(canList + 1L).toList();
        }
        boolean more = listed.size() > canList;
        listed = listed.subList(0, Math.min(listed.size(), canList));
        budget.listed -= listed.size();
        final List<Path> shown = listed.stream().filter(p -> {
            final String name = p.getFileName().toString();
            return !name.startsWith(".") && !folder.excludes(name) && !skip.test(p);
        }).toList();
        final int canShow = Math.min(shown.size(), Math.max(budget.entries, 0));
        more |= shown.size() > canShow;
        final String index = depth == 1 ? folder.index() : "";
        final List<Entry> entries = shown.subList(0, canShow).stream().map(p -> Entry.of(p, index)).sorted(ORDER).toList();
        budget.entries -= entries.size();
        final List<ViewSpec.TreeNode> out = new ArrayList<>(entries.size() + 1);
        // this folder's subfolders show, with their entries, when it is the top folder or open; else they are not read
        final boolean listsSubfolders = depth == 1 || open.test(dir);
        for (final Entry e : entries) {
            if (e.kind() == Kind.FILE) {
                out.add(new ViewSpec.TreeNode(e.name(), e.size(), ViewSpec.TreeNode.fileLink(e.path().toString(), 0), List.of()));
            } else if (e.kind() == Kind.LINKED_FOLDER) {
                out.add(new ViewSpec.TreeNode(e.name(), "link to a folder, not followed", List.of()));
            } else if (depth >= MAX_DEPTH) {
                out.add(new ViewSpec.TreeNode(e.name(), "not read: " + MAX_DEPTH + " levels deep",
                        List.of(new ViewSpec.TreeNode(ViewSpec.TreeNode.MORE, "deeper than the tab reads", List.of()))));
            } else if (!listsSubfolders) {
                out.add(new ViewSpec.TreeNode(e.name(), "", NOT_READ));
            } else {
                List<ViewSpec.TreeNode> kids;
                try {
                    kids = children(folder, e.path(), depth + 1, budget, lister, skip, open);
                } catch (final IOException ex) {
                    kids = List.of(error(ex));
                }
                out.add(new ViewSpec.TreeNode(e.name(), entries(kids), kids));
            }
        }
        if (more) {
            out.add(new ViewSpec.TreeNode(ViewSpec.TreeNode.MORE, "more entries than the tab reads", List.of()));
        }
        return out;
    }

    /**
     * "3 entries", "1 entry"; "5 entries or more" for a folder read in part; "cannot be read" for one that could not.
     */
    private static String entries(final List<ViewSpec.TreeNode> kids) {
        if (kids.size() == 1 && kids.getFirst().label().startsWith(UNREADABLE)) {
            return "cannot be read";
        }
        final boolean part = !kids.isEmpty() && kids.getLast().label().equals(ViewSpec.TreeNode.MORE);
        final int n = part ? kids.size() - 1 : kids.size();
        return (n == 1 ? "1 entry" : n + " entries") + (part ? " or more" : "");
    }

    /** What an entry is: a folder, a symbolic link to a folder (listed, not followed), or a file. */
    enum Kind {
        FOLDER, LINKED_FOLDER, FILE
    }

    /**
     * An entry with what the order and its node need, read once and with one look at the file system (a symbolic link
     * takes a second): the tree is read every few seconds and a sort compares each entry many times, so neither the
     * file system nor a pattern is asked again per comparison.
     *
     * @param rank -1 for the index file, 0 for a folder not named after a date, 1 for a date-named folder, 2 for any
     *             other file
     * @param size a file's size as the tree shows it, empty for a folder or a file that cannot be read
     */
    record Entry(Path path, String name, String lower, Kind kind, int rank, String size) {

        static Entry of(final Path p) {
            return of(p, "");
        }

        /** {@code index} names the folder's index file when {@code p} is at its top level, else empty. */
        static Entry of(final Path p, final String index) {
            final String name = p.getFileName().toString();
            BasicFileAttributes a;
            try {
                a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (final IOException e) {
                a = null; // gone since it was listed, or not readable: a file of no known size
            }
            final Kind kind = a != null && a.isDirectory() ? Kind.FOLDER
                    : a != null && a.isSymbolicLink() && Files.isDirectory(p) ? Kind.LINKED_FOLDER : Kind.FILE;
            final int rank = kind != Kind.FILE ? (DAY.matcher(name).matches() ? 1 : 0) : name.equals(index) ? -1 : 2;
            final String size = kind != Kind.FILE || a == null ? "" : a.isSymbolicLink() ? FolderTree.size(p) : Formats.bytes(a.size());
            return new Entry(p, name, name.toLowerCase(Locale.ROOT), kind, rank, size);
        }
    }

    /**
     * The index file, then folders not named after a date, then date-named folders oldest first, then files; names
     * ignoring case.
     */
    static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::rank).thenComparing(Entry::lower).thenComparing(Entry::name);

    /** The size of the file {@code p} leads to, as the tree shows it; empty when it cannot be read. */
    private static String size(final Path p) {
        try {
            return Formats.bytes(Files.size(p));
        } catch (final IOException e) {
            return "";
        }
    }
}
