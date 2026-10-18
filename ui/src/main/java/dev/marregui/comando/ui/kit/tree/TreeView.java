package dev.marregui.comando.ui.kit.tree;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A {@link ViewSpec.TreeNode} tree: details muted after the label; a double click or Enter activates a
 * node; a right click asks for its menu. The one tree every widget uses.
 */
public final class TreeView {

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final JScrollPane scroll = new JScrollPane(tree);
    private Consumer<ViewSpec.TreeNode> onActivate = n -> { };
    private BiConsumer<ViewSpec.TreeNode, MouseEvent> onMenu = (n, e) -> { };
    private Function<ViewSpec.TreeNode, Icon> icons = n -> null;

    public TreeView() {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean focus) {
                JLabel l = (JLabel) super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, focus);
                if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof ViewSpec.TreeNode node) {
                    String label = node.label().startsWith("✗ ") ? "<span style='color:" + Theme.hex(Theme.DANGER) + "'>" + MarkdownHtml.escape(node.label()) + "</span>"
                            : MarkdownHtml.escape(node.label());
                    l.setText(node.detail().isEmpty() && !node.label().startsWith("✗ ") ? Plain.text(node.label())
                            : "<html>" + label + (node.detail().isEmpty() ? "" : "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>"
                            + MarkdownHtml.escape(node.detail()) + "</span>") + "</html>");
                    l.setToolTipText(node.link().isEmpty() ? null : "Double-click to open");
                    Icon icon = icons.apply(node);
                    if (icon != null) {
                        l.setIcon(icon);
                    }
                }
                return l;
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    ViewSpec.TreeNode n = nodeAt(e);
                    if (n != null && n.children().isEmpty()) {
                        onActivate.accept(n);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate");
        tree.getActionMap().put("activate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ViewSpec.TreeNode n = selected();
                if (n != null) {
                    onActivate.accept(n);
                }
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);
        scroll.setBorder(null);
    }

    private void popup(MouseEvent e) {
        if (e.isPopupTrigger()) {
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            if (path != null) {
                // a right click inside the selection keeps it, so a menu can act on every selected node
                if (!tree.isPathSelected(path)) {
                    tree.setSelectionPath(path);
                }
                ViewSpec.TreeNode n = node(path);
                if (n != null) {
                    onMenu.accept(n, e);
                }
            }
        }
    }

    private ViewSpec.TreeNode nodeAt(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        return path == null ? null : node(path);
    }

    private static ViewSpec.TreeNode node(TreePath path) {
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n ? n : null;
    }

    /** The parent of the selected node, for keys built from a node and its parent. */
    public ViewSpec.TreeNode parentOf(ViewSpec.TreeNode child) {
        TreePath path = tree.getSelectionPath();
        if (path == null || path.getPathCount() < 2 || node(path) != child) {
            return null;
        }
        return node(path.getParentPath());
    }

    public ViewSpec.TreeNode selected() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : node(path);
    }

    public JComponent component() {
        return scroll;
    }

    public JTree tree() {
        return tree;
    }

    public void onActivate(Consumer<ViewSpec.TreeNode> handler) {
        onActivate = handler;
    }

    public void onMenu(BiConsumer<ViewSpec.TreeNode, MouseEvent> handler) {
        onMenu = handler;
    }

    /** The icon of each node; null keeps the look's own folder and leaf icons. */
    public void icons(Function<ViewSpec.TreeNode, Icon> icons) {
        this.icons = icons;
    }

    /** Shows {@code root}, expanded to {@code levels} below it. */
    public void setRoot(ViewSpec.TreeNode root, int levels) {
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
    public void update(ViewSpec.TreeNode root) {
        Object shown = ((DefaultMutableTreeNode) tree.getModel().getRoot()).getUserObject();
        if (root.equals(shown)) {
            return;
        }
        List<List<String>> expanded = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            if (tree.isExpanded(p)) {
                expanded.add(labels(p));
            }
        }
        List<List<String>> selected = new ArrayList<>();
        TreePath[] selection = tree.getSelectionPaths();
        if (selection != null) {
            for (TreePath p : selection) {
                selected.add(labels(p));
            }
        }
        Rectangle view = tree.getVisibleRect();
        tree.setModel(new DefaultTreeModel(build(root)));
        for (List<String> path : expanded) {
            TreePath p = find(path);
            if (p != null) {
                tree.expandPath(p);
            }
        }
        List<TreePath> reselect = new ArrayList<>();
        for (List<String> path : selected) {
            TreePath p = find(path);
            if (p != null) {
                reselect.add(p);
            }
        }
        if (!reselect.isEmpty()) {
            tree.setSelectionPaths(reselect.toArray(TreePath[]::new));
        }
        tree.scrollRectToVisible(view);
    }

    private static List<String> labels(TreePath path) {
        List<String> out = new ArrayList<>(path.getPathCount());
        for (Object o : path.getPath()) {
            out.add(((DefaultMutableTreeNode) o).getUserObject() instanceof ViewSpec.TreeNode n ? n.label() : "");
        }
        return out;
    }

    /** The path whose labels are {@code labels}, from the root; null when a node is gone. */
    private TreePath find(List<String> labels) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getModel().getRoot();
        if (labels.isEmpty() || !(node.getUserObject() instanceof ViewSpec.TreeNode r) || !r.label().equals(labels.getFirst())) {
            return null;
        }
        TreePath path = new TreePath(node);
        for (String label : labels.subList(1, labels.size())) {
            DefaultMutableTreeNode next = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
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

    private static DefaultMutableTreeNode build(ViewSpec.TreeNode n) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(n);
        for (ViewSpec.TreeNode c : n.children()) {
            node.add(build(c));
        }
        return node;
    }

    /** The tree as indented text. */
    public String copyText() {
        StringBuilder sb = new StringBuilder();
        append(sb, (DefaultMutableTreeNode) tree.getModel().getRoot(), 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, DefaultMutableTreeNode n, int depth) {
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
