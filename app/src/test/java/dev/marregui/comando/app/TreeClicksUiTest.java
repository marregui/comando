package dev.marregui.comando.app;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.tree.TreeView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tree that opens a folder on one click, as the Files tab's: the look opens and closes folders on a press, which needs
 * a display, so this runs in {@code ./gradlew :app:uiTest} (on Linux under {@code xvfb-run}).
 */
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class TreeClicksUiTest {

    private static ViewSpec.TreeNode node(String label, ViewSpec.TreeNode... children) {
        return new ViewSpec.TreeNode(label, "", List.of(children));
    }

    @Test
    void theFirstClickOpensOrClosesAFolderAndADoubleClickCanPutItBack() throws Exception {
        TreeView view = new TreeView();
        JTree tree = view.tree();
        view.toggleOnClick(true);
        SwingUtilities.invokeAndWait(() -> {
            view.setRoot(node("root", node("a/", node("a1")), node("notes.md")), 0);
            tree.expandRow(0);
            tree.setSize(300, 200);
            tree.doLayout();
        });
        TreePath a = tree.getPathForRow(1);
        SwingUtilities.invokeAndWait(() -> press(tree, 1, 1));
        assertThat(tree.isExpanded(a)).as("one click opens a folder").isTrue();
        SwingUtilities.invokeAndWait(() -> press(tree, 1, 2));
        assertThat(tree.isExpanded(a)).as("the second click of a double click leaves it").isTrue();
        SwingUtilities.invokeAndWait(() -> view.undoClickToggle(a));
        assertThat(tree.isExpanded(a)).as("put back as it was before the double click").isFalse();
        SwingUtilities.invokeAndWait(() -> view.undoClickToggle(a));
        assertThat(tree.isExpanded(a)).as("put back once only").isFalse();
        SwingUtilities.invokeAndWait(() -> {
            press(tree, 1, 1);
            press(tree, 1, 1);
        });
        assertThat(tree.isExpanded(a)).as("two single clicks open and close it").isFalse();

        TreeView plain = new TreeView();
        assertThat(plain.tree().getToggleClickCount()).as("other trees keep the look's double click").isEqualTo(2);
    }

    /** A left press and release of {@code count} on row {@code row}. */
    private static void press(JTree tree, int row, int count) {
        Rectangle r = tree.getRowBounds(row);
        int x = r.x + r.width / 2;
        int y = r.y + r.height / 2;
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, x, y, count, false, MouseEvent.BUTTON1));
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x, y, count, false, MouseEvent.BUTTON1));
    }
}
