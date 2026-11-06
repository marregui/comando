package dev.marregui.comando.ui.kit.tree;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [TreeView] shows a [ViewSpec.TreeNode] tree that keeps the user's place across updates.
/// - An update keeps what the user opened and selected, by path, and drops a selection that is gone.
/// - A single click activates nothing; a double click activates a leaf, and opens a folder instead of activating it.
/// - Rows are plain labels, never HTML, with a muted detail and errors in red; the tree names a node by its label.
/// - A row too narrow for both keeps its name and shows its detail in the tooltip.
/// - A row that keeps its detail fits the tree's width, border included, so the bars and the detail settle.
class TreeViewTest {

    private static ViewSpec.TreeNode leaf(final String label) {
        return new ViewSpec.TreeNode(label, "", List.of());
    }

    private static ViewSpec.TreeNode dir(final String label, final ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(label, "", List.of(children));
    }

    /// After an update that inserts nodes above and around them, an expanded folder stays open, a closed one closed and
    /// the selected leaf stays selected at its new row; an update without it selects nothing.
    @Test
    void anUpdateKeepsWhatTheUserOpenedAndSelected() throws Exception {
        final TreeView view = new TreeView();
        final JTree tree = view.tree();
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

    /// A single click activates nothing, a double click on a leaf activates it, a double click on a folder does not,
    /// and the toggle click count is 2.
    @Test
    void onlyADoubleClickOpensALeafOrAFolder() throws Exception {
        final TreeView view = new TreeView();
        final JTree tree = view.tree();
        final List<String> opened = new ArrayList<>();
        view.onActivate(n -> opened.add(n.label()));
        SwingUtilities.invokeAndWait(() -> {
            view.setRoot(dir("root", dir("a/", leaf("a1")), leaf("notes.md")), 1);
            tree.setSize(300, 200);
            tree.doLayout();
            click(tree, 2, 1);
        });
        assertThat(opened).as("one click opens nothing").isEmpty();
        SwingUtilities.invokeAndWait(() -> {
            click(tree, 2, 2);
            click(tree, 1, 2);
        });
        assertThat(opened).as("a folder is not activated: the tree opens it").containsExactly("notes.md");
        assertThat(tree.getToggleClickCount()).as("a folder opens on a double click").isEqualTo(2);
    }

    /// A node with a detail renders as a name label and a muted detail label, a `✗` row is red even when selected, no
    /// label starts with `<html>`, and [JTree#convertValueToText] gives the node's label, not its record's text.
    @Test
    void aRowIsPlainLabelsNeverHtmlAndTheTreeNamesANodeByItsLabel() throws Exception {
        final TreeView view = new TreeView();
        final JTree tree = view.tree();
        SwingUtilities.invokeAndWait(() -> view.setRoot(dir("root", new ViewSpec.TreeNode("notes.md", "3 kB", List.of()), leaf("✗ unreadable"), leaf("plain")), 1));
        SwingUtilities.invokeAndWait(() -> {
            final Component withDetail = tree.getCellRenderer().getTreeCellRendererComponent(tree, node(tree, 1), false, false, true, 1, false);
            final List<JLabel> labels = new ArrayList<>();
            for (final Component c : ((Container) withDetail).getComponents()) {
                labels.add((JLabel) c);
            }
            assertThat(labels).extracting(JLabel::getText).as("the name, then the detail in a label of its own").containsExactly("notes.md", "3 kB");
            assertThat(labels.get(1).getForeground()).isEqualTo(Theme.MUTED);
            final JLabel failed = (JLabel) tree.getCellRenderer().getTreeCellRendererComponent(tree, node(tree, 2), true, false, true, 2, false);
            assertThat(failed.getText()).isEqualTo("✗ unreadable");
            assertThat(failed.getForeground()).as("an error reads red, selected or not").isEqualTo(Theme.DANGER);
            final JLabel plain = (JLabel) tree.getCellRenderer().getTreeCellRendererComponent(tree, node(tree, 3), false, false, true, 3, false);
            assertThat(List.of(labels.get(0).getText(), labels.get(1).getText(), failed.getText(), plain.getText()))
                    .as("no label parses HTML at every paint").noneMatch(text -> text.startsWith("<html>"));
            assertThat(tree.convertValueToText(node(tree, 0), false, true, false, 0, false))
                    .as("a node's name, not its record's text with the whole subtree").isEqualTo("root");
        });
    }

    private static DefaultMutableTreeNode node(final JTree tree, final int row) {
        return (DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent();
    }

    /**
     * A left click of {@code count} on row {@code row}; the press before it needs a display, which tests do without.
     */
    private static void click(final JTree tree, final int row, final int count) {
        final Rectangle r = tree.getRowBounds(row);
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, r.x + r.width / 2,
                r.y + r.height / 2, count, false, MouseEvent.BUTTON1));
    }

    /// In a narrow tree a row keeps its name and gives up its detail, which moves to the tooltip, so no sideways scroll
    /// bar is needed; widened, the detail is back; a name wider than the tree alone still needs the bar.
    @Test
    void aRowGivesUpItsDetailBeforeItsNameAndGetsItBackWhenWidened() throws Exception {
        final TreeView view = onEdt(TreeView::new);
        final JTree tree = view.tree();
        final JScrollPane scroll = (JScrollPane) view.component();
        final String name = "CLAUDE_INTERNALS.md";
        final String path = "~/.claude/BRAIN/SCRATCHPAD/a/rather/long/path";
        SwingUtilities.invokeAndWait(() -> {
            view.setRoot(dir("root", new ViewSpec.TreeNode(name, path, List.of())), 1);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        });
        final int nameWidth = onEdt(() -> {
            final JLabel measure = new JLabel(name);
            measure.setFont(tree.getFont());
            return measure.getPreferredSize().width;
        });
        resize(scroll, nameWidth + 120);
        assertThat(onEdt(() -> labels(cell(tree, 1)))).as("narrow: the name alone").containsExactly(name);
        assertThat(onEdt(() -> tree.getPreferredSize().width)).as("the row fits: no sideways bar")
                .isLessThanOrEqualTo(onEdt(() -> scroll.getViewport().getWidth()));
        assertThat(onEdt(() -> ((JComponent) cell(tree, 1)).getToolTipText())).as("the detail in the tooltip")
                .contains(path);

        resize(scroll, nameWidth + 800);
        assertThat(onEdt(() -> labels(cell(tree, 1)))).as("wide: the detail is back")
                .containsExactly(name, path);

        resize(scroll, nameWidth / 2);
        assertThat(onEdt(() -> tree.getPreferredSize().width)).as("a name wider than the tree scrolls into view")
                .isGreaterThan(onEdt(() -> scroll.getViewport().getWidth()));
    }

    /// At every size around the widest row, within a bar's height of the tree's own, a row that keeps its detail fits
    /// the viewport with the tree's border counted, and the viewport stops changing: a size where the detail needs a
    /// bar and the bar takes the detail's room would flip them for as long as the size stays.
    @Test
    void theBarsAndTheDetailSettleAtEverySizeAroundARow() throws Exception {
        final TreeView view = onEdt(TreeView::new);
        final JTree tree = view.tree();
        final JScrollPane scroll = (JScrollPane) view.component();
        final List<ViewSpec.TreeNode> rows = new ArrayList<>();
        rows.add(new ViewSpec.TreeNode("name.md", "some/detail/path/that/is/long", List.of()));
        for (int i = 0; i < 6; i++) {
            rows.add(leaf("r" + i));
        }
        SwingUtilities.invokeAndWait(() -> {
            view.setRoot(new ViewSpec.TreeNode("root", "", rows), 1);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            // the border the look gives a tree: the test's look has none
            tree.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        });
        resize(scroll, 1_000, 600);
        final Dimension tall = onEdt(tree::getPreferredSize);
        final int aside = onEdt(() -> scroll.getWidth() - scroll.getViewport().getWidth());
        final int above = onEdt(() -> scroll.getHeight() - scroll.getViewport().getHeight());
        final int bar = onEdt(() -> scroll.getHorizontalScrollBar().getPreferredSize().height);
        for (int dw = -6; dw <= 6; dw++) {
            for (int dh = 0; dh <= bar; dh++) {
                final int width = tall.width + aside + dw;
                final int height = tall.height + above + dh;
                for (int pass = 0; pass < 6; pass++) {
                    resize(scroll, width, height);
                }
                final int room = onEdt(() -> scroll.getViewport().getWidth());
                resize(scroll, width, height);
                assertThat(onEdt(() -> scroll.getViewport().getWidth())).as("settled at %d x %d", width, height)
                        .isEqualTo(room);
                if (onEdt(() -> labels(cell(tree, 1))).size() == 2) {
                    assertThat(onEdt(() -> tree.getPreferredSize().width)).as("fits at %d x %d", width, height)
                            .isLessThanOrEqualTo(room);
                }
            }
        }
    }

    private static void resize(final JScrollPane scroll, final int width) throws Exception {
        resize(scroll, width, 300);
    }

    private static void resize(final JScrollPane scroll, final int width, final int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            scroll.setSize(width, height);
            scroll.doLayout();
            scroll.getViewport().doLayout();
        });
        // the viewport's resize reaches its listeners as an event: this queues behind it
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static Component cell(final JTree tree, final int row) {
        final TreePath p = tree.getPathForRow(row);
        return tree.getCellRenderer()
                .getTreeCellRendererComponent(tree, p.getLastPathComponent(), false, false, true, row, false);
    }

    /// The texts of the labels a cell shows.
    private static List<String> labels(final Component c) {
        final List<String> out = new ArrayList<>();
        if (c instanceof JLabel l) {
            out.add(l.getText());
        } else if (c instanceof Container k) {
            for (final Component x : k.getComponents()) {
                out.addAll(labels(x));
            }
        }
        return out;
    }

    private static <T> T onEdt(final Supplier<T> read) throws Exception {
        final AtomicReference<T> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(read.get()));
        return value.get();
    }

    private static TreePath path(final JTree tree, final String... labels) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            final TreePath p = tree.getPathForRow(i);
            final Object[] nodes = p.getPath();
            if (nodes.length == labels.length) {
                boolean match = true;
                for (int k = 0; k < nodes.length && match; k++) {
                    final Object u = ((DefaultMutableTreeNode) nodes[k]).getUserObject();
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
