package dev.marregui.comando.tools;

import dev.marregui.comando.core.view.ViewSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The scratchpad as a tree, every level ordered the same way: folders not named after a date first, by name; then
 * date-named folders ({@code yyyy-MM-dd}) oldest first; then files by name, ignoring case. A file links to itself;
 * a folder has no link and its label has no trailing slash. Hidden entries are left out, symbolic links are not
 * followed into, and the depth is bounded.
 */
final class Scratchpad {

    static final int MAX_DEPTH = 12;

    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private Scratchpad() {
    }

    static ViewSpec.TreeNode tree(Path root) throws IOException {
        return new ViewSpec.TreeNode(root.getFileName().toString(), root.toString(), children(root, 1));
    }

    private static List<ViewSpec.TreeNode> children(Path dir, int depth) throws IOException {
        List<Entry> entries;
        try (Stream<Path> list = Files.list(dir)) {
            entries = list.filter(p -> !p.getFileName().toString().startsWith(".")).map(Entry::of).sorted(ORDER).toList();
        }
        List<ViewSpec.TreeNode> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.folder()) {
                List<ViewSpec.TreeNode> kids = depth < MAX_DEPTH ? children(e.path(), depth + 1) : List.of();
                out.add(new ViewSpec.TreeNode(e.name(), kids.size() == 1 ? "1 entry" : kids.size() + " entries", kids));
            } else {
                out.add(new ViewSpec.TreeNode(e.name(), size(e.path()), "comando-file:" + e.path() + "#0", List.of()));
            }
        }
        return out;
    }

    /**
     * An entry with what the order needs, read once: the tree is read every few seconds and a sort compares each
     * entry many times, so neither the file system nor a pattern is asked again per comparison.
     *
     * @param rank 0 for a folder not named after a date, 1 for a date-named folder, 2 for a file
     */
    record Entry(Path path, String name, String lower, boolean folder, int rank) {

        static Entry of(Path p) {
            String name = p.getFileName().toString();
            boolean folder = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
            return new Entry(p, name, name.toLowerCase(Locale.ROOT), folder, !folder ? 2 : DAY.matcher(name).matches() ? 1 : 0);
        }
    }

    /** Folders not named after a date, then date-named folders oldest first, then files; names ignoring case. */
    static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::rank).thenComparing(Entry::lower).thenComparing(Entry::name);

    private static String size(Path p) {
        try {
            long b = Files.size(p);
            return b < 1024 ? b + " B" : b < 1024 * 1024 ? (b / 1024) + " kB" : (b / (1024 * 1024)) + " MB";
        } catch (IOException e) {
            return "";
        }
    }
}
