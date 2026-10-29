package dev.marregui.comando.ui.widgets.tree;

import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.SourceLinks;
import dev.marregui.comando.ui.kit.tree.TreeView;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/** A labelled tree: test results, a schema, a folder. A node with a file link opens the file. */
public final class TreeWidget implements Widget<ViewSpec.Tree>, Copyable {

    private final TreeView tree = new TreeView();
    private boolean rendered;

    public TreeWidget(WidgetContext context) {
        tree.onActivate(n -> open(context, n));
        tree.onMenu((n, e) -> {
            if (!n.link().isEmpty()) {
                JPopupMenu menu = new JPopupMenu();
                JMenuItem item = new JMenuItem(n.link().startsWith(INSERT) ? "Insert into the editor" : "Open");
                item.addActionListener(a -> open(context, n));
                menu.add(item);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
        Capabilities.mark(tree.component(), this);
    }

    /**
     * Links: {@code comando-file:} opens the file at the line; {@code comando-insert:} puts the text at the editor's
     * caret.
     */
    static final String INSERT = "comando-insert:";

    private static void open(WidgetContext context, ViewSpec.TreeNode n) {
        if (n.link().startsWith(SourceLinks.SCHEME)) {
            context.intents().dispatch(new Intent.OpenFile("", SourceLinks.path(n.link()), SourceLinks.line(n.link())));
        } else if (n.link().startsWith(INSERT)) {
            context.intents().dispatch(new Intent.EditPanel(null, n.link().substring(INSERT.length()), EditMode.CARET));
        }
    }

    @Override
    public JComponent component() {
        return tree.component();
    }

    /** The first view opens two levels; a refresh keeps what the user opened and selected. */
    @Override
    public void render(ViewSpec.Tree view) {
        if (rendered) {
            tree.update(view.root());
        } else {
            rendered = true;
            tree.setRoot(view.root(), 2);
        }
    }

    @Override
    public String copyText() {
        return tree.copyText();
    }
}
