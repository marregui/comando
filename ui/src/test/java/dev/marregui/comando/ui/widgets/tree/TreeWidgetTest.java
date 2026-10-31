package dev.marregui.comando.ui.widgets.tree;

import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;

import static org.assertj.core.api.Assertions.assertThat;

/// [TreeWidget] renders a tree view and keeps the user's place across renders.
/// - a refreshed tree keeps the nodes the user opened and the one selected
class TreeWidgetTest {

    private static ViewSpec.TreeNode leaf(final String label) {
        return new ViewSpec.TreeNode(label, "", List.of());
    }

    private static ViewSpec.TreeNode dir(final String label, final ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(label, "", List.of(children));
    }

    private static JTree tree(final Component c) {
        if (c instanceof JTree t) {
            return t;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final JTree t = tree(child);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /** The path whose labels are {@code labels} from the root. */
    private static TreePath path(final JTree tree, final String... labels) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getModel().getRoot();
        TreePath p = new TreePath(node);
        for (int l = 1; l < labels.length; l++) {
            for (int i = 0; i < node.getChildCount(); i++) {
                final DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
                if (((ViewSpec.TreeNode) c.getUserObject()).label().equals(labels[l])) {
                    node = c;
                    break;
                }
            }
            p = p.pathByAddingChild(node);
        }
        return p;
    }

    /// A render that adds a leaf and a sibling folder keeps the expanded table expanded and the selected column
    /// selected.
    @Test
    void aRefreshedTreeKeepsWhatTheUserOpenedAndSelected() throws Exception {
        final TreeWidget widget = new TreeWidget(new WidgetContext(intent -> { }, new ActionRegistry()));
        final JTree tree = tree(widget.component());
        final AtomicBoolean open = new AtomicBoolean();
        final AtomicReference<String> selected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            widget.render(new ViewSpec.Tree(dir("db", dir("public", dir("orders", leaf("id"), leaf("total"))))));
            tree.expandPath(path(tree, "db", "public", "orders"));
            tree.setSelectionPath(path(tree, "db", "public", "orders", "total"));

            widget.render(new ViewSpec.Tree(dir("db", dir("public", dir("orders", leaf("id"), leaf("total"), leaf("placed")), dir("users", leaf("id"))))));
            open.set(tree.isExpanded(path(tree, "db", "public", "orders")));
            final TreePath s = tree.getSelectionPath();
            selected.set(s == null ? null : ((ViewSpec.TreeNode) ((DefaultMutableTreeNode) s.getLastPathComponent()).getUserObject()).label());
        });
        assertThat(open.get()).as("the table the user opened stays open").isTrue();
        assertThat(selected.get()).as("the selected column stays selected").isEqualTo("total");
    }
}
