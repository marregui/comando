package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.tree.TreeView;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/**
 * The sidebar's Scratchpad tab: the scratchpad's documents and day folders as a tree, the newest day open at the
 * first load. A refresh keeps what the user opened and selected; double-click or Enter opens a file in the side pane.
 */
final class ScratchpadTab {

    private final TreeView tree = new TreeView();
    private boolean loaded;

    ScratchpadTab(IntentSink intents) {
        tree.onActivate(n -> open(intents, n));
        tree.onMenu((n, e) -> {
            if (n.link().startsWith(SourceLinks.SCHEME)) {
                JPopupMenu menu = new JPopupMenu();
                JMenuItem item = new JMenuItem("Open");
                item.addActionListener(a -> open(intents, n));
                menu.add(item);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
        tree.tree().getAccessibleContext().setAccessibleName("Scratchpad");
    }

    JComponent component() {
        return tree.component();
    }

    TreeView tree() {
        return tree;
    }

    /** Shows the scratchpad as read: the first time with the newest day open, then keeping the user's tree. */
    void show(ViewSpec.TreeNode root) {
        if (loaded) {
            tree.update(root);
            return;
        }
        loaded = true;
        tree.setRoot(root, 1);
        for (int i = 1; i < tree.tree().getRowCount(); i++) {
            TreePath p = tree.tree().getPathForRow(i);
            if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof ViewSpec.TreeNode n
                    && n.label().matches("\\d{4}-\\d{2}-\\d{2}/")) {
                tree.tree().expandRow(i);
                break;
            }
        }
    }

    private static void open(IntentSink intents, ViewSpec.TreeNode n) {
        if (n.link().startsWith(SourceLinks.SCHEME)) {
            intents.dispatch(new Intent.OpenFile("", SourceLinks.path(n.link()), SourceLinks.line(n.link())));
        }
    }
}
