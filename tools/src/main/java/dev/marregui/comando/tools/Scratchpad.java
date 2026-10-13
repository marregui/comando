package dev.marregui.comando.tools;

import dev.marregui.comando.core.view.ViewSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The scratchpad as a tree: documents at the top level, then day folders newest first; in each day the
 * READ- documents in reading order, then ALPHA.md, then the rest, with {@code work/} last. Each file
 * links to itself.
 */
final class Scratchpad {

    private static final int MAX_DEPTH = 3;

    private Scratchpad() {
    }

    static ViewSpec.TreeNode tree(Path root) throws IOException {
        return new ViewSpec.TreeNode(root.getFileName().toString(), root.toString(), children(root, 1));
    }

    private static List<ViewSpec.TreeNode> children(Path dir, int depth) throws IOException {
        List<Path> entries;
        try (Stream<Path> list = Files.list(dir)) {
            entries = list.filter(p -> !p.getFileName().toString().startsWith(".")).sorted(order(depth)).toList();
        }
        List<ViewSpec.TreeNode> out = new ArrayList<>();
        for (Path p : entries) {
            String name = p.getFileName().toString();
            if (Files.isDirectory(p)) {
                if (depth < MAX_DEPTH) {
                    List<ViewSpec.TreeNode> kids = children(p, depth + 1);
                    out.add(new ViewSpec.TreeNode(name + "/", kids.size() + " entries", kids));
                }
            } else {
                out.add(new ViewSpec.TreeNode(name, size(p), "comando-file:" + p + "#0", List.of()));
            }
        }
        return out;
    }

    /** Top level: files first, then day folders newest first; inside a day: READ-, ALPHA.md, others, folders. */
    private static Comparator<Path> order(int depth) {
        Comparator<Path> filesFirst = Comparator.comparing(Files::isDirectory);
        if (depth == 1) {
            // day folders newest first, then other folders by name
            return filesFirst.thenComparing((Path p) -> isDay(p) ? 0 : 1)
                    .thenComparing((a, b) -> isDay(a) && isDay(b) ? b.getFileName().compareTo(a.getFileName()) : a.getFileName().compareTo(b.getFileName()));
        }
        return filesFirst.thenComparing((Path p) -> {
            String n = p.getFileName().toString();
            return n.startsWith("READ-") ? 0 : n.equals("ALPHA.md") ? 1 : 2;
        }).thenComparing(p -> p.getFileName().toString());
    }

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
