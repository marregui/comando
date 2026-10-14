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
import java.util.stream.Stream;

/**
 * The scratchpad as a tree, every level ordered the same way: folders not named after a date first, by name; then
 * date-named folders ({@code yyyy-MM-dd}) oldest first; then files by name, ignoring case. A file links to itself;
 * a folder has no link and its label has no trailing slash. Hidden entries are left out, symbolic links are not
 * followed into, and the depth is bounded.
 */
final class Scratchpad {

    static final int MAX_DEPTH = 12;

    private Scratchpad() {
    }

    static ViewSpec.TreeNode tree(Path root) throws IOException {
        return new ViewSpec.TreeNode(root.getFileName().toString(), root.toString(), children(root, 1));
    }

    private static List<ViewSpec.TreeNode> children(Path dir, int depth) throws IOException {
        List<Path> entries;
        try (Stream<Path> list = Files.list(dir)) {
            entries = list.filter(p -> !p.getFileName().toString().startsWith(".")).sorted(ORDER).toList();
        }
        List<ViewSpec.TreeNode> out = new ArrayList<>(entries.size());
        for (Path p : entries) {
            String name = p.getFileName().toString();
            if (isFolder(p)) {
                List<ViewSpec.TreeNode> kids = depth < MAX_DEPTH ? children(p, depth + 1) : List.of();
                out.add(new ViewSpec.TreeNode(name, kids.size() == 1 ? "1 entry" : kids.size() + " entries", kids));
            } else {
                out.add(new ViewSpec.TreeNode(name, size(p), "comando-file:" + p + "#0", List.of()));
            }
        }
        return out;
    }

    private static boolean isFolder(Path p) {
        return Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
    }

    /** Folders not named after a date, then date-named folders oldest first, then files; names ignoring case. */
    static final Comparator<Path> ORDER = Comparator.comparingInt((Path p) -> !isFolder(p) ? 2 : isDay(p) ? 1 : 0)
            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
            .thenComparing(p -> p.getFileName().toString());

    private static boolean isDay(Path p) {
        return p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private static String size(Path p) {
        try {
            long b = Files.size(p);
            return b < 1024 ? b + " B" : b < 1024 * 1024 ? (b / 1024) + " kB" : (b / (1024 * 1024)) + " MB";
        } catch (IOException e) {
            return "";
        }
    }
}
