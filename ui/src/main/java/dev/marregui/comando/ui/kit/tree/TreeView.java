package dev.marregui.comando.ui.kit.tree;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.AbstractAction;
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
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link ViewSpec.TreeNode} tree: details muted after the label; a double click or Enter activates a
 * node; a right click asks for its menu. The one tree every widget uses.
 */
public final class TreeView {

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final JScrollPane scroll = new JScrollPane(tree);
    private Consumer<ViewSpec.TreeNode> onActivate = n -> { };
    private BiConsumer<ViewSpec.TreeNode, MouseEvent> onMenu = (n, e) -> { };

    public TreeView() {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean focus) {
                JLabel l = (JLabel) super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, focus);
                if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof ViewSpec.TreeNode node) {
                    String label = node.label().startsWith("✗ ") ? "<span style='color:" + Theme.hex(Theme.DANGER) + "'>" + esc(node.label()) + "</span>"
                            : esc(node.label());
                    l.setText(node.detail().isEmpty() && !node.label().startsWith("✗ ") ? Plain.text(node.label())
                            : "<html>" + label + (node.detail().isEmpty() ? "" : "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>"
                            + esc(node.detail()) + "</span>") + "</html>");
                    l.setToolTipText(node.link().isEmpty() ? null : "Double-click to open");
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
                tree.setSelectionPath(path);
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

    /** Shows {@code root}, expanded to {@code levels} below it. */
    public void setRoot(ViewSpec.TreeNode root, int levels) {
        tree.setModel(new DefaultTreeModel(build(root)));
        for (int i = 0; i < Math.min(tree.getRowCount(), 500); i++) {
            if (((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getLevel() < levels) {
                tree.expandRow(i);
            }
        }
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

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
