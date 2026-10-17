package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.FolderKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.tree.TreeView;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The sidebar's Files tab: the folders the user configured ({@link FolderRoot}), one top-level row each, as a navigator
 * of their folders and files. A click selects; a double click (or Enter) opens a file in a panel, and a double click
 * opens or closes a folder; nothing opens a panel but a file asked for. Each kind of file has its icon, and a folder's
 * index file leads it, offered by name in its row's menu. Only the folders whose rows are open are read and kept in sync
 * ({@link Intent.WatchFolders}), so a closed folder costs nothing; the tree follows them as the tools adapter reads
 * them, keeping what the user opened and selected, and the newest day folder is open at a folder's first read. A
 * folder's row has the icon it was configured with; a read-only one says so in its detail.
 *
 * <p>The filter above the tree finds files and folders by name across every folder: while it has text, a second tree
 * shows the matches with the folders leading to them, and every folder is read once so the closed ones are searched
 * too. Clearing it (Esc) brings back the tree as the user left it. The bar above, at its left edge, follows the
 * selection, each tip naming what its button affects: on a folder row it adds a folder to the tab and edits, clones or
 * removes the selected one, with the same dialog as connections; on a file it edits the file in a panel, clones it
 * under a name the user gives, or deletes it (to the Trash where the desktop has one), add being off; on a folder
 * inside a folder only add applies. A read-only folder and its files are never edited, cloned nor removed.</p>
 */
final class FilesTab {

    /** The entry an unread folder shows until its tree arrives; it also makes the row openable. */
    static final String READING = "reading…";
    /** The entry an empty folder shows. */
    static final String EMPTY = "empty";
    /** The entry the filter shows when nothing matches. */
    static final String NO_MATCH = "nothing matches";
    /** Rows of the filter's tree expanded at most, so a filter of one letter stays quick. */
    private static final int MAX_FOUND_ROWS = 2_000;

    /** Asks for a name; null when the user cancels. Tests answer for the user. */
    interface Names {
        Names MODAL = (parent, title, text, initial) -> (String) JOptionPane.showInputDialog(parent, text, title,
                JOptionPane.PLAIN_MESSAGE, null, null, initial);

        String ask(Component parent, String title, String text, String initial);
    }

    private final IntentSink intents;
    private final Consumer<Path> open;
    /** Opens a file in a panel in its editor, rather than rendered. */
    private final Consumer<Path> editFile;
    private final ResourceActions.Questions questions;
    private final Names names;
    /** Whether the desktop has a Trash, asked when a file is to be deleted. */
    private final BooleanSupplier trash;
    private final ResourceActions.Editor editor;
    private final TreeView tree = new TreeView();
    private final TreeView found = new TreeView();
    private final JTextField filter = new JTextField();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel body = new JPanel(new BorderLayout());
    private final ResourceActions<FolderRoot> actions;
    private final List<Runnable> selectionListeners = new ArrayList<>();

    private List<FolderRoot> folders = List.of();
    /** The latest tree read of each folder, by name. */
    private final Map<String, ViewSpec.TreeNode> read = new HashMap<>();
    /** Folders whose newest day was opened, the first time their row was open with their tree read; never again. */
    private final Set<String> dayOpened = new HashSet<>();
    /** Folders read for the filter and not yet arrived: each is asked for once, not at every keystroke. */
    private final Set<String> requested = new HashSet<>();
    /** The folders the filter's tree shows, so a read that adds one opens it there without resetting the others. */
    private Set<String> foundRows = Set.of();
    /** The open folders as last sent in {@link Intent.WatchFolders}; the adapter starts watching none. */
    private List<String> watched = List.of();
    /** True while code, not the user, expands rows: a restored row neither opens its index nor changes what is watched. */
    private boolean restoring;
    private boolean built;

    FilesTab(IntentSink intents, Consumer<Path> open, Consumer<Path> editFile, ResourceActions.Editor editor,
             ResourceActions.Questions questions, Names names, BooleanSupplier trash, Supplier<AppState> state) {
        this.intents = intents;
        this.open = open;
        this.editFile = editFile;
        this.questions = questions;
        this.names = names;
        this.trash = trash;
        this.editor = editor;
        for (TreeView t : List.of(tree, found)) {
            t.tree().setRootVisible(false);
            t.icons(n -> {
                String icon = rowIcon(n).orElseGet(() -> iconName(n, indexNames()));
                return icon.isEmpty() ? null : Icons.get(icon);
            });
            t.onActivate(this::activate);
            t.onMenu(this::menu);
            if (t.component() instanceof JScrollPane sp) {
                // as the sidebar's lists: a long path is cut at the edge rather than scrolled to
                sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            }
        }
        tree.tree().getAccessibleContext().setAccessibleName("Files");
        found.tree().getAccessibleContext().setAccessibleName("Files found");
        tree.tree().addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                if (!restoring && e.getPath().getPathCount() == 2) {
                    folderOf(e.getPath()).ifPresent(x -> openNewestDay(x.name()));
                    watchOpen(false);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                if (!restoring && e.getPath().getPathCount() == 2) {
                    watchOpen(false);
                }
            }
        });
        tree.tree().addTreeSelectionListener(e -> selectionListeners.forEach(Runnable::run));
        found.tree().addTreeSelectionListener(e -> selectionListeners.forEach(Runnable::run));

        filter.putClientProperty("JTextField.placeholderText", "Filter by name");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.getAccessibleContext().setAccessibleName("Filter files");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterChanged();
            }
        });
        filter.registerKeyboardAction(e -> filter.setText(""), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED);

        actions = new ResourceActions<>(new ResourceActions.Selection<>() {
            @Override
            public FolderRoot selected() {
                FolderRoot f = selectedFolder();
                return f == null || f.readOnly() ? null : f;
            }

            @Override
            public void onChange(Runnable listener) {
                selectionListeners.add(listener);
            }

            @Override
            public Component component() {
                return shown().tree();
            }

            @Override
            public boolean canAdd() {
                ViewSpec.TreeNode n = shown().selected();
                return n == null || !n.link().startsWith(SourceLinks.SCHEME);
            }

            @Override
            public ResourceActions.Target target() {
                return targetOf(shown().tree().getSelectionPath());
            }
        }, new ResourceActions.Rows<>(FolderKind.INSTANCE.noun(), FolderRoot::name, FolderRoot::name, f -> false, "",
                f -> FolderKind.INSTANCE.removalQuestion(state.get(), f.name())),
                editor, name -> intents.dispatch(new Intent.RemoveFolder(name)), questions)
                .leftAligned().addTip("Add a folder to the Files tab…");

        center.add(tree.component(), "tree");
        center.add(found.component(), "found");
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        top.add(filter, BorderLayout.CENTER);
        body.add(top, BorderLayout.NORTH);
        body.add(center, BorderLayout.CENTER);
    }

    /** The filter above the tree. */
    JComponent component() {
        return body;
    }

    /** Add, then edit, clone and remove of the selected folder or file, together at the left edge. */
    JComponent actions() {
        return actions.component();
    }

    TreeView tree() {
        return tree;
    }

    TreeView found() {
        return found;
    }

    JTextField filter() {
        return filter;
    }

    /**
     * Follows the configured folders: a folder that changed or went is forgotten, and the open ones are watched again,
     * so a folder edited while open is read with its new settings at once. A folder renamed and otherwise unchanged
     * keeps its tree, and stays open when it was.
     */
    void render(AppState s) {
        if (built && s.folders().equals(folders)) {
            return;
        }
        List<FolderRoot> before = folders;
        Set<String> nowNames = new HashSet<>(s.folders().stream().map(FolderRoot::name).toList());
        Set<String> renamed = new HashSet<>();
        Set<String> reopen = new HashSet<>();
        for (FolderRoot f : s.folders()) {
            if (before.stream().anyMatch(b -> b.name().equals(f.name()))) {
                continue;
            }
            before.stream().filter(old -> !nowNames.contains(old.name()) && old.withName(f.name()).equals(f)).findFirst().ifPresent(old -> {
                rename(old.name(), f.name());
                renamed.add(f.name());
                TreePath row = folderRow(old.name());
                if (row != null && tree.tree().isExpanded(row)) {
                    reopen.add(f.name());
                }
            });
        }
        folders = s.folders();
        Set<String> keep = new HashSet<>(renamed);
        folders.stream().filter(before::contains).forEach(f -> keep.add(f.name()));
        read.keySet().retainAll(keep);
        dayOpened.retainAll(keep);
        requested.retainAll(keep);
        rebuild();
        restoring = true;
        try {
            for (String name : reopen) {
                TreePath row = folderRow(name);
                if (row != null) {
                    tree.tree().expandPath(row);
                }
            }
        } finally {
            restoring = false;
        }
        watchOpen(true);
        if (searching()) {
            readUnread();
            refilter(true);
        }
    }

    /** Moves what is known of folder {@code from} to its new name {@code to}. */
    private void rename(String from, String to) {
        ViewSpec.TreeNode t = read.remove(from);
        if (t != null) {
            read.put(to, t);
        }
        if (dayOpened.remove(from)) {
            dayOpened.add(to);
        }
        if (requested.remove(from)) {
            requested.add(to);
        }
    }

    /**
     * A folder as read; a later read keeps the tree as the user left it. A tree read with settings that are no longer
     * the folder's, or of a folder no longer configured, is ignored.
     */
    void show(FolderRoot folder, ViewSpec.TreeNode folderTree) {
        if (!folders.contains(folder)) {
            return;
        }
        String name = folder.name();
        read.put(name, folderTree);
        requested.remove(name);
        rebuild();
        openNewestDay(name);
        if (searching()) {
            refilter(false);
        }
    }

    private Optional<FolderRoot> folder(String name) {
        return folders.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    private Set<String> indexNames() {
        Set<String> names = new HashSet<>();
        for (FolderRoot f : folders) {
            if (!f.index().isEmpty()) {
                names.add(Path.of(f.index()).getFileName().toString().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private void rebuild() {
        List<ViewSpec.TreeNode> rows = new ArrayList<>(folders.size());
        for (FolderRoot f : folders) {
            ViewSpec.TreeNode t = read.get(f.name());
            List<ViewSpec.TreeNode> children = t == null ? List.of(placeholder(READING))
                    : t.children().isEmpty() ? List.of(placeholder(EMPTY)) : t.children();
            rows.add(new ViewSpec.TreeNode(f.name(), rowDetail(f), children));
        }
        ViewSpec.TreeNode root = new ViewSpec.TreeNode("Files", "",
                rows.isEmpty() ? List.of(placeholder("No folders yet. + adds one.")) : rows);
        restoring = true;
        try {
            if (built) {
                tree.update(root);
            } else {
                built = true;
                tree.setRoot(root, 1);
            }
        } finally {
            restoring = false;
        }
        selectionListeners.forEach(Runnable::run);
    }

    /** A folder's row's detail: its path, and whether it is read-only. */
    private static String rowDetail(FolderRoot f) {
        return Labels.shortPath(f.path()) + (f.readOnly() ? " · read-only" : "");
    }

    /**
     * The icon of a folder's top-level row, as configured; empty for any other node. A row is known by its name and
     * its detail, which no entry inside a folder has (theirs count entries), so a subfolder named as a folder keeps the
     * folder icon.
     */
    Optional<String> rowIcon(ViewSpec.TreeNode n) {
        for (FolderRoot f : folders) {
            if (f.name().equals(n.label()) && rowDetail(f).equals(n.detail())) {
                return Optional.of(rowIconName(f));
            }
        }
        return Optional.empty();
    }

    /** {@code folder} for the plain icon, else {@code folder-<icon>}. */
    static String rowIconName(FolderRoot f) {
        return f.icon().equals(FolderRoot.FOLDER) ? "folder" : "folder-" + f.icon();
    }

    private static ViewSpec.TreeNode placeholder(String label) {
        return new ViewSpec.TreeNode(label, "", List.of());
    }

    /**
     * Opens the last date-named folder directly under {@code name}'s row, such as a scratchpad's newest day: once per
     * folder, the first time its row is open with its tree read.
     */
    private void openNewestDay(String name) {
        TreePath row = folderRow(name);
        if (row == null || !tree.tree().isExpanded(row) || !read.containsKey(name) || !dayOpened.add(name)) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) row.getLastPathComponent();
        TreePath newest = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
            if (c.getUserObject() instanceof ViewSpec.TreeNode n && n.label().matches("\\d{4}-\\d{2}-\\d{2}") && !n.children().isEmpty()) {
                newest = row.pathByAddingChild(c);
            }
        }
        if (newest != null) {
            restoring = true;
            try {
                tree.tree().expandPath(newest);
            } finally {
                restoring = false;
            }
        }
    }

    private TreePath folderRow(String name) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.tree().getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) root.getChildAt(i);
            if (c.getUserObject() instanceof ViewSpec.TreeNode n && n.label().equals(name)) {
                return new TreePath(root).pathByAddingChild(c);
            }
        }
        return null;
    }

    /** The open folders, in their order; sent when they changed, or when {@code force} and any is open. */
    private void watchOpen(boolean force) {
        List<String> open = new ArrayList<>();
        for (FolderRoot f : folders) {
            TreePath row = folderRow(f.name());
            if (row != null && tree.tree().isExpanded(row)) {
                open.add(f.name());
            }
        }
        if (!open.equals(watched) || force && !open.isEmpty()) {
            watched = List.copyOf(open);
            intents.dispatch(new Intent.WatchFolders(watched));
        }
    }

    private Optional<FolderRoot> folderOf(TreePath path) {
        return path.getPathCount() < 2 || !(((DefaultMutableTreeNode) path.getPathComponent(1)).getUserObject() instanceof ViewSpec.TreeNode n)
                ? Optional.empty() : folder(n.label());
    }

    /** The tree in front: the filter's while it has text, else the folders'. */
    private TreeView shown() {
        return searching() ? found : tree;
    }

    /** The folder whose top-level row is selected in the tree in front, or null. */
    private FolderRoot selectedFolder() {
        TreePath p = shown().tree().getSelectionPath();
        return p == null || p.getPathCount() != 2 ? null : folderOf(p).orElse(null);
    }

    /** What the bar's edit, clone and remove do to the node at {@code path}, and what their tips say. */
    private ResourceActions.Target targetOf(TreePath path) {
        ViewSpec.TreeNode n = path == null ? null : node(path);
        Optional<FolderRoot> folder = path == null ? Optional.empty() : folderOf(path);
        // nothing, or an entry that stands for none: "reading…", "empty", "…", an error
        if (n == null || folder.isEmpty() || n.label().equals(ViewSpec.TreeNode.MORE)
                || n.children().isEmpty() && n.link().isEmpty() && n.detail().isEmpty()) {
            return off("the selected folder or file", "");
        }
        FolderRoot f = folder.get();
        if (path.getPathCount() == 2) {
            return f.readOnly() ? off("folder " + f.name(), ": it is read-only")
                    : new ResourceActions.Target("Edit folder " + f.name() + "…", () -> editor.edit(f.name()),
                    "Clone folder " + f.name() + "…", () -> editor.cloneOf(f.name()),
                    "Remove folder " + f.name() + " from the Files tab…", () -> actions.remove(f));
        }
        if (!n.link().startsWith(SourceLinks.SCHEME)) {
            return off("folder " + n.label(), ": only files and the tab's own folders");
        }
        Path file = Path.of(SourceLinks.path(n.link()));
        if (f.readOnly()) {
            return off("file " + n.label(), ": folder " + f.name() + " is read-only");
        }
        return new ResourceActions.Target("Edit file " + n.label() + " in a panel", () -> editFile.accept(file),
                "Clone file " + n.label() + "…", () -> cloneFile(file),
                (trash.getAsBoolean() ? "Move file " + n.label() + " to the Trash…" : "Delete file " + n.label() + "…"), () -> deleteFile(file));
    }

    /** Buttons off, their tips naming {@code what} and {@code why}. */
    private static ResourceActions.Target off(String what, String why) {
        return new ResourceActions.Target("Edit " + what + why, null, "Clone " + what + why, null, "Remove " + what + why, null);
    }

    private static ViewSpec.TreeNode node(TreePath path) {
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n ? n : null;
    }

    /** Copies {@code file} beside it, under a name the user gives; "notes copy.md" offered. */
    private void cloneFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String offered = dot > 0 ? name.substring(0, dot) + " copy" + name.substring(dot) : name + " copy";
        String answer = names.ask(shown().tree(), "Clone " + name, "Name of the copy, in the same folder:", offered);
        if (answer == null) {
            return;
        }
        String copy = answer.strip();
        if (!isNewName(copy, name)) {
            intents.dispatch(new Intent.Notify(Notice.Level.ERROR, "Cannot clone " + name + ": \"" + copy
                    + "\" is not a new name in its folder", false));
            return;
        }
        intents.dispatch(new Intent.CopyFile(file.toString(), file.resolveSibling(copy).toString()));
    }

    /** Whether {@code copy} names a file beside {@code original}: one name, not a path, and not the original's. */
    static boolean isNewName(String copy, String original) {
        try {
            return !copy.isEmpty() && !copy.equals(".") && !copy.equals("..") && !copy.equals(original)
                    && Path.of(copy).getNameCount() == 1 && !copy.contains("/");
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Deletes {@code file} after asking: to the Trash where the desktop has one, for good otherwise, as the question says. */
    private void deleteFile(Path file) {
        String name = file.getFileName().toString();
        boolean toTrash = trash.getAsBoolean();
        String text = toTrash ? "Move " + name + " to the Trash? It can be put back from there."
                : "Delete " + name + " for good? This desktop has no Trash: it cannot be undone.";
        Object[] options = toTrash ? new Object[]{"Move to Trash", "Cancel"} : new Object[]{"Delete", "Cancel"};
        if (questions.ask(shown().tree(), Plain.text(text), "Delete " + Plain.text(name), options) == 0) {
            intents.dispatch(new Intent.DeleteFile(file.toString(), toTrash));
        }
    }

    private void activate(ViewSpec.TreeNode n) {
        if (n.link().startsWith(SourceLinks.SCHEME)) {
            open.accept(Path.of(SourceLinks.path(n.link())));
        }
    }

    private void menu(ViewSpec.TreeNode n, MouseEvent e) {
        JPopupMenu menu = menuFor(n);
        if (menu.getComponentCount() > 0) {
            menu.show(e.getComponent(), e.getX(), e.getY());
        }
    }

    /**
     * The context menu of {@code n}, selected in the tree in front: a file's, a folder row's, or none. A read-only
     * folder's offers its index file only.
     */
    JPopupMenu menuFor(ViewSpec.TreeNode n) {
        JPopupMenu menu = new JPopupMenu();
        if (n.link().startsWith(SourceLinks.SCHEME)) {
            menu.add(item("Open in a panel", "text", () -> activate(n)));
            // the bar's actions on the file selected, as the bar names them
            ResourceActions.Target t = targetOf(shown().tree().getSelectionPath());
            if (t.edit() != null && n.equals(node(shown().tree().getSelectionPath()))) {
                menu.add(item("Edit in a panel", "edit", t.edit()));
                menu.add(item("Clone…", "copy", t.cloneOf()));
                menu.add(item(trash.getAsBoolean() ? "Move to the Trash…" : "Delete…", "close", t.remove()));
            }
        } else if (selectedFolder() != null && selectedFolder().name().equals(n.label())) {
            FolderRoot f = selectedFolder();
            // asked for by name: a missing index says so in the status bar, as any file that cannot be opened
            f.indexFile().ifPresent(index -> menu.add(item("Open " + f.index(), "index", () -> open.accept(index))));
            if (f.readOnly()) {
                return menu;
            }
            menu.add(item("Edit…", "edit", () -> editor.edit(f.name())));
            menu.add(item("Clone…", "copy", () -> editor.cloneOf(f.name())));
            menu.add(item("Remove…", "close", () -> actions.remove(f)));
        }
        return menu;
    }

    private static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(a -> r.run());
        return i;
    }

    // ---- filter

    private boolean searching() {
        return !filter.getText().isBlank();
    }

    private void filterChanged() {
        if (searching()) {
            readUnread();
            refilter(true);
            cards.show(center, "found");
        } else {
            cards.show(center, "tree");
        }
        selectionListeners.forEach(Runnable::run);
    }

    /** Reads, once, the folders never read, so the filter searches the closed ones too. */
    private void readUnread() {
        List<String> unread = folders.stream().map(FolderRoot::name)
                .filter(name -> !read.containsKey(name) && requested.add(name)).toList();
        if (!unread.isEmpty()) {
            intents.dispatch(new Intent.BrowseFolders(unread));
        }
    }

    /**
     * Shows the matches. On new text ({@code reset}) the tree is built afresh with the folders leading to every match
     * open; on a read while the text stands, the tree keeps what the user opened, closed and selected, and only a
     * folder new to it is opened to its matches.
     */
    private void refilter(boolean reset) {
        String q = filter.getText().strip().toLowerCase(Locale.ROOT);
        List<ViewSpec.TreeNode> rows = new ArrayList<>();
        for (FolderRoot f : folders) {
            ViewSpec.TreeNode t = read.get(f.name());
            if (t != null) {
                List<ViewSpec.TreeNode> kids = matches(t.children(), q);
                if (!kids.isEmpty()) {
                    rows.add(new ViewSpec.TreeNode(f.name(), rowDetail(f), kids));
                }
            }
        }
        ViewSpec.TreeNode root = new ViewSpec.TreeNode("Found", "", rows.isEmpty() ? List.of(placeholder(NO_MATCH)) : rows);
        Set<String> opening = new HashSet<>(rows.stream().map(ViewSpec.TreeNode::label).toList());
        if (reset) {
            found.setRoot(root, 1);
        } else {
            found.update(root);
            opening.removeAll(foundRows);
        }
        foundRows = Set.copyOf(rows.stream().map(ViewSpec.TreeNode::label).toList());
        // open the folders leading to a match, not the matches: a matching folder stays closed until opened
        for (int i = 0; i < found.tree().getRowCount() && i < MAX_FOUND_ROWS; i++) {
            TreePath p = found.tree().getPathForRow(i);
            if (p.getPathCount() >= 2 && ((DefaultMutableTreeNode) p.getPathComponent(1)).getUserObject() instanceof ViewSpec.TreeNode row
                    && opening.contains(row.label())
                    && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                    && !n.children().isEmpty() && (p.getPathCount() == 2 || !matches(n.label(), q))) {
                found.tree().expandRow(i);
            }
        }
    }

    /** {@code nodes} cut to those whose name matches {@code q} and the folders leading to them; a match keeps its entries. */
    static List<ViewSpec.TreeNode> matches(List<ViewSpec.TreeNode> nodes, String q) {
        List<ViewSpec.TreeNode> out = new ArrayList<>();
        for (ViewSpec.TreeNode n : nodes) {
            if (matches(n.label(), q)) {
                out.add(n);
            } else if (!n.children().isEmpty()) {
                List<ViewSpec.TreeNode> kids = matches(n.children(), q);
                if (!kids.isEmpty()) {
                    out.add(new ViewSpec.TreeNode(n.label(), n.detail(), n.link(), kids));
                }
            }
        }
        return out;
    }

    private static boolean matches(String label, String q) {
        return label.toLowerCase(Locale.ROOT).contains(q);
    }

    /**
     * The icon of a node: folders (work folders apart), then files by name and extension; a folder's index file has
     * the index icon. Empty for an entry that is neither, such as {@link #READING}: the look's own leaf icon then.
     */
    static String iconName(ViewSpec.TreeNode n, Set<String> indexNames) {
        String name = n.label().toLowerCase(Locale.ROOT);
        if (!n.link().startsWith(SourceLinks.SCHEME)) {
            if (n.children().isEmpty() && n.detail().isEmpty() || n.label().equals(ViewSpec.TreeNode.MORE)) {
                return "";
            }
            return name.equals("work") ? "folder-work" : "folder";
        }
        if (indexNames.contains(name)) {
            return "index";
        }
        String ext = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1);
        if (name.contains("plan") && ext.equals("md")) {
            return "plan";
        }
        if (name.startsWith("read-")) {
            return "read";
        }
        return switch (ext) {
            case "md", "markdown" -> "markdown";
            case "java", "kt", "kts", "py", "js", "ts", "go", "clj", "rs", "c", "h", "cpp", "scala", "groovy" -> "code";
            case "json", "jsonl", "yaml", "yml", "toml", "xml", "properties", "conf" -> "json";
            case "sh", "zsh", "bash" -> "terminal";
            case "sql" -> "database";
            case "csv", "tsv", "parquet" -> "table";
            case "png", "jpg", "jpeg", "gif", "svg", "webp" -> "image";
            case "patch", "diff" -> "diff";
            case "txt", "log", "out" -> "text";
            default -> "file";
        };
    }
}
