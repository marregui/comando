package dev.marregui.comando.ui.widgets.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.format.Labels;

/**
 * The rows of the Search view, built afresh for each batch of hits: a row per Files-tab folder searched, in the order
 * its first hit came, its files by folder (a chain of folders holding only one folder is one row, as in the Changes
 * view), a row per line found under its file; last, the conversations, a row per conversation and one per message
 * found in it. Every row that opens a hit is linked to it in the targets.
 */
final class SearchTree {

    /** The label of the row that holds the conversations found, below the folders. */
    static final String CONVERSATIONS = "Conversations";

    /** The scheme of a hit row's link, followed by the hit's index in the hits built from. */
    static final String HIT = "hit:";
    /** The scheme of a file or a conversation row's link, followed by its source. */
    static final String SOURCE = "src:";

    private SearchTree() {
    }

    /**
     * The rows of {@code hits}, under a root the view hides. {@code targets} is cleared, then maps every row's link to
     * the hit it opens: a hit row's ({@code "hit:"} and the hit's index in {@code hits}) to its hit, a file or a
     * conversation row's ({@code "src:"} and its source) to its first hit, a file's name hit before its lines.
     */
    static ViewSpec.TreeNode build(final List<SearchHit> hits, final Map<String, SearchHit> targets) {
        targets.clear();
        final Map<String, Place> places = new LinkedHashMap<>();
        final Map<String, Leaf> leaves = new HashMap<>();
        final Map<String, List<Found>> talks = new LinkedHashMap<>();
        int talked = 0;
        for (int i = 0; i < hits.size(); i++) {
            final Found found = new Found(i, hits.get(i));
            switch (found.hit().kind()) {
                case NAME, FILE -> place(found, places, leaves);
                case CONVERSATION -> {
                    talks.computeIfAbsent(found.hit().conversation(), _ -> new ArrayList<>()).add(found);
                    talked++;
                }
            }
        }
        final Map<String, Integer> groups = new HashMap<>();
        for (final Place p : places.values()) {
            if (p.group != null) {
                groups.merge(p.group, 1, Integer::sum);
            }
        }
        final List<ViewSpec.TreeNode> rows = new ArrayList<>(places.size() + 1);
        for (final Place p : places.values()) {
            final String label = p.group == null || groups.get(p.group) > 1 ? Labels.shortPath(p.path) : p.group;
            final String detail = hits(tally(p.top)) + " in " + count(p.files, "file");
            rows.add(new ViewSpec.TreeNode(label, detail, nodes(p.top, targets)));
        }
        if (!talks.isEmpty()) {
            final String detail = hits(talked) + " in " + count(talks.size(), "conversation");
            rows.add(new ViewSpec.TreeNode(CONVERSATIONS, detail, conversations(talks, targets)));
        }
        return new ViewSpec.TreeNode(ViewSpec.Search.TITLE, "", rows);
    }

    /**
     * A row's key: the labels below the root joined by '/', so a chain of folders shown as one row ("a/b/c") and the
     * same folders shown apart ("a/b", then "c") have the same key.
     */
    static String key(final TreePath path) {
        final StringBuilder out = new StringBuilder();
        for (int i = 1; i < path.getPathCount(); i++) {
            if (path.getPathComponent(i) instanceof DefaultMutableTreeNode d
                    && d.getUserObject() instanceof ViewSpec.TreeNode n) {
                out.append(i > 1 ? "/" : "").append(n.label());
            }
        }
        return out.toString();
    }

    static List<SearchHit> sameSource(final List<SearchHit> hits, final SearchHit hit) {
        return hits.stream().filter(h -> h.source().equals(hit.source())).toList();
    }

    /**
     * Every hit of the rows under {@code root}, in the order the tree shows them, top to bottom, each once: a hit at
     * its row, a file's name hit, which has no row of its own, at its file's row.
     */
    static List<SearchHit> order(final ViewSpec.TreeNode root, final Map<String, SearchHit> targets) {
        final List<SearchHit> out = new ArrayList<>(targets.size());
        walk(root, targets, out);
        return out;
    }

    private static void place(final Found found, final Map<String, Place> places, final Map<String, Leaf> leaves) {
        final SearchHit hit = found.hit();
        final Leaf known = leaves.get(hit.source());
        final Leaf leaf = known != null ? known : leaf(hit, places);
        if (known == null) {
            leaves.put(hit.source(), leaf);
        }
        if (hit.kind() != SearchHit.Kind.NAME) {
            leaf.lines.add(found);
        } else if (leaf.name == null) {
            leaf.name = found;
        }
    }

    /** The row of the file of {@code hit}, made in its folder; a source outside its root goes under its parent. */
    private static Leaf leaf(final SearchHit hit, final Map<String, Place> places) {
        final String source = hit.source();
        final int below = below(hit.root(), source);
        final int slash = source.lastIndexOf('/');
        final String top = below >= 0 ? hit.root() : slash > 0 ? source.substring(0, slash) : slash == 0 ? "/" : "";
        final Place place = places.computeIfAbsent(top, Place::new);
        if (below >= 0 && place.group == null) {
            place.group = hit.group();
        }
        place.files++;
        final String[] parts = (below >= 0 ? source.substring(below) : source.substring(slash + 1)).split("/");
        Dir d = place.top;
        for (int i = 0; i < parts.length - 1; i++) {
            d = d.dirs.computeIfAbsent(parts[i], _ -> new Dir());
        }
        final Leaf leaf = new Leaf(source);
        d.files.put(parts[parts.length - 1], leaf);
        return leaf;
    }

    /** Where {@code source}'s path below {@code root} starts, or -1 when {@code root} does not hold it. */
    private static int below(final String root, final String source) {
        if (root.isEmpty() || !source.startsWith(root)) {
            return -1;
        }
        if (root.endsWith("/")) {
            return source.length() > root.length() ? root.length() : -1;
        }
        return source.length() > root.length() + 1 && source.charAt(root.length()) == '/' ? root.length() + 1 : -1;
    }

    /** The hits under {@code dir}, kept in it and in each folder below it. */
    private static int tally(final Dir dir) {
        int n = 0;
        for (final Dir d : dir.dirs.values()) {
            n += tally(d);
        }
        for (final Leaf l : dir.files.values()) {
            n += l.lines.size() + (l.name == null ? 0 : 1);
        }
        dir.count = n;
        return n;
    }

    /** {@code dir} as folders and files, folders first; a folder holding only one folder joins it in one row. */
    private static List<ViewSpec.TreeNode> nodes(final Dir dir, final Map<String, SearchHit> targets) {
        final List<ViewSpec.TreeNode> out = new ArrayList<>(dir.dirs.size() + dir.files.size());
        for (final Map.Entry<String, Dir> e : dir.dirs.entrySet()) {
            final StringBuilder label = new StringBuilder(e.getKey());
            Dir d = e.getValue();
            while (d.files.isEmpty() && d.dirs.size() == 1) {
                final Map.Entry<String, Dir> only = d.dirs.firstEntry();
                label.append('/').append(only.getKey());
                d = only.getValue();
            }
            out.add(new ViewSpec.TreeNode(label.toString(), hits(d.count), nodes(d, targets)));
        }
        for (final Map.Entry<String, Leaf> e : dir.files.entrySet()) {
            out.add(file(e.getKey(), e.getValue(), targets));
        }
        return out;
    }

    private static ViewSpec.TreeNode file(final String name, final Leaf leaf, final Map<String, SearchHit> targets) {
        leaf.lines.sort(Comparator.comparingInt(f -> f.hit().line()));
        final List<ViewSpec.TreeNode> rows = new ArrayList<>(leaf.lines.size());
        for (final Found f : leaf.lines) {
            final SearchHit hit = f.hit();
            final String at = "line " + hit.line();
            rows.add(row(f, hit.heading().isEmpty() ? at : hit.heading() + " · " + at, targets));
        }
        final String link = SOURCE + leaf.source;
        targets.put(link, (leaf.name != null ? leaf.name : leaf.lines.getFirst()).hit());
        final String detail = leaf.name == null ? hits(leaf.lines.size())
                : leaf.lines.isEmpty() ? "name matches" : "name matches · " + hits(leaf.lines.size());
        return new ViewSpec.TreeNode(name, detail, link, rows);
    }

    private static List<ViewSpec.TreeNode> conversations(
            final Map<String, List<Found>> talks,
            final Map<String, SearchHit> targets
    ) {
        final List<ViewSpec.TreeNode> out = new ArrayList<>(talks.size());
        for (final List<Found> found : talks.values()) {
            final SearchHit first = found.getFirst().hit();
            // the conversation's id, not its file: a folder of the Files tab may hold the transcripts as files too
            final String link = SOURCE + first.conversation();
            targets.put(link, first);
            final List<ViewSpec.TreeNode> rows = new ArrayList<>(found.size());
            for (final Found f : found) {
                rows.add(row(f, f.hit().heading(), targets));
            }
            out.add(new ViewSpec.TreeNode(first.group(), hits(found.size()), link, rows));
        }
        return out;
    }

    private static ViewSpec.TreeNode row(final Found found, final String detail, final Map<String, SearchHit> targets) {
        final String link = HIT + found.index();
        targets.put(link, found.hit());
        return new ViewSpec.TreeNode(found.hit().snippet().strip(), detail, link, List.of());
    }

    private static void walk(
            final ViewSpec.TreeNode node,
            final Map<String, SearchHit> targets,
            final List<SearchHit> out
    ) {
        final SearchHit hit = targets.get(node.link());
        if (hit != null && (node.link().startsWith(HIT) || hit.kind() == SearchHit.Kind.NAME)) {
            out.add(hit);
        }
        for (final ViewSpec.TreeNode child : node.children()) {
            walk(child, targets, out);
        }
    }

    private static String hits(final int n) {
        return count(n, "hit");
    }

    private static String count(final int n, final String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** A hit and where it stands in the hits the tree is built from, which its row's link names. */
    private record Found(int index, SearchHit hit) {
    }

    /** A folder of the Files tab searched, or the folder of a hit outside its own: its files and its name. */
    private static final class Place {
        final String path;
        final Dir top = new Dir();
        String group;
        int files;

        Place(final String path) {
            this.path = path;
        }
    }

    /** A folder of the tree being built: its subfolders and its files, each by name, and the hits under it. */
    private static final class Dir {
        final TreeMap<String, Dir> dirs = new TreeMap<>();
        final TreeMap<String, Leaf> files = new TreeMap<>();
        int count;
    }

    /** A file of the tree being built: the hit on its name, if any, and those on its lines. */
    private static final class Leaf {
        final String source;
        final List<Found> lines = new ArrayList<>();
        Found name;

        Leaf(final String source) {
            this.source = source;
        }
    }
}
