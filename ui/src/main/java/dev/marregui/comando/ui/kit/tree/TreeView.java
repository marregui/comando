package dev.marregui.comando.ui.kit.tree;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.border.AbstractBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import com.formdev.flatlaf.ui.FlatTreeUI;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.FocusRing;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

/**
 * A {@link ViewSpec.TreeNode} tree: details muted after the label; a double click on a leaf, or ⏎ on the selected
 * node, activates it; a right click asks for its menu. The one tree every widget uses.
 */
public final class TreeView {

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode())) {
        /** The click count of the mouse event being handled; 0 outside one. */
        private int clicks;

        @Override
        protected void processMouseEvent(final MouseEvent e) {
            clicks = e.getClickCount();
            firstPress = e.getID() == MouseEvent.MOUSE_PRESSED && clicks == 1;
            if (firstPress) {
                toggledByPress = null;
            }
            try {
                super.processMouseEvent(e);
            } finally {
                clicks = 0;
                firstPress = false;
            }
        }

        @Override
        public String convertValueToText(final Object value, final boolean selected, final boolean expanded, final boolean leaf, final int row, final boolean focus) {
            // a node's name, which the look asks for at every row: the record's own text prints the whole subtree
            return value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof ViewSpec.TreeNode node
                    ? node.label() : super.convertValueToText(value, selected, expanded, leaf, row, focus);
        }

        @Override
        protected void paintComponent(final Graphics g) {
            super.paintComponent(g);
            if (sidebar) {
                paintSidebar(g);
            }
        }

        @Override
        public void updateUI() {
            if (sidebar) {
                setUI(new SidebarTreeUI());
            } else {
                super.updateUI();
            }
        }

        @Override
        public int getToggleClickCount() {
            // the look asks while it handles a press: with toggleOnClick only a sequence's first click opens or closes
            return toggleOnClick ? (clicks == 1 ? 1 : 0) : super.getToggleClickCount();
        }
    };
    /** Whether one click opens or closes a folder; else a double click does, as the look has it. */
    private boolean toggleOnClick;
    /** Whether the first press of a click sequence is being handled. */
    private boolean firstPress;
    /** The node that first press opened or closed, so a double click can put it back; null when it toggled none. */
    private TreePath toggledByPress;
    private final JScrollPane scroll = new JScrollPane(tree);
    private Consumer<ViewSpec.TreeNode> onActivate = n -> { };
    private BiConsumer<ViewSpec.TreeNode, MouseEvent> onMenu = (n, e) -> { };
    private Function<ViewSpec.TreeNode, Icon> icons = n -> null;
    private Function<DefaultMutableTreeNode, Color> colors = n -> null;
    private Function<DefaultMutableTreeNode, Icon> nodeIcons = n -> null;
    private Function<DefaultMutableTreeNode, Icon> trailing = n -> null;
    private Function<DefaultMutableTreeNode, String> tips = n -> null;
    /** Whether the tree has the sidebar's look (record 98): see {@link #sidebar}. */
    private boolean sidebar;
    /** The header above a node, null for none; only with the sidebar's look. */
    private Function<DefaultMutableTreeNode, String> headers = n -> null;
    /** A row under a header: the space the tree paints the header in, then the row. */
    private final JPanel headed = new JPanel(new BorderLayout());
    /** What an icon function gives for a node drawn with no icon at all, not even the look's own (record 119). */
    public static final Icon NO_ICON = new ImageIcon();
    /** The height of a header's space above its row. */
    static final int HEADER = 24;
    /** A cell with more than a name: the look's label, the mark after it if any, then the detail, muted. */
    private final JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    private final JLabel mark = new JLabel();
    private final JLabel detail = new JLabel();

    public TreeView() {
        cell.setOpaque(false);
        mark.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        detail.setForeground(Theme.MUTED);
        detail.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_S, 0, 0));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(final TreeExpansionEvent e) {
                pressed(e.getPath());
            }

            @Override
            public void treeCollapsed(final TreeExpansionEvent e) {
                pressed(e.getPath());
            }

            private void pressed(final TreePath p) {
                if (firstPress) {
                    toggledByPress = p;
                }
            }
        });
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(final JTree t, final Object value, final boolean sel, final boolean expanded, final boolean leaf, final int row, final boolean focus) {
                final JLabel l = (JLabel) super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, focus);
                if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof ViewSpec.TreeNode node) {
                    // plain text only: a label holding HTML parses it again for every row it renders
                    final boolean failed = node.label().startsWith("✗ ");
                    l.setText(Plain.text(node.label()));
                    final String tip = tips.apply(n);
                    l.setToolTipText(tip != null ? tip : node.link().isEmpty() ? null : "Double-click to open");
                    Icon icon = nodeIcons.apply(n);
                    if (icon == null) {
                        icon = icons.apply(node);
                    }
                    if (icon == NO_ICON) {
                        l.setIcon(null);
                    } else if (icon != null) {
                        l.setIcon(icon);
                    }
                    Color color = failed ? Theme.DANGER : sel ? null : colors.apply(n);
                    if (color == null && sidebar) {
                        color = sel ? Theme.tint().highlight : SidebarLook.TEXT;
                    }
                    if (color != null) {
                        l.setForeground(color);
                    }
                    final Icon after = trailing.apply(n);
                    if (after != null || !node.detail().isEmpty()) {
                        // the mark belongs to the name: the detail, a path that may run past the edge, comes after it
                        cell.removeAll();
                        cell.add(l);
                        if (after != null) {
                            mark.setIcon(after);
                            cell.add(mark);
                        }
                        if (!node.detail().isEmpty()) {
                            detail.setText(Plain.text(node.detail()));
                            detail.setFont(l.getFont());
                            cell.add(detail);
                        }
                        cell.setToolTipText(l.getToolTipText());
                        return underHeader(n, cell);
                    }
                    return underHeader(n, l);
                }
                return l;
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    final ViewSpec.TreeNode n = nodeAt(e);
                    if (n != null && n.children().isEmpty()) {
                        onActivate.accept(n);
                    }
                }
            }

            @Override
            public void mousePressed(final MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                popup(e);
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate");
        tree.getActionMap().put("activate", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                final ViewSpec.TreeNode n = selected();
                if (n != null) {
                    onActivate.accept(n);
                }
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);
        FocusRing.install(scroll);
    }

    /** The look's tree, which centres a row's open-and-close mark on the row below its header's space, not on both. */
    private final class SidebarTreeUI extends FlatTreeUI {
        @Override
        protected void paintExpandControl(final Graphics g, final Rectangle clip, final Insets insets, final Rectangle bounds, final TreePath path, final int row,
                                          final boolean expanded, final boolean beenExpanded, final boolean leaf) {
            final Rectangle below = headers.apply((DefaultMutableTreeNode) path.getLastPathComponent()) == null
                    ? bounds : new Rectangle(bounds.x, bounds.y + HEADER, bounds.width, bounds.height - HEADER);
            super.paintExpandControl(g, clip, insets, below, path, row, expanded, beenExpanded, leaf);
        }
    }

    /** {@code c}, below the space of its header when {@code n} has one in the sidebar's look. */
    private Component underHeader(final DefaultMutableTreeNode n, final JComponent c) {
        if (!sidebar || headers.apply(n) == null) {
            return c;
        }
        headed.removeAll();
        headed.add(Box.createVerticalStrut(HEADER), BorderLayout.NORTH);
        headed.add(c, BorderLayout.CENTER);
        headed.setToolTipText(c.getToolTipText());
        return headed;
    }

    /**
     * The sidebar's marks over the painted rows: the report's bar down the left edge of each selected row, then each
     * header in its space above its row, on the ground the selection's band does not reach: the header's words in
     * spaced capitals in the tint's body, and a hairline of the tint's shade on to the edge; an empty header, the
     * hairline alone.
     */
    private void paintSidebar(final Graphics g0) {
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            final Rectangle clip = g.getClipBounds();
            final int first = clip == null ? 0 : Math.max(0, tree.getClosestRowForLocation(0, clip.y));
            final int last = clip == null ? tree.getRowCount() - 1 : tree.getClosestRowForLocation(0, clip.y + clip.height);
            final Font font = headerFont();
            for (int row = first; row <= last && row >= 0; row++) {
                final Rectangle r = tree.getRowBounds(row);
                final TreePath path = tree.getPathForRow(row);
                if (r == null || path == null) {
                    continue;
                }
                final String header = headers.apply((DefaultMutableTreeNode) path.getLastPathComponent());
                final int top = header == null ? 0 : HEADER;
                if (tree.isRowSelected(row)) {
                    SidebarLook.bar(g, new Rectangle(0, r.y + top, SidebarLook.BAR, r.height - top));
                }
                if (header != null) {
                    g.setColor(Theme.ground());
                    g.fillRect(0, r.y, tree.getWidth(), HEADER);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setFont(font);
                    final String text = header.toUpperCase(Locale.ROOT);
                    final FontMetrics m = g.getFontMetrics();
                    final int baseline = r.y + HEADER - 7;
                    g.setColor(Theme.tint().body);
                    g.drawString(text, 8, baseline);
                    // an empty header is the hairline alone, a separator (record 118)
                    final int from = text.isEmpty() ? 8 : 8 + m.stringWidth(text) + 8;
                    if (from < tree.getWidth() - 8) {
                        g.setColor(Tint.over(Theme.ground(), Theme.tint().shade, 0.45f));
                        g.fillRect(from, r.y + hairline(m), tree.getWidth() - 8 - from, 1);
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** How far below the top of a header's space its hairline runs, in the header's font {@code m}. */
    static int hairline(final FontMetrics m) {
        return HEADER - 7 - m.getAscent() / 2 + 1;
    }

    /**
     * Space above the first row as far as a separator's hairline runs below the row before it, the hairline left out
     * (record 118): the first row sits as far from the top as from the hairline under it. Follows the font.
     */
    public void spaceAbove() {
        tree.setBorder(new AbstractBorder() {
            @Override
            public Insets getBorderInsets(final Component c, final Insets insets) {
                insets.set(hairline(c.getFontMetrics(headerFont())), 0, 0, 0);
                return insets;
            }
        });
    }

    private Font headerFont() {
        return SidebarLook.headerFont(tree.getFont());
    }

    /**
     * Gives the tree the sidebar's look (record 98): the transcript's ground, names in its ivory, the selection a band
     * of the tint's shade with the name in its highlight and the report's bar at the left edge, and {@code headers}'
     * text above the nodes it names one for, in spaced capitals in the tint, over a hairline (the hairline alone for an
     * empty text); rows then take their own heights.
     */
    public void sidebar(final Function<DefaultMutableTreeNode, String> headers) {
        this.sidebar = true;
        this.headers = headers;
        headed.setOpaque(false);
        tree.updateUI();
        SidebarLook.tree(tree);
        SidebarLook.ground(scroll);
        tree.setRowHeight(0);
    }

    private void popup(final MouseEvent e) {
        if (e.isPopupTrigger()) {
            final TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            if (path != null) {
                // a right click inside the selection keeps it, so a menu can act on every selected node
                if (!tree.isPathSelected(path)) {
                    tree.setSelectionPath(path);
                }
                final ViewSpec.TreeNode n = node(path);
                if (n != null) {
                    onMenu.accept(n, e);
                }
            }
        }
    }

    private ViewSpec.TreeNode nodeAt(final MouseEvent e) {
        final TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        return path == null ? null : node(path);
    }

    private static ViewSpec.TreeNode node(final TreePath path) {
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n ? n : null;
    }

    /**
     * The parent of {@code child} when it is the selected node, else null; for keys built from a node and its parent.
     */
    public ViewSpec.TreeNode parentOf(final ViewSpec.TreeNode child) {
        final TreePath path = tree.getSelectionPath();
        if (path == null || path.getPathCount() < 2 || node(path) != child) {
            return null;
        }
        return node(path.getParentPath());
    }

    public ViewSpec.TreeNode selected() {
        final TreePath path = tree.getSelectionPath();
        return path == null ? null : node(path);
    }

    public JComponent component() {
        return scroll;
    }

    public JTree tree() {
        return tree;
    }

    public void onActivate(final Consumer<ViewSpec.TreeNode> handler) {
        onActivate = handler;
    }

    public void onMenu(final BiConsumer<ViewSpec.TreeNode, MouseEvent> handler) {
        onMenu = handler;
    }

    /** The icon of each node; null keeps the look's own folder and leaf icons. */
    public void icons(final Function<ViewSpec.TreeNode, Icon> icons) {
        this.icons = icons;
    }

    /**
     * The colour of each node's label, given its tree node so a caller can look at the nodes above it; null keeps the
     * look's own. A selected node keeps the selection's colour.
     */
    public void colors(final Function<DefaultMutableTreeNode, Color> colors) {
        this.colors = colors;
    }

    /** The icon of each node, given its tree node; null falls back to {@link #icons}. */
    public void nodeIcons(final Function<DefaultMutableTreeNode, Icon> icons) {
        this.nodeIcons = icons;
    }

    /** An icon after a node's text, such as a mark that it holds changes; null for none. */
    public void trailing(final Function<DefaultMutableTreeNode, Icon> trailing) {
        this.trailing = trailing;
    }

    /** The tooltip of a node, or null for the default: "Double-click to open" on a node with a link, else none. */
    public void tips(final Function<DefaultMutableTreeNode, String> tips) {
        this.tips = tips;
    }

    /**
     * Lays out every shown row again, for a caller whose cells changed width while the tree did not: a mark appeared
     * after a name. A repaint alone keeps each row's first width and cuts what the wider cell adds.
     */
    public void rowsChanged() {
        final DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
        for (int i = 0; i < tree.getRowCount(); i++) {
            model.nodeChanged((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent());
        }
    }

    /**
     * One click opens or closes a folder, and the second click of a double click does neither; off by default, where
     * a double click opens or closes it.
     */
    public void toggleOnClick(final boolean on) {
        toggleOnClick = on;
    }

    /**
     * Puts {@code path} back as it was before the double click whose first click opened or closed it, for a caller
     * whose double click on that node does something else; nothing when that click toggled no node or another.
     */
    public void undoClickToggle(final TreePath path) {
        if (path.equals(toggledByPress)) {
            if (tree.isExpanded(path)) {
                tree.collapsePath(path);
            } else {
                tree.expandPath(path);
            }
        }
        toggledByPress = null;
    }

    /** Shows {@code root}, expanded to {@code levels} below it within the first 500 rows. */
    public void setRoot(final ViewSpec.TreeNode root, final int levels) {
        tree.setModel(new DefaultTreeModel(build(root)));
        for (int i = 0; i < Math.min(tree.getRowCount(), 500); i++) {
            if (((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getLevel() < levels) {
                tree.expandRow(i);
            }
        }
    }

    /**
     * Shows {@code root} in place of the tree shown, keeping what the user opened and selected: nodes are matched
     * by their labels from the root, so a refresh that adds or removes files leaves the rest as it was. Nothing
     * changes when {@code root} equals the tree shown.
     */
    public void update(final ViewSpec.TreeNode root) {
        final Object shown = ((DefaultMutableTreeNode) tree.getModel().getRoot()).getUserObject();
        if (root.equals(shown)) {
            return;
        }
        final List<List<String>> expanded = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            final TreePath p = tree.getPathForRow(i);
            if (tree.isExpanded(p)) {
                expanded.add(labels(p));
            }
        }
        final List<List<String>> selected = new ArrayList<>();
        final TreePath[] selection = tree.getSelectionPaths();
        if (selection != null) {
            for (final TreePath p : selection) {
                selected.add(labels(p));
            }
        }
        final Rectangle view = tree.getVisibleRect();
        tree.setModel(new DefaultTreeModel(build(root)));
        for (final List<String> path : expanded) {
            final TreePath p = find(path);
            if (p != null) {
                tree.expandPath(p);
            }
        }
        final List<TreePath> reselect = new ArrayList<>();
        for (final List<String> path : selected) {
            final TreePath p = find(path);
            if (p != null) {
                reselect.add(p);
            }
        }
        if (!reselect.isEmpty()) {
            tree.setSelectionPaths(reselect.toArray(TreePath[]::new));
        }
        tree.scrollRectToVisible(view);
    }

    private static List<String> labels(final TreePath path) {
        final List<String> out = new ArrayList<>(path.getPathCount());
        for (final Object o : path.getPath()) {
            out.add(((DefaultMutableTreeNode) o).getUserObject() instanceof ViewSpec.TreeNode n ? n.label() : "");
        }
        return out;
    }

    /** The path whose labels are {@code labels}, from the root; null when a node is gone. */
    private TreePath find(final List<String> labels) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getModel().getRoot();
        if (labels.isEmpty() || !(node.getUserObject() instanceof ViewSpec.TreeNode r) || !r.label().equals(labels.getFirst())) {
            return null;
        }
        TreePath path = new TreePath(node);
        for (final String label : labels.subList(1, labels.size())) {
            DefaultMutableTreeNode next = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                final DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
                if (c.getUserObject() instanceof ViewSpec.TreeNode n && n.label().equals(label)) {
                    next = c;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            node = next;
            path = path.pathByAddingChild(node);
        }
        return path;
    }

    private static DefaultMutableTreeNode build(final ViewSpec.TreeNode n) {
        final DefaultMutableTreeNode node = new DefaultMutableTreeNode(n);
        for (final ViewSpec.TreeNode c : n.children()) {
            node.add(build(c));
        }
        return node;
    }

    /** The tree as indented text. */
    public String copyText() {
        final StringBuilder sb = new StringBuilder();
        append(sb, (DefaultMutableTreeNode) tree.getModel().getRoot(), 0);
        return sb.toString();
    }

    private static void append(final StringBuilder sb, final DefaultMutableTreeNode n, final int depth) {
        if (n.getUserObject() instanceof ViewSpec.TreeNode node) {
            sb.append("  ".repeat(depth)).append(node.label());
            if (!node.detail().isEmpty()) {
                sb.append("  ").append(node.detail());
            }
            sb.append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            append(sb, (DefaultMutableTreeNode) n.getChildAt(i), depth + 1);
        }
    }
}
