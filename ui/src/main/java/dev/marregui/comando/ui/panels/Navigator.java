package dev.marregui.comando.ui.panels;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.tree.TreeView;

/**
 * A sidebar tab's tree of rows, as the Logbook and Instances tabs show theirs: the rows drawn by a {@link TreeView},
 * a row's card at a click that then follows the pointer ({@link CardFollower}) in place of a tooltip, a menu on a
 * right click, and a double click or Enter to open a row. The tab gives what its rows stand for and what each offers;
 * the tree and its pointer are the navigator's.
 *
 * @param <R> what a row of the tree stands for
 */
final class Navigator<R> {

    private final CardFollower<R> follower;
    /** The row at a path of the tree, or null for a path that is no such row or no path. */
    private final Function<TreePath, R> rowOf;
    /** Whether two rows are one row, the tree rebuilt in between; the second may be null. */
    private final BiPredicate<R, R> same;
    private final TreeView view = new TreeView();
    /** What a row's menu offers; null, or an empty menu, for a row with none. */
    private Function<R, JPopupMenu> menu = _ -> null;
    /** What a double click or Enter on a row does. */
    private Consumer<R> open = _ -> { };

    /**
     * @param name  the tree's accessible name
     * @param cards the sidebar's cards
     * @param rowOf the row at a path of the tree, or null
     * @param key   a row's card's key on {@code cards}, one per row
     * @param same  whether two rows are one row, the tree rebuilt in between; the second may be null
     * @param card  what a row's card says
     */
    Navigator(
            final String name,
            final Supplier<SessionsSidebar.Cards> cards,
            final Function<TreePath, R> rowOf,
            final Function<R, String> key,
            final BiPredicate<R, R> same,
            final Function<R, CardFollower.Card> card
    ) {
        this.rowOf = rowOf;
        this.same = same;
        final JTree tree = view.tree();
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getAccessibleContext().setAccessibleName(name);
        view.spaceAbove();
        view.onActivate(n -> {
            final R r = rowOf.apply(pathOf(n));
            if (r != null) {
                open.accept(r);
            }
        });
        view.onMenu((_, e) -> {
            final R r = rowOf.apply(tree.getPathForLocation(e.getX(), e.getY()));
            final JPopupMenu m = r == null ? null : menu.apply(r);
            if (m != null && m.getComponentCount() > 0) {
                m.show(e.getComponent(), e.getX(), e.getY());
            }
        });
        // a row tells what it is on its card, not in a tooltip: the tree's own, and a detail cut short, are not shown
        ToolTipManager.sharedInstance().unregisterComponent(tree);
        follower = new CardFollower<>(tree, cards, rowOf, key, same, card);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                // a click shows the row's card, which then follows the pointer; a double click opens the row
                if (e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    final R r = rowOf.apply(tree.getPathForLocation(e.getX(), e.getY()));
                    if (r != null) {
                        follower.clicked(r);
                    }
                }
            }
        });
        if (view.component() instanceof JScrollPane sp) {
            // as the Files tab: a name wider than the tab scrolls into view rather than being cut at the edge
            sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        }
    }

    TreeView view() {
        return view;
    }

    JComponent component() {
        return view.component();
    }

    void menus(final Function<R, JPopupMenu> menu) {
        this.menu = menu;
    }

    void onOpen(final Consumer<R> open) {
        this.open = open;
    }

    R selected() {
        return rowOf.apply(view.tree().getSelectionPath());
    }

    /** The path of the row showing {@code n}, the selected one first, or null when no row shows it. */
    private TreePath pathOf(final ViewSpec.TreeNode n) {
        final JTree tree = view.tree();
        final TreePath selected = tree.getSelectionPath();
        if (shows(selected, n)) {
            return selected;
        }
        for (int i = 0; i < tree.getRowCount(); i++) {
            final TreePath p = tree.getPathForRow(i);
            if (shows(p, n)) {
                return p;
            }
        }
        return null;
    }

    private static boolean shows(final TreePath p, final ViewSpec.TreeNode n) {
        return p != null && p.getLastPathComponent() instanceof DefaultMutableTreeNode d && d.getUserObject() == n;
    }

    /** The row shown now as {@code r}, the last of them, or null when it is gone. */
    R shown(final R r) {
        final JTree tree = view.tree();
        R now = null;
        for (int i = 0; i < tree.getRowCount(); i++) {
            final R x = rowOf.apply(tree.getPathForRow(i));
            if (x != null && same.test(r, x)) {
                now = x;
            }
        }
        return now;
    }

    /** The card shown, with what it says now for the row {@code now} gives; see {@link CardFollower#refresh}. */
    void refresh(final UnaryOperator<R> now) {
        follower.refresh(now);
    }
}
