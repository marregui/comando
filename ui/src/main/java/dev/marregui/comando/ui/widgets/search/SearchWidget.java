package dev.marregui.comando.ui.widgets.search;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;
import dev.marregui.comando.core.port.SearchPort;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.Tooltips;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.tree.TreeView;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;

/**
 * Find in files and conversations (record 139), as a view of the side pane: a field, then the hits by where they are,
 * as the Changes view shows a repository's files ({@link SearchTree}: each Files-tab folder, its folders and files,
 * each file's hits named by line and heading; then the conversations). Every row is open but those the user closed;
 * the selection is kept as hits arrive. Moving the selection shows a file's hit in the workspace's preview tab, every
 * hit of the file marked, without taking the keyboard from the rows (record 141); a conversation's hit reads in its
 * row's tooltip. Enter or a double click on a file's hit keeps its preview tab, on a conversation's opens it in its
 * session. ↓ or Enter in the field moves to the first hit; F7 and ⇧F7 walk the hits in the order the view shows
 * them. The text and the toggles stay in the {@link SearchHub} while comando runs.
 */
public final class SearchWidget implements Widget<ViewSpec.Search>, Copyable {

    /**
     * How long hits that arrive are gathered before the rows are built again: a search hands hits over file by file,
     * and a tree built per file would cost the EDT more than it shows. Unmeasured: under a keystroke's pause.
     */
    static final int GATHER_MILLIS = 100;
    /**
     * How long after the last keystroke a search starts; set by record 139, unmeasured, as {@code FindBar}'s pause is.
     */
    static final int TYPING_MILLIS = 200;
    /** Rows looked at, at most, for rows to open after a build; as the Changes view. */
    private static final int MAX_ROWS_OPENED = 3_000;

    private final SearchHub.Listener asked = this::ask;
    private final JPanel body = new JPanel(new BorderLayout());
    /** The rows the user closed, by {@link SearchTree#key}: every other row is open. */
    private final Set<String> closed = new HashSet<>();
    private final JToggleButton conversations = toggle("session", "Conversations",
            "Search the stored conversations of Claude Code");
    private final JTextField field = new JTextField();
    private final JToggleButton files = toggle("files", "Files",
            "Search the folders of the Kit, Brain and Scratchpad tabs");
    private final JLabel foot = Plain.only(new JLabel(" "));
    private final Timer gather = new Timer(GATHER_MILLIS, _ -> {
        build();
        firstHeard();
    });
    private final List<SearchHit> hits = new ArrayList<>();
    private final SearchHub hub;
    private final Map<String, SearchHit> targets = new HashMap<>();
    private final TreeView tree = new TreeView();
    private final Debouncer typing = new Debouncer(TYPING_MILLIS, this::search);
    private boolean built;
    private boolean cut;
    private int failed;
    /** ↓ or ⏎ came before the new search's first answer: its first hit is selected as it comes. */
    private boolean firstWhenHeard;
    /** The field asked for the keyboard before it showed: it takes it as it shows. */
    private boolean focusWhenShown = true;
    /** The search whose answers count; another's are dropped here, on the thread that decides. */
    private Listening listening;
    /** The text of the search listed. */
    private String query = "";
    /** True while rows are built: the selection they restore and the rows they open are not the user's moves. */
    private boolean rendering;
    /** The new search has said nothing yet, so the hits before it still show. */
    private boolean replacing;
    private ViewSpec.TreeNode root = ViewSpec.TreeNode.NONE;
    private boolean searching;
    /** The text of the search last started; it becomes {@link #query} as its first answer replaces the rows. */
    private String started = "";

    public SearchWidget(final SearchHub hub) {
        this.hub = hub;
        files.setSelected(hub.files());
        conversations.setSelected(hub.conversations());
        gather.setRepeats(false);
        layOut();
        listen();
        Capabilities.mark(tree.component(), this);
        hub.listener(asked);
        hub.viewer().onStep(this::step);
        field.setText(hub.text());
        if (!hub.text().isBlank()) {
            search();
        }
        // the view's tab may come forward after this, or the side pane show later: the field takes the keyboard then
        field.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && field.isShowing() && focusWhenShown) {
                focusWhenShown = false;
                field.requestFocusInWindow();
            }
        });
    }

    @Override
    public JComponent component() {
        return body;
    }

    @Override
    public void render(final ViewSpec.Search view) {
        // the view carries no data: the hits are this widget's
    }

    @Override
    public void unmount() {
        typing.stop();
        gather.stop();
        silence();
        if (hub.holds(asked)) {
            hub.listener(null);
            hub.viewer().onStep(null);
        }
    }

    @Override
    public String copyText() {
        return tree.copyText();
    }

    @Override
    public boolean canCopy() {
        return built;
    }

    /** A toggle of what is searched: an icon, named {@code name}, its tooltip {@code tip}; on at first. */
    private static JToggleButton toggle(final String icon, final String name, final String tip) {
        final JToggleButton b = new JToggleButton(Icons.get(icon));
        b.setToolTipText(Tooltips.wrapped(tip));
        b.getAccessibleContext().setAccessibleName(name);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        // a click leaves the keyboard where it was
        b.setFocusable(false);
        return b;
    }

    private static JMenuItem item(final String text, final String icon, final Runnable r) {
        final JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(_ -> r.run());
        return i;
    }

    /** Searches {@code text} with the keyboard in the field; the text already searched is not searched again. */
    private void ask(final String text) {
        if (!text.equals(field.getText())) {
            field.setText(text);
            search();
        }
        field.selectAll();
        if (field.isShowing()) {
            field.requestFocusInWindow();
        } else {
            focusWhenShown = true;
        }
    }

    private void layOut() {
        field.putClientProperty("JTextField.leadingIcon", Icons.get("search"));
        field.putClientProperty("JTextField.placeholderText", "Find in files and conversations");
        field.getAccessibleContext().setAccessibleName(ViewSpec.Search.TITLE);
        tree.tree().setRootVisible(false);
        tree.tree().getAccessibleContext().setAccessibleName("Hits");
        field.putClientProperty("JTextField.showClearButton", true);
        tree.icons(this::icon);
        tree.tips(this::tip);
        foot.setForeground(Theme.MUTED);
        final JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP / 2, 0));
        toggles.add(files);
        toggles.add(conversations);
        final JPanel top = new JPanel(new BorderLayout(0, Theme.GAP / 2));
        top.setBorder(BorderFactory.createEmptyBorder(Theme.GAP, Theme.GAP, Theme.GAP / 2, Theme.GAP));
        top.add(field, BorderLayout.NORTH);
        top.add(toggles, BorderLayout.SOUTH);
        foot.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP, 2, Theme.GAP));
        body.add(top, BorderLayout.NORTH);
        body.add(tree.component(), BorderLayout.CENTER);
        body.add(foot, BorderLayout.SOUTH);
    }

    private void listen() {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                typing.request();
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                typing.request();
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                // attributes only
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN, KeyEvent.VK_ENTER -> toFirstHit();
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });
        for (final JToggleButton toggle : List.of(files, conversations)) {
            toggle.addActionListener(_ -> {
                hub.files(files.isSelected());
                hub.conversations(conversations.isSelected());
                search();
            });
        }
        tree.onActivate(this::activate);
        tree.onMenu(this::menu);
        tree.tree().addTreeSelectionListener(_ -> {
            final SearchHit hit = selectedHit();
            if (!rendering && hit != null && tree.tree().getSelectionCount() == 1) {
                hub.viewer().show(hit, SearchTree.sameSource(hits, hit), false);
            }
        });
        tree.tree().addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(final TreeExpansionEvent e) {
                if (!rendering) {
                    closed.remove(SearchTree.key(e.getPath()));
                }
            }

            @Override
            public void treeCollapsed(final TreeExpansionEvent e) {
                if (!rendering) {
                    closed.add(SearchTree.key(e.getPath()));
                }
            }
        });
    }

    // ---- searching

    /** Runs the text in the field, or, with no text or nothing to search, ends the one running and clears the rows. */
    private void search() {
        typing.stop();
        final String text = field.getText();
        hub.text(text);
        if (text.isBlank() || !files.isSelected() && !conversations.isSelected()) {
            silence();
            clear();
            foot.setText(text.isBlank() ? " " : "Nothing to search: turn on Files or Conversations");
            return;
        }
        final Listening next = new Listening();
        listening = next;
        started = text;
        replacing = true;
        searching = true;
        foot.setText("Searching…");
        final List<FolderRoot> roots = files.isSelected() ? hub.folders().get() : List.of();
        hub.port().get().search(new SearchQuery(text, roots, conversations.isSelected()), next);
    }

    private void silence() {
        listening = null;
        searching = false;
        hub.port().get().cancel();
    }

    private void clear() {
        replacing = false;
        firstWhenHeard = false;
        query = "";
        started = "";
        hits.clear();
        cut = false;
        failed = 0;
        build();
    }

    private void heard(final List<SearchHit> found) {
        if (replacing) {
            replaced();
        }
        hits.addAll(found);
        if (!gather.isRunning()) {
            gather.start();
        }
    }

    private void ended(final boolean wasCut, final int notRead) {
        if (replacing) {
            replaced();
        }
        listening = null;
        searching = false;
        cut = wasCut;
        failed = notRead;
        gather.stop();
        build();
        firstHeard();
    }

    /** The first hit of a search the keyboard asked for before it answered, once the rows show it. */
    private void firstHeard() {
        if (firstWhenHeard && !hits.isEmpty()) {
            firstWhenHeard = false;
            toFirstHit();
        }
    }

    /** The first answer of a new search: the rows of the one before go, and the closed rows with them. */
    private void replaced() {
        replacing = false;
        query = started;
        hits.clear();
        closed.clear();
        cut = false;
        failed = 0;
    }

    // ---- the rows

    /** Builds the rows of the hits heard, keeping the rows the user closed and the hit selected. */
    private void build() {
        final SearchHit selected = selectedHit();
        root = SearchTree.build(hits, targets);
        rendering = true;
        try {
            if (built) {
                tree.update(root);
            } else {
                built = true;
                tree.setRoot(root, 1);
            }
            openAllButClosed();
            if (selected != null && !selected.equals(selectedHit())) {
                select(selected);
            }
        } finally {
            rendering = false;
        }
        count();
    }

    private void openAllButClosed() {
        for (int i = 0; i < tree.tree().getRowCount() && i < MAX_ROWS_OPENED; i++) {
            final TreePath p = tree.tree().getPathForRow(i);
            if (!((DefaultMutableTreeNode) p.getLastPathComponent()).isLeaf() && !tree.tree().isExpanded(p)
                    && !closed.contains(SearchTree.key(p))) {
                tree.tree().expandRow(i);
            }
        }
    }

    /** The foot: how many hits, then what the count leaves out. */
    private void count() {
        final StringBuilder text = new StringBuilder();
        if (hits.isEmpty()) {
            text.append(query.isEmpty() && !searching ? " " : "no matches");
        } else {
            final long sources = hits.stream().map(SearchHit::source).distinct().count();
            text.append(String.format(Locale.ROOT, "%,d %s in %,d %s", hits.size(), hits.size() == 1 ? "hit" : "hits",
                    sources, sources == 1 ? "source" : "sources"));
        }
        if (cut) {
            text.append(String.format(Locale.ROOT, ", cut at %,d", hits.size()));
        }
        if (failed > 0) {
            text.append(String.format(Locale.ROOT, ", %,d not read", failed));
        }
        if (searching) {
            text.append(", searching…");
        }
        foot.setText(text.toString());
    }

    private SearchHit selectedHit() {
        final ViewSpec.TreeNode n = tree.selected();
        return n == null ? null : targets.get(n.link());
    }

    /** Selects the row of {@code hit} that shows, the hit's own before its file's, and scrolls it into view. */
    private boolean select(final SearchHit hit) {
        TreePath source = null;
        for (int i = 0; i < tree.tree().getRowCount(); i++) {
            final TreePath p = tree.tree().getPathForRow(i);
            if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                    && hit.equals(targets.get(n.link()))) {
                if (n.link().startsWith(SearchTree.HIT)) {
                    show(p);
                    return true;
                }
                source = source == null ? p : source;
            }
        }
        if (source != null) {
            show(source);
            return true;
        }
        return false;
    }

    private void show(final TreePath p) {
        tree.tree().setSelectionPath(p);
        tree.tree().scrollPathToVisible(p);
    }

    /** Moves the keyboard to the rows, on the first hit. */
    private void toFirstHit() {
        typing.stop();
        if (!started.equals(field.getText())) {
            search();
        }
        if (replacing) {
            // the hits shown are the last search's: the first of the new one is taken as it comes
            firstWhenHeard = true;
            return;
        }
        final List<SearchHit> order = SearchTree.order(root, targets);
        if (!order.isEmpty()) {
            if (selectedHit() == null) {
                select(order.getFirst());
            }
            tree.tree().requestFocusInWindow();
        }
    }

    /** The hit {@code delta} rows of hits away from the one selected, as the rows show them, opened if closed. */
    private void step(final int delta) {
        final List<SearchHit> order = SearchTree.order(root, targets);
        if (order.isEmpty()) {
            return;
        }
        final int at = order.indexOf(selectedHit());
        final int next = at < 0 ? 0 : Math.floorMod(at + delta, order.size());
        if (!select(order.get(next))) {
            reveal(order.get(next));
        }
    }

    /** Opens the rows above the row of {@code hit}, which the user had closed, and selects it; the others stay. */
    private void reveal(final SearchHit hit) {
        final DefaultMutableTreeNode top = (DefaultMutableTreeNode) tree.tree().getModel().getRoot();
        for (final Enumeration<TreeNode> e = top.depthFirstEnumeration(); e.hasMoreElements();) {
            if (e.nextElement() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof ViewSpec.TreeNode n
                    && hit.equals(targets.get(n.link()))) {
                // children come first: a hit's own row before the file row that also stands for it
                final TreePath p = new TreePath(node.getPath());
                tree.tree().expandPath(p.getParentPath());
                show(p);
                return;
            }
        }
    }

    private void activate(final ViewSpec.TreeNode n) {
        final SearchHit hit = n == null ? null : targets.get(n.link());
        if (hit == null) {
            return;
        }
        if (hit.kind() == SearchHit.Kind.CONVERSATION) {
            hub.opener().open(hit, query);
        } else {
            hub.viewer().show(hit, SearchTree.sameSource(hits, hit), false);
            hub.viewer().pin(hit);
        }
    }

    private void menu(final ViewSpec.TreeNode n, final MouseEvent e) {
        final SearchHit hit = targets.get(n.link());
        if (hit == null) {
            return;
        }
        final JPopupMenu menu = new JPopupMenu();
        menu.add(item(hit.kind() == SearchHit.Kind.CONVERSATION && !hub.opener().inSession(hit.conversation())
                ? "Import…" : "Open", "open-file", () -> activate(n)));
        if (hit.kind() != SearchHit.Kind.CONVERSATION) {
            menu.add(item("Show in the sidebar", "files", () -> hub.opener().showInFiles(hit)));
        }
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    /** A conversation's hit reads in its row's tooltip: the message around the match; other rows keep the default. */
    private String tip(final DefaultMutableTreeNode node) {
        final SearchHit hit = node.getUserObject() instanceof ViewSpec.TreeNode n ? targets.get(n.link()) : null;
        if (hit == null || hit.kind() != SearchHit.Kind.CONVERSATION || hit.context().isBlank()) {
            return null;
        }
        return Tooltips.wrapped(hit.context().strip());
    }

    private Icon icon(final ViewSpec.TreeNode n) {
        if (n.link().startsWith(SearchTree.HIT)) {
            return TreeView.NO_ICON;
        }
        final SearchHit hit = targets.get(n.link());
        if (hit != null) {
            return Icons.get(hit.kind() == SearchHit.Kind.CONVERSATION ? "session" : "file");
        }
        if (n.children().isEmpty()) {
            return null;
        }
        return Icons.get(SearchTree.CONVERSATIONS.equals(n.label()) ? "session" : "folder");
    }

    /** Hears one search, and hands what it says to the EDT, where a search no longer running is dropped. */
    private final class Listening implements SearchPort.Sink {

        @Override
        public void hits(final List<SearchHit> found) {
            Edt.later(() -> {
                if (listening == this) {
                    heard(found);
                }
            });
        }

        @Override
        public void done(final boolean wasCut, final int notRead) {
            Edt.later(() -> {
                if (listening == this) {
                    ended(wasCut, notRead);
                }
            });
        }
    }
}
