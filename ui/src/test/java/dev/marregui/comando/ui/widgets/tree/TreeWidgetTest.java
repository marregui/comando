package dev.marregui.comando.ui.widgets.tree;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TreeWidgetTest {

    private static ViewSpec.TreeNode leaf(String label) {
        return new ViewSpec.TreeNode(label, "", List.of());
    }

    private static ViewSpec.TreeNode dir(String label, ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(label, "", List.of(children));
    }

    private static JTree tree(Component c) {
        if (c instanceof JTree t) {
            return t;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                JTree t = tree(child);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /** The path whose labels are {@code labels} from the root. */
    private static TreePath path(JTree tree, String... labels) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getModel().getRoot();
        TreePath p = new TreePath(node);
        for (int l = 1; l < labels.length; l++) {
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
                if (((ViewSpec.TreeNode) c.getUserObject()).label().equals(labels[l])) {
                    node = c;
                    break;
                }
            }
            p = p.pathByAddingChild(node);
        }
        return p;
    }

    @Test
    void aRefreshedTreeKeepsWhatTheUserOpenedAndSelected() throws Exception {
        TreeWidget widget = new TreeWidget(new WidgetContext(intent -> { }, new ActionRegistry()));
        JTree tree = tree(widget.component());
        AtomicBoolean open = new AtomicBoolean();
        AtomicReference<String> selected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            widget.render(new ViewSpec.Tree(dir("db", dir("public", dir("orders", leaf("id"), leaf("total"))))));
            tree.expandPath(path(tree, "db", "public", "orders"));
            tree.setSelectionPath(path(tree, "db", "public", "orders", "total"));

            widget.render(new ViewSpec.Tree(dir("db", dir("public", dir("orders", leaf("id"), leaf("total"), leaf("placed")), dir("users", leaf("id"))))));
            open.set(tree.isExpanded(path(tree, "db", "public", "orders")));
            TreePath s = tree.getSelectionPath();
            selected.set(s == null ? null : ((ViewSpec.TreeNode) ((DefaultMutableTreeNode) s.getLastPathComponent()).getUserObject()).label());
        });
        assertThat(open.get()).as("the table the user opened stays open").isTrue();
        assertThat(selected.get()).as("the selected column stays selected").isEqualTo("total");
    }
}
