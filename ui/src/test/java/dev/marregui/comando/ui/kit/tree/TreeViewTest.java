package dev.marregui.comando.ui.kit.tree;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreeViewTest {

    private static ViewSpec.TreeNode leaf(String label) {
        return new ViewSpec.TreeNode(label, "", List.of());
    }

    private static ViewSpec.TreeNode dir(String label, ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(label, "", List.of(children));
    }

    @Test
    void anUpdateKeepsWhatTheUserOpenedAndSelected() throws Exception {
        TreeView view = new TreeView();
        JTree tree = view.tree();
        SwingUtilities.invokeAndWait(() -> {
            view.setRoot(dir("root", dir("a/", leaf("a1")), dir("b/", leaf("b1"), leaf("b2"))), 1);
            tree.expandRow(2); // b/
            tree.setSelectionRow(4); // b2
        });
        assertThat(view.selected().label()).isEqualTo("b2");

        SwingUtilities.invokeAndWait(() -> view.update(dir("root", dir("a/", leaf("a1"), leaf("a2")), dir("b/", leaf("b0"), leaf("b1"), leaf("b2")),
                dir("c/", leaf("c1")))));
        assertThat(tree.isExpanded(path(tree, "root", "b/"))).as("b/ stays open").isTrue();
        assertThat(tree.isExpanded(path(tree, "root", "a/"))).as("a/ stays closed").isFalse();
        assertThat(view.selected().label()).as("the same node, at its new row").isEqualTo("b2");

        SwingUtilities.invokeAndWait(() -> view.update(dir("root", dir("a/", leaf("a1"), leaf("a2")))));
        assertThat(view.selected()).as("a node that is gone leaves nothing selected").isNull();
    }

    private static TreePath path(JTree tree, String... labels) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            Object[] nodes = p.getPath();
            if (nodes.length == labels.length) {
                boolean match = true;
                for (int k = 0; k < nodes.length && match; k++) {
                    Object u = ((DefaultMutableTreeNode) nodes[k]).getUserObject();
                    match = u instanceof ViewSpec.TreeNode n && n.label().equals(labels[k]);
                }
                if (match) {
                    return p;
                }
            }
        }
        throw new AssertionError("no row " + String.join("/", labels));
    }
}
