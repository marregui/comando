package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.icon.RowIcon;
import dev.marregui.comando.ui.kit.overlay.Balloon;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.tree.TreeView;
import dev.marregui.comando.ui.kit.vcs.OpenedFiles;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The sidebar's Files tab: the folders the user configured ({@link FolderRoot}), one top-level row each, as a navigator
 * of their folders and files. A click selects; a double click (or Enter) opens a file in a panel, and a double click
 * opens or closes a folder; nothing opens a panel but a file asked for. Each kind of file has its icon, and a folder's
 * index file leads it, offered by name in its row's menu. Only the folders whose rows are open are read and kept in sync
 * ({@link Intent.WatchFolders}), so a closed folder costs nothing; the tree follows them as the tools adapter reads
 * them, keeping what the user opened and selected, and the newest day folder is open at a folder's first read. A
 * folder's row has the icon it was configured with. In a git repository, what git ignores is left out, and a file
 * that differs from {@code HEAD} (or a folder holding one) has git's colour for how it changed. A repository's top
 * folder has the git icon and, while the repository has changes, a rising arrow after its name; hovering it says its
 * branch and what it holds uncommitted. A double click on a folder's row opens the Changes view of what the repository
 * holding it changed inside it, or says there is nothing to see, and opens or closes nothing; a folder's or a
 * subfolder's menu shows the changes of the repository holding it inside that folder alone, and a changed file's
 * compares it with {@code HEAD}. Every folder lies inside Claude Code's folder: the tab is no browser of code
 * repositories.
 *
 * <p>The tab has no buttons, and a file's menu opens it, rendered or as text; the folders are configured in {@code
 * folders.json}, through the windows the tab's own menu opens. In a folder configured as deletable, the menu also moves the selected files and subfolders to the
 * Trash, after asking; that is the only thing the tab writes. The filter under the tree finds files and folders by name across every folder:
 * while it has text, a second tree shows the matches with the folders leading to them, and every folder is read once
 * so the closed ones are searched too. Clearing it (Esc) brings back the tree as the user left it.</p>
 */
final class FilesTab {

    /** A git repository's top folder: its icon; its mark after its name while it has changes. */
    static final String GIT_ICON = "folder-git";
    static final String CHANGES_ICON = "changes";
    /** After the name of a new (untracked) file and of each folder on the way down to one. */
    static final String NEW_ICON = "new-file";
    static final String NEW_TIP = "Open to reach the new files inside, which git does not track yet";
    /** On a folder on the way down to a changed file, and on the file: the trail of the rising arrow (record 97). */
    static final String CHANGED_TIP = "Open to reach the files inside that differ from the last commit";
    static final String CHANGED_FILE_TIP = "Differs from the last commit; right-click to compare with HEAD";
    /** Where a folder's top-level row is in a node's path: under the tree's hidden root. */
    static final int ROW = 1;

    /** The entry an unread folder shows until its tree arrives; it also makes the row openable. */
    static final String READING = "reading…";
    /** The entry an empty folder shows. */
    static final String EMPTY = "empty";
    /** The entry the filter shows when nothing matches. */
    static final String NO_MATCH = "nothing matches";
    /** Rows of the filter's tree expanded at most, so a filter of one letter stays quick. */
    private static final int MAX_FOUND_ROWS = 2_000;

    private final IntentSink intents;
    private final Consumer<Path> open;
    /** Opens a file in a panel in its editor, rather than rendered. */
    private final Consumer<Path> editFile;
    private final ResourceActions.Questions questions;
    private final TreeView tree = new TreeView();
    private final TreeView found = new TreeView();
    private final JTextField filter = new JTextField();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel body = new JPanel(new BorderLayout());
    /** Says a word beside a row, asking nothing: a balloon; tests record it. */
    interface Tell {
        Tell BALLOON = Balloon::show;

        void tell(JComponent owner, Point at, String text);
    }

    private Tell tell = Tell.BALLOON;

    /** The git repositories of the open folders, as last read: their files' and folders' colours. */
    private final RepoChanges changes = new RepoChanges();
    /** The files the user opened: a new file opened once is marked no more. */
    private OpenedFiles opened = OpenedFiles.inMemory();
    private Runnable stopHearingOpened = () -> { };
    /** The folders holding a marked file: a change, or a new file not opened yet. Made when either changes. */
    private Set<Path> marked = Set.of();
    /** The folders on the way down to a new file not opened yet. */
    private Set<Path> towardsNew = Set.of();
    /** The folders on the way down to a file git tracks that differs from {@code HEAD}. */
    private Set<Path> towardsChanged = Set.of();
    /** The arrow and the dot after one name, made once. */
    private Icon changedAndNew;

    private List<FolderRoot> folders = List.of();
    /** The folders' index file names, lower case, as each cell's icon asks: made when the folders change. */
    private Set<String> indexNames = Set.of();
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
    /** Adds the window's own items to a folder row's menu: the brain's backup. */
    private BiConsumer<FolderRoot, JPopupMenu> folderMenu = (f, m) -> {
    };

    FilesTab(IntentSink intents, Consumer<Path> open, Consumer<Path> editFile) {
        this(intents, open, editFile, ResourceActions.Questions.MODAL);
    }

    /** With the question asked before entries go to the Trash; tests answer it. */
    FilesTab(IntentSink intents, Consumer<Path> open, Consumer<Path> editFile, ResourceActions.Questions questions) {
        this.intents = intents;
        this.open = open;
        this.editFile = editFile;
        this.questions = questions;
        for (TreeView t : List.of(tree, found)) {
            t.tree().setRootVisible(false);
            t.icons(n -> {
                String icon = rowIcon(n).orElseGet(() -> iconName(n, indexNames));
                return icon.isEmpty() ? null : Icons.get(icon);
            });
            t.colors(this::colorOf);
            t.nodeIcons(n -> repoAt(n).isPresent() ? Icons.get(GIT_ICON) : null);
            t.tips(n -> repoAt(n).map(FilesTab::repoTip).or(() -> rowTip(n))
                    .orElse(holdsNew(n) ? NEW_TIP : isChanged(n) ? CHANGED_FILE_TIP : holdsChanged(n) ? CHANGED_TIP : null));
            t.trailing(this::marks);
            // one click opens or closes a folder; a double click on a folder's row shows its changes instead
            t.toggleOnClick(true);
            t.tree().addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        TreePath p = t.tree().getPathForLocation(e.getX(), e.getY());
                        if (p != null && isRow(p.getPath())) {
                            t.undoClickToggle(p);
                            openChanges(t, p);
                        }
                    }
                }
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
                if (!restoring && isRow(e.getPath().getPath())) {
                    folderOf(e.getPath()).ifPresent(x -> openNewestDay(x.name()));
                }
                if (!restoring && isRow(e.getPath().getPath())) {
                    watchOpen(false);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                if (!restoring && isRow(e.getPath().getPath())) {
                    watchOpen(false);
                }
            }
        });

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

        center.add(tree.component(), "tree");
        center.add(found.component(), "found");
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        bottom.add(filter, BorderLayout.CENTER);
        body.add(center, BorderLayout.CENTER);
        body.add(bottom, BorderLayout.SOUTH); // under the tree (Miguel, 2026-10-19)
    }

    /** The tree, the filter under it. */
    JComponent component() {
        return body;
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
        indexNames = indexNames(folders);
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

    /** The open folders' git repositories as read: a changed file and a folder holding one take git's colours. */
    void showRepos(List<RepoStatus> repos) {
        List<RepoStatus> before = changes.repos();
        changes.set(repos);
        if (changes.repos() != before) {
            remark();
        }
    }

    /** The files the user opens from now on, and those opened before: a new file among them is marked no more. */
    void watchOpened(OpenedFiles files) {
        stopHearingOpened.run();
        opened = files;
        stopHearingOpened = files.listen(this::remark);
        remark();
    }

    /** Works out which folders hold a marked file, then repaints. */
    private void remark() {
        Set<Path> holding = new HashSet<>();
        Set<Path> towards = new HashSet<>();
        Set<Path> towardsTracked = new HashSet<>();
        for (RepoStatus r : changes.repos()) {
            Path root = Path.of(r.root()).toAbsolutePath().normalize();
            for (RepoStatus.FileChange c : r.changes()) {
                Path file = root.resolve(c.path()).normalize();
                boolean fresh = c.kind() == RepoStatus.Kind.UNTRACKED;
                if (fresh && opened.contains(file)) {
                    continue;
                }
                addFolders(file, root, holding);
                addFolders(file, root, fresh ? towards : towardsTracked);
            }
        }
        marked = holding;
        towardsNew = towards;
        towardsChanged = towardsTracked;
        // a mark that appears or goes changes a row's width, which a repaint alone would not take
        tree.rowsChanged();
        found.rowsChanged();
    }

    /** Adds the folders from {@code file}'s up to {@code root} to {@code into}, stopping at one already there. */
    private static void addFolders(Path file, Path root, Set<Path> into) {
        for (Path p = file.getParent(); p != null && p.startsWith(root); p = p.getParent()) {
            if (!into.add(p)) {
                return;
            }
        }
    }

    RepoChanges changes() {
        return changes;
    }

    /** For tests: where the words beside a row go. */
    void tell(Tell tell) {
        this.tell = tell;
    }

    /**
     * The colour of a node's name: a file's for how it differs from {@code HEAD}, a folder's (or a folder row's) when
     * it holds a changed file or a new one; none for anything else, a new file the user opened included.
     */
    Color colorOf(DefaultMutableTreeNode n) {
        if (!(n.getUserObject() instanceof ViewSpec.TreeNode node) || changes.repos().isEmpty()) {
            return null;
        }
        if (node.link().startsWith(SourceLinks.SCHEME)) {
            Path file = Path.of(SourceLinks.path(node.link()));
            return changes.of(file).filter(k -> k != RepoStatus.Kind.UNTRACKED || !opened.contains(file)).map(RepoChanges::color).orElse(null);
        }
        return folderPath(n).filter(p -> marked.contains(p.toAbsolutePath().normalize())).map(p -> Theme.VCS_MODIFIED).orElse(null);
    }

    /** Whether {@code n} is a file git does not track yet and the user has not opened. */
    boolean isNew(DefaultMutableTreeNode n) {
        if (!(n.getUserObject() instanceof ViewSpec.TreeNode node) || !node.link().startsWith(SourceLinks.SCHEME)) {
            return false;
        }
        Path file = Path.of(SourceLinks.path(node.link()));
        return changes.of(file).filter(k -> k == RepoStatus.Kind.UNTRACKED).isPresent() && !opened.contains(file);
    }

    /** Whether {@code n} is a folder (a folder row or a subfolder) on the way down to a new file not opened yet. */
    boolean holdsNew(DefaultMutableTreeNode n) {
        return folderPath(n).filter(p -> towardsNew.contains(p.toAbsolutePath().normalize())).isPresent();
    }

    /** Whether {@code n} is a file git tracks that differs from {@code HEAD}: changed, added, renamed, in conflict. */
    boolean isChanged(DefaultMutableTreeNode n) {
        if (!(n.getUserObject() instanceof ViewSpec.TreeNode node) || !node.link().startsWith(SourceLinks.SCHEME)) {
            return false;
        }
        return changes.of(Path.of(SourceLinks.path(node.link()))).filter(k -> k != RepoStatus.Kind.UNTRACKED).isPresent();
    }

    /** Whether {@code n} is a folder (a folder row or a subfolder) on the way down to a file {@link #isChanged}. */
    boolean holdsChanged(DefaultMutableTreeNode n) {
        return folderPath(n).filter(p -> towardsChanged.contains(p.toAbsolutePath().normalize())).isPresent();
    }

    /** The repository whose top folder node {@code n} is, a folder row or a subfolder; empty for any other node. */
    Optional<RepoStatus> repoAt(DefaultMutableTreeNode n) {
        return changes.repos().isEmpty() ? Optional.empty() : folderPath(n).flatMap(changes::repoAt);
    }

    /**
     * The path of a folder node: a folder row's configured path, a subfolder's from its row and the names leading to it;
     * empty for a file, a placeholder or a note.
     */
    private Optional<Path> folderPath(DefaultMutableTreeNode n) {
        if (!(n.getUserObject() instanceof ViewSpec.TreeNode node) || node.link().startsWith(SourceLinks.SCHEME)) {
            return Optional.empty();
        }
        TreeNode[] path = n.getPath();
        if (path.length < ROW + 1 || node.label().equals(ViewSpec.TreeNode.MORE) || node.label().startsWith("✗ ")
                || node.children().isEmpty() && node.detail().isEmpty()
                || !(((DefaultMutableTreeNode) path[ROW]).getUserObject() instanceof ViewSpec.TreeNode row)) {
            return Optional.empty();
        }
        Optional<FolderRoot> folder = folder(row.label());
        if (folder.isEmpty()) {
            return Optional.empty();
        }
        Path p = Path.of(folder.get().path());
        for (int i = ROW + 1; i < path.length; i++) {
            if (((DefaultMutableTreeNode) path[i]).getUserObject() instanceof ViewSpec.TreeNode c) {
                p = p.resolve(c.label());
            }
        }
        return Optional.of(p);
    }

    /**
     * A folder's row double-clicked: the Changes view of what the repository holding it has changed inside it, all of it
     * when the row is the repository's top folder, when there is any; else a word beside the row that there is none.
     */
    private void openChanges(TreeView t, TreePath row) {
        String title = title(row);
        Optional<Path> folder = folderPath((DefaultMutableTreeNode) row.getLastPathComponent()).map(p -> p.toAbsolutePath().normalize());
        Optional<RepoStatus> repo = folder.flatMap(changes::repoOf);
        if (repo.isEmpty()) {
            tell.tell(t.tree(), below(t, row), "No changes to see in " + title + ": no git repository holds it");
            return;
        }
        RepoStatus r = repo.get();
        String inside = inside(r, folder.get());
        if (!r.error().isEmpty()) {
            tell.tell(t.tree(), below(t, row), title + " cannot be read: " + r.error());
        } else if (RepoChanges.changesIn(r, inside).isEmpty()) {
            tell.tell(t.tree(), below(t, row), "No changes to see in " + title + (inside.isEmpty() ? ": its working tree matches HEAD" : ": it matches HEAD"));
        } else {
            showChanges(r, inside, title);
        }
    }

    /** {@code folder}'s path inside {@code repo}, relative to its top folder; empty for the top folder itself. */
    private static String inside(RepoStatus repo, Path folder) {
        return Path.of(repo.root()).toAbsolutePath().normalize().relativize(folder).toString().replace('\\', '/');
    }

    /** Whether {@code n} is a folder's top-level row. */
    private static boolean isRow(DefaultMutableTreeNode n) {
        return isRow(n.getPath());
    }

    /**
     * The marks after a node's name: a row's rising arrow while what it holds differs from {@code HEAD} (a repository's
     * top folder's anywhere), and the same arrow on the way down to each file git tracks that differs and on the file
     * (record 97); the dot on the way down to a new file not opened yet; both on a node that has both.
     */
    private Icon marks(DefaultMutableTreeNode n) {
        boolean changed = repoAt(n).filter(r -> !r.changes().isEmpty()).isPresent()
                || isRow(n) && folderPath(n).filter(changes::holdsChanges).isPresent()
                || isChanged(n) || holdsChanged(n);
        boolean fresh = isNew(n) || holdsNew(n);
        if (changed && fresh) {
            if (changedAndNew == null) {
                changedAndNew = new RowIcon(Icons.get(CHANGES_ICON), Icons.get(NEW_ICON));
            }
            return changedAndNew;
        }
        return changed ? Icons.get(CHANGES_ICON) : fresh ? Icons.get(NEW_ICON) : null;
    }

    /** What a folder's row inside a repository says on hover: the branch, and what it holds that the last commit does not. */
    private Optional<String> rowTip(DefaultMutableTreeNode n) {
        if (!isRow(n)) {
            return Optional.empty();
        }
        Optional<Path> folder = folderPath(n).map(p -> p.toAbsolutePath().normalize());
        return folder.flatMap(changes::repoOf).map(r -> changesTip(r, RepoChanges.changesIn(r, inside(r, folder.get())), " here"));
    }

    /**
     * The Changes view of {@code repo} inside {@code folder} (relative to its top folder; empty for all of it), its row
     * called {@code name}; the view keeps its one title, so it replaces the one shown.
     */
    private void showChanges(RepoStatus repo, String folder, String name) {
        intents.dispatch(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(repo.root(), folder, name), Placement.SIDE));
    }

    /** A folder node's name in a title: its row's name, then the subfolders leading to it. */
    private static String title(TreePath p) {
        Object[] path = p.getPath();
        StringBuilder out = new StringBuilder();
        for (int i = ROW; i < path.length; i++) {
            if (((DefaultMutableTreeNode) path[i]).getUserObject() instanceof ViewSpec.TreeNode c) {
                out.append(out.isEmpty() ? "" : "/").append(c.label());
            }
        }
        return out.toString();
    }

    /** Whether {@code path} ends at a folder's top-level row. */
    private static boolean isRow(Object[] path) {
        return path.length == ROW + 1;
    }

    /** What a repository's top folder says on hover: its branch, and what it holds that its last commit does not. */
    static String repoTip(RepoStatus r) {
        return changesTip(r, r.changes(), "");
    }

    /** {@code r}'s branch and its {@code changes}, counted by kind; {@code where} follows the count, " here" for a folder. */
    private static String changesTip(RepoStatus r, List<RepoStatus.FileChange> changes, String where) {
        if (!r.error().isEmpty()) {
            return Plain.text(Clip.keepStart("Git: cannot be read: " + r.error(), 80));
        }
        String on = r.branch().isEmpty() || r.branch().equals("(detached)") ? "Detached HEAD" : "On " + r.branch();
        if (changes.isEmpty()) {
            return Plain.text(on + " · nothing to commit" + where);
        }
        Map<RepoStatus.Kind, Integer> kinds = new EnumMap<>(RepoStatus.Kind.class);
        changes.forEach(c -> kinds.merge(c.kind(), 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        kinds.forEach((k, n) -> parts.add(n + " " + RepoChanges.describe(k)));
        int n = changes.size();
        return Plain.text(Clip.keepStart(on + " · " + n + (r.truncated() ? "+" : "") + " uncommitted" + where + ": " + String.join(", ", parts), 80));
    }

    private static Point below(TreeView t, TreePath row) {
        Rectangle r = t.tree().getPathBounds(row);
        return r == null ? new Point(0, 0) : new Point(r.x + 16, r.y + r.height);
    }

    private Optional<FolderRoot> folder(String name) {
        return folders.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    private static Set<String> indexNames(List<FolderRoot> folders) {
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
                rows.isEmpty() ? List.of(placeholder("No folders yet: right-click the Files tab to add some")) : rows);
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
    }

    /** A folder's row's detail: its path. */
    private static String rowDetail(FolderRoot f) {
        return Labels.shortPath(f.path());
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
        return path.getPathCount() < ROW + 1 || !(((DefaultMutableTreeNode) path.getPathComponent(ROW)).getUserObject() instanceof ViewSpec.TreeNode n)
                ? Optional.empty() : folder(n.label());
    }

    /** The tree in front: the filter's while it has text, else the folders'. */
    private TreeView shown() {
        return searching() ? found : tree;
    }

    /** The folder whose top-level row is selected in the tree in front, or null. */
    private FolderRoot selectedFolder() {
        TreePath p = shown().tree().getSelectionPath();
        return p == null || !isRow(p.getPath()) ? null : folderOf(p).orElse(null);
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
     * The context menu of {@code n}, selected in the tree in front: a file's opens it rendered or as its text and, when
     * it changed, compares it with {@code HEAD}; a folder row's opens its index file; a folder row's and a subfolder's
     * show the changes inside it, and a folder row's gains the window's items for it; any other node has none. In a
     * deletable folder, a file's or a subfolder's menu also moves the selection to the Trash.
     */
    JPopupMenu menuFor(ViewSpec.TreeNode n) {
        JPopupMenu menu = new JPopupMenu();
        TreePath at = shown().tree().getSelectionPath();
        if (n.link().startsWith(SourceLinks.SCHEME)) {
            Path file = Path.of(SourceLinks.path(n.link()));
            menu.add(item("Open in a panel", "text", () -> open.accept(file)));
            menu.add(item("Edit in a panel", "edit", () -> editFile.accept(file)));
            addCompare(menu, file);
            addTrash(menu);
        } else if (entry(n)) {
            addChanges(menu, at);
            addTrash(menu);
        } else if (selectedFolder() != null && selectedFolder().name().equals(n.label())) {
            FolderRoot f = selectedFolder();
            // asked for by name: a missing index says so in the status bar, as any file that cannot be opened
            f.indexFile().ifPresent(index -> menu.add(item("Open " + f.index(), "index", () -> open.accept(index))));
            addChanges(menu, at);
            folderMenu.accept(f, menu);
        }
        return menu;
    }

    /** What a folder row's menu gains from the window, after its own items. */
    void onFolderMenu(BiConsumer<FolderRoot, JPopupMenu> extra) {
        folderMenu = extra;
    }

    /** Shows the changes inside the folder at {@code at}, of the repository holding it; off, saying so, when it has none. */
    private void addChanges(JPopupMenu menu, TreePath at) {
        if (at == null) {
            return;
        }
        Optional<Path> folder = folderPath((DefaultMutableTreeNode) at.getLastPathComponent());
        Optional<RepoStatus> repo = folder.flatMap(changes::repoOf);
        if (repo.isEmpty() || !repo.get().error().isEmpty()) {
            return;
        }
        String title = title(at);
        String inside = inside(repo.get(), folder.get());
        JMenuItem item = changes.holdsChanges(folder.get())
                ? item("Show changes in " + title, "diff", () -> showChanges(repo.get(), inside, title))
                : item("No changes in " + title, "diff", () -> {
                });
        item.setEnabled(changes.holdsChanges(folder.get()));
        menu.add(item);
    }

    /** Compares {@code file} with its {@code HEAD} version when it differs from it. */
    private void addCompare(JPopupMenu menu, Path file) {
        changes.repoOf(file).ifPresent(repo -> {
            String inside = Path.of(repo.root()).toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            repo.changes().stream().filter(c -> c.path().equals(inside)).findFirst().ifPresent(c ->
                    menu.add(item("Compare with HEAD", "diff", () -> intents.dispatch(new Intent.OpenChange(repo.root(), c, ChangeAsk.OPEN)))));
        });
    }

    /** Whether {@code n} is a file or a subfolder, not a folder's row, a placeholder or a note such as "more…". */
    private boolean entry(ViewSpec.TreeNode n) {
        TreePath p = shown().tree().getSelectionPath();
        return p != null && p.getPathCount() > ROW + 1 && !n.label().equals(ViewSpec.TreeNode.MORE) && !n.label().startsWith("✗ ")
                && (n.link().startsWith(SourceLinks.SCHEME) || !n.children().isEmpty() || !n.detail().isEmpty());
    }

    /**
     * The selection as entries of one deletable folder, with their full paths; empty when anything selected is not
     * such an entry, so the Trash item acts on the whole selection or is not offered.
     */
    Optional<Map.Entry<FolderRoot, List<Path>>> trashable() {
        TreePath[] selected = shown().tree().getSelectionPaths();
        if (selected == null || selected.length == 0) {
            return Optional.empty();
        }
        FolderRoot folder = null;
        List<Path> paths = new ArrayList<>(selected.length);
        for (TreePath p : selected) {
            Optional<FolderRoot> f = folderOf(p);
            if (f.isEmpty() || !f.get().deletable() || folder != null && !folder.equals(f.get())
                    || !(((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n)
                    || p.getPathCount() <= ROW + 1 || n.label().equals(ViewSpec.TreeNode.MORE) || n.label().startsWith("✗ ")
                    || !(n.link().startsWith(SourceLinks.SCHEME) || !n.children().isEmpty() || !n.detail().isEmpty())) {
                return Optional.empty();
            }
            folder = f.get();
            paths.add(pathOf(folder, p, n));
        }
        return Optional.of(Map.entry(folder, List.copyOf(paths)));
    }

    /** A file's path from its link; a subfolder's from its folder and the names leading to it. */
    private static Path pathOf(FolderRoot folder, TreePath p, ViewSpec.TreeNode n) {
        if (n.link().startsWith(SourceLinks.SCHEME)) {
            return Path.of(SourceLinks.path(n.link()));
        }
        Path out = Path.of(folder.path());
        for (int i = ROW + 1; i < p.getPathCount(); i++) {
            if (((DefaultMutableTreeNode) p.getPathComponent(i)).getUserObject() instanceof ViewSpec.TreeNode c) {
                out = out.resolve(c.label());
            }
        }
        return out;
    }

    private void addTrash(JPopupMenu menu) {
        trashable().ifPresent(t -> {
            List<Path> paths = t.getValue();
            String what = paths.size() == 1 ? paths.getFirst().getFileName().toString() : paths.size() + " entries";
            menu.addSeparator();
            menu.add(item("Move " + what + " to the Trash…", "trash", () -> moveToTrash(t.getKey(), paths)));
        });
    }

    /** Asks, naming what goes, then moves it to the Trash. */
    private void moveToTrash(FolderRoot folder, List<Path> paths) {
        List<String> names = paths.stream().map(p -> Path.of(folder.path()).relativize(p).toString()).toList();
        String shownNames = names.size() <= 5 ? String.join(", ", names)
                : String.join(", ", names.subList(0, 5)) + " and " + (names.size() - 5) + " more";
        String text = "Move " + (names.size() == 1 ? names.getFirst() : names.size() + " entries (" + shownNames + ")")
                + " to the Trash? They can be put back from there."
                + (folder.listedIn().isEmpty() ? "" : " Their own lines in " + folder.listedIn() + " are taken out.");
        Object[] options = {"Move to Trash", "Cancel"};
        if (questions.ask(shown().tree(), Plain.text(text), "Move to the Trash", options) == 0) {
            intents.dispatch(new Intent.DeleteFiles(folder.name(), paths.stream().map(Path::toString).toList()));
        }
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
        Set<String> opening = new HashSet<>(rows.stream().map(ViewSpec.TreeNode::label).toList());
        ViewSpec.TreeNode root = new ViewSpec.TreeNode("Found", "", rows.isEmpty() ? List.of(placeholder(NO_MATCH)) : rows);
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
            if (p.getPathCount() >= ROW + 1 && ((DefaultMutableTreeNode) p.getPathComponent(ROW)).getUserObject() instanceof ViewSpec.TreeNode row
                    && opening.contains(row.label())
                    && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                    && !n.children().isEmpty() && (p.getPathCount() == ROW + 1 || !matches(n.label(), q))) {
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
