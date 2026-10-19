package dev.marregui.comando.ui.widgets.changes;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.tree.TreeView;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * What the Files tab's git repositories hold that their last commits do not, as IDEs show it before a commit: a row
 * per repository with its branch and its count (one repository only, or one folder of it, when the view says so), then
 * up to three groups, Changed, Untracked and Removed, each by folder (a chain of folders holding only one folder is one
 * row), its files named in git's colour for how they changed. A double
 * click or Enter on a file compares it with its {@code HEAD} version side by side; moving the selection moves a
 * comparison already shown to the file selected. The view follows the repositories as they are read: every row is open
 * but those the user closed, known by their folders' path so a chain of folders that splits or joins stays as the
 * user left it, and the file selected stays selected.
 */
public final class ChangesWidget implements Widget<ViewSpec.Changes>, Copyable {

    /** What the view shows when the Files tab's folders hold no git repository. */
    static final String NO_REPOS = "No git repository in the Files tab's folders";
    /** What a view of one repository shows while that repository is not among those read. */
    static final String NOT_WATCHED = "Not read as a git repository: ";
    /** The three groups of a repository's row, in this order: what changed, what is new and untracked, what went. */
    static final String CHANGED = "Changed";
    static final String UNTRACKED = "Untracked";
    static final String REMOVED = "Removed";
    static final String CLEAN = "no changes";
    /** Rows looked at, at most, for new folders to open after a read. */
    private static final int MAX_ROWS_OPENED = 3_000;
    private static final String LINK = "change:";

    /** A file's row: its repository and its change. */
    record Target(String root, RepoStatus.FileChange change) {
    }

    private final TreeView tree = new TreeView();
    private final IntentSink intents;
    private final RepoChanges changes;
    private final Consumer<Path> openFile;
    private final Runnable unlisten;
    private final Map<String, Target> targets = new HashMap<>();
    /** The rows the user closed, by {@link #key}: every other row is open. */
    private final Set<String> closed = new HashSet<>();
    /** Which repositories, and which folder of one, the view shows. */
    private ViewSpec.Changes scope = new ViewSpec.Changes();
    private boolean built;
    /** True while a read is shown: the selection it restores is not the user's move. */
    private boolean rendering;

    public ChangesWidget(WidgetContext context, RepoChanges changes, Consumer<Path> openFile) {
        this.intents = context.intents();
        this.changes = changes;
        this.openFile = openFile;
        tree.tree().setRootVisible(false);
        tree.tree().getAccessibleContext().setAccessibleName(ViewSpec.Changes.TITLE);
        tree.icons(this::icon);
        tree.colors(this::color);
        tree.onActivate(n -> compare(n, ChangeAsk.OPEN));
        tree.onMenu(this::menu);
        tree.tree().addTreeSelectionListener(e -> {
            if (!rendering && tree.tree().getSelectionCount() == 1) {
                compare(tree.selected(), ChangeAsk.FOLLOW);
            }
        });
        tree.tree().addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                if (!rendering) {
                    closed.remove(key(e.getPath()));
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                if (!rendering) {
                    closed.add(key(e.getPath()));
                }
            }
        });
        Capabilities.mark(tree.component(), this);
        unlisten = changes.listen(this::refresh);
        refresh();
    }

    @Override
    public JComponent component() {
        return tree.component();
    }

    @Override
    public void render(ViewSpec.Changes view) {
        scope = view;
        refresh();
    }

    @Override
    public void unmount() {
        unlisten.run();
    }

    @Override
    public String copyText() {
        return tree.copyText();
    }

    TreeView tree() {
        return tree;
    }

    private void refresh() {
        ViewSpec.TreeNode selected = tree.selected();
        String selectedLink = selected == null ? "" : selected.link();
        ViewSpec.TreeNode root = build(changes.repos(), scope, targets);
        rendering = true;
        try {
            if (built) {
                tree.update(root);
            } else {
                built = true;
                tree.setRoot(root, 1);
            }
            openAllButClosed();
            if (targets.containsKey(selectedLink) && (tree.selected() == null || !tree.selected().link().equals(selectedLink))) {
                reselect(selectedLink);
            }
        } finally {
            rendering = false;
        }
    }

    /** Opens every row the user has not closed, so a new repository, group or folder shows its files. */
    private void openAllButClosed() {
        for (int i = 0; i < tree.tree().getRowCount() && i < MAX_ROWS_OPENED; i++) {
            TreePath p = tree.tree().getPathForRow(i);
            if (!((DefaultMutableTreeNode) p.getLastPathComponent()).isLeaf() && !tree.tree().isExpanded(p) && !closed.contains(key(p))) {
                tree.tree().expandRow(i);
            }
        }
    }

    /** Selects the file row linked {@code link}, when it shows: a folder chain that split or joined moved it. */
    private void reselect(String link) {
        for (int i = 0; i < tree.tree().getRowCount(); i++) {
            TreePath p = tree.tree().getPathForRow(i);
            if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n && n.link().equals(link)) {
                tree.tree().setSelectionPath(p);
                return;
            }
        }
    }

    /**
     * A row's key: the labels below the root joined by '/', so a chain of folders shown as one row ("a/b/c") and the
     * same folders shown apart ("a/b", then "c") have the same key.
     */
    static String key(TreePath p) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < p.getPathCount(); i++) {
            if (((DefaultMutableTreeNode) p.getPathComponent(i)).getUserObject() instanceof ViewSpec.TreeNode n) {
                out.append(i > 1 ? "/" : "").append(n.label());
            }
        }
        return out.toString();
    }

    /**
     * The rows of {@code repos}; each file's row is linked to its change in {@code targets}. A repository's row is named
     * after its folder, or after its path when two watched repositories share a name.
     */
    static ViewSpec.TreeNode build(List<RepoStatus> repos, Map<String, Target> targets) {
        return build(repos, new ViewSpec.Changes(), targets);
    }

    /**
     * The rows of what {@code scope} asks for: every repository, or the one at its root, called its name, with only the
     * changes inside its folder when it names one.
     */
    static ViewSpec.TreeNode build(List<RepoStatus> repos, ViewSpec.Changes scope, Map<String, Target> targets) {
        if (scope.root().isEmpty()) {
            return all(repos, targets);
        }
        targets.clear();
        Path top = Path.of(scope.root()).toAbsolutePath().normalize();
        for (RepoStatus r : repos) {
            if (Path.of(r.root()).toAbsolutePath().normalize().equals(top)) {
                RepoStatus shown = scope.folder().isEmpty() ? r
                        : new RepoStatus(r.root(), r.branch(), RepoChanges.changesIn(r, scope.folder()), r.truncated(), r.error());
                String label = scope.name().isEmpty() ? r.name() : scope.name();
                return new ViewSpec.TreeNode("Changes", "", List.of(repo(shown, label, targets)));
            }
        }
        return new ViewSpec.TreeNode("Changes", "", List.of(note(NOT_WATCHED + Labels.shortPath(scope.root()))));
    }

    private static ViewSpec.TreeNode all(List<RepoStatus> repos, Map<String, Target> targets) {
        targets.clear();
        Map<String, Integer> names = new HashMap<>();
        repos.forEach(r -> names.merge(r.name(), 1, Integer::sum));
        List<ViewSpec.TreeNode> rows = new ArrayList<>(repos.size());
        for (RepoStatus r : repos) {
            String label = names.get(r.name()) > 1 ? Labels.shortPath(r.root()) : r.name();
            rows.add(repo(r, label, targets));
        }
        return new ViewSpec.TreeNode("Changes", "", rows.isEmpty() ? List.of(note(NO_REPOS)) : rows);
    }

    private static ViewSpec.TreeNode repo(RepoStatus r, String label, Map<String, Target> targets) {
        if (!r.error().isEmpty()) {
            return new ViewSpec.TreeNode(label, Labels.shortPath(r.root()), List.of(note("✗ cannot be read: " + r.error())));
        }
        List<RepoStatus.FileChange> untracked = r.changes().stream().filter(c -> c.kind() == RepoStatus.Kind.UNTRACKED).toList();
        List<RepoStatus.FileChange> removed = r.changes().stream().filter(c -> c.kind() == RepoStatus.Kind.DELETED).toList();
        List<RepoStatus.FileChange> changed = r.changes().stream()
                .filter(c -> c.kind() != RepoStatus.Kind.UNTRACKED && c.kind() != RepoStatus.Kind.DELETED).toList();
        List<ViewSpec.TreeNode> kids = new ArrayList<>(4);
        if (!changed.isEmpty()) {
            kids.add(new ViewSpec.TreeNode(CHANGED, files(changed.size()), folders(r.root(), changed, targets)));
        }
        if (!untracked.isEmpty()) {
            kids.add(new ViewSpec.TreeNode(UNTRACKED, files(untracked.size()), folders(r.root(), untracked, targets)));
        }
        if (!removed.isEmpty()) {
            kids.add(new ViewSpec.TreeNode(REMOVED, files(removed.size()), folders(r.root(), removed, targets)));
        }
        if (r.truncated()) {
            kids.add(new ViewSpec.TreeNode(ViewSpec.TreeNode.MORE, "more changes than the view shows", List.of()));
        }
        String count = r.changes().isEmpty() ? CLEAN : r.changes().size() + (r.truncated() ? "+" : "") + (r.changes().size() == 1 ? " change" : " changes");
        String detail = (r.branch().isEmpty() ? "" : r.branch() + " · ") + count;
        return new ViewSpec.TreeNode(label, detail, kids);
    }

    private static String files(int n) {
        return n == 1 ? "1 file" : n + " files";
    }

    private static ViewSpec.TreeNode note(String text) {
        return new ViewSpec.TreeNode(text, "", List.of());
    }

    /** A folder of the tree being built: its subfolders and its files, each by name. */
    private static final class Dir {
        final TreeMap<String, Dir> dirs = new TreeMap<>();
        final TreeMap<String, RepoStatus.FileChange> files = new TreeMap<>();
        int count;
    }

    /** {@code list} as folders and files, folders first; a folder holding only one folder joins it in one row. */
    static List<ViewSpec.TreeNode> folders(String root, List<RepoStatus.FileChange> list, Map<String, Target> targets) {
        Dir top = new Dir();
        for (RepoStatus.FileChange c : list) {
            String[] parts = c.path().split("/");
            Dir d = top;
            d.count++;
            for (int i = 0; i < parts.length - 1; i++) {
                d = d.dirs.computeIfAbsent(parts[i], k -> new Dir());
                d.count++;
            }
            d.files.put(parts[parts.length - 1], c);
        }
        return nodes(root, top, targets);
    }

    private static List<ViewSpec.TreeNode> nodes(String root, Dir dir, Map<String, Target> targets) {
        List<ViewSpec.TreeNode> out = new ArrayList<>(dir.dirs.size() + dir.files.size());
        for (Map.Entry<String, Dir> e : dir.dirs.entrySet()) {
            StringBuilder label = new StringBuilder(e.getKey());
            Dir d = e.getValue();
            while (d.files.isEmpty() && d.dirs.size() == 1) {
                Map.Entry<String, Dir> only = d.dirs.firstEntry();
                label.append('/').append(only.getKey());
                d = only.getValue();
            }
            out.add(new ViewSpec.TreeNode(label.toString(), files(d.count), nodes(root, d, targets)));
        }
        for (Map.Entry<String, RepoStatus.FileChange> e : dir.files.entrySet()) {
            RepoStatus.FileChange c = e.getValue();
            // the kind is part of the link: after git rm --cached one path is both deleted and untracked
            String link = LINK + root + '\n' + c.kind() + '\n' + c.path();
            targets.put(link, new Target(root, c));
            String detail = RepoChanges.describe(c.kind()) + (c.from().isEmpty() ? "" : " from " + c.from());
            out.add(new ViewSpec.TreeNode(e.getKey(), detail, link, List.of()));
        }
        return out;
    }

    private Icon icon(ViewSpec.TreeNode n) {
        if (targets.containsKey(n.link())) {
            return Icons.get("file");
        }
        if (n.children().isEmpty()) {
            return null;
        }
        return Icons.get(switch (n.label()) {
            case CHANGED -> "changes-modified";
            case UNTRACKED -> "changes-untracked";
            case REMOVED -> "changes-removed";
            default -> "folder";
        });
    }

    private Color color(DefaultMutableTreeNode node) {
        return node.getUserObject() instanceof ViewSpec.TreeNode n && targets.get(n.link()) instanceof Target t
                ? RepoChanges.color(t.change().kind()) : null;
    }

    /** Compares the file of row {@code n} with its {@code HEAD} version; a row that is not a file's does nothing. */
    private void compare(ViewSpec.TreeNode n, ChangeAsk ask) {
        Target t = n == null ? null : targets.get(n.link());
        if (t != null) {
            intents.dispatch(new Intent.OpenChange(t.root(), t.change(), ask));
        }
    }

    private void menu(ViewSpec.TreeNode n, MouseEvent e) {
        Target t = targets.get(n.link());
        if (t == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Compare with HEAD", "diff", () -> compare(n, ChangeAsk.OPEN)));
        if (t.change().kind() != RepoStatus.Kind.DELETED) {
            menu.add(item("Open in a panel", "text", () -> openFile.accept(Path.of(t.root()).resolve(t.change().path()))));
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(a -> r.run());
        return i;
    }
}
