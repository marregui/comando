package dev.marregui.comando.ui.kit.tabs;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MovableTabsTest {

    @Test
    void aTabDraggedIntoAGapReportsWhereItLandsCountedAfterItLeavesItsPlace() throws Exception {
        List<int[]> moves = new CopyOnWriteArrayList<>();
        MovableTabs[] t = new MovableTabs[1];
        SwingUtilities.invokeAndWait(() -> t[0] = tabs(moves));

        drag(t[0], 0, gapX(t[0], 3));
        assertThat(moves).as("the first tab dropped after the last is the last").singleElement()
                .satisfies(m -> assertThat(m).containsExactly(0, 2));
        assertThat(t[0].gap()).as("the drop line goes with the release").isEqualTo(-1);

        moves.clear();
        drag(t[0], 2, gapX(t[0], 0));
        assertThat(moves).singleElement().satisfies(m -> assertThat(m).containsExactly(2, 0));

        moves.clear();
        drag(t[0], 1, gapX(t[0], 2));
        assertThat(moves).as("a tab dropped next to itself stays").isEmpty();
    }

    @Test
    void aPressThatMovesLessThanTheSlopIsNoDrag() throws Exception {
        List<int[]> moves = new CopyOnWriteArrayList<>();
        MovableTabs[] t = new MovableTabs[1];
        SwingUtilities.invokeAndWait(() -> t[0] = tabs(moves));
        Rectangle r = bounds(t[0], 0);
        int y = r.y + r.height / 2;
        SwingUtilities.invokeAndWait(() -> {
            press(t[0], r.x + 4, y);
            t[0].dispatchEvent(mouse(t[0], MouseEvent.MOUSE_DRAGGED, r.x + 7, y));
            assertThat(t[0].gap()).isEqualTo(-1);
            t[0].dispatchEvent(mouse(t[0], MouseEvent.MOUSE_RELEASED, r.x + 7, y));
        });
        assertThat(moves).isEmpty();
    }

    private static MovableTabs tabs(List<int[]> moves) {
        MovableTabs tabs = new MovableTabs();
        for (String title : List.of("alpha", "beta", "gamma")) {
            tabs.addTab(title, new JPanel());
        }
        tabs.onMove((from, to) -> moves.add(new int[]{from, to}));
        tabs.setSize(600, 200);
        tabs.doLayout();
        return tabs;
    }

    /** An x inside gap {@code gap}: the left quarter of the tab after it, or right of the last tab. */
    private static int gapX(MovableTabs tabs, int gap) throws Exception {
        int n = tabs.getTabCount();
        Rectangle r = bounds(tabs, Math.min(gap, n - 1));
        return gap < n ? r.x + r.width / 4 : r.x + r.width + 4;
    }

    private static void drag(MovableTabs tabs, int from, int toX) throws Exception {
        Rectangle r = bounds(tabs, from);
        int y = r.y + r.height / 2;
        SwingUtilities.invokeAndWait(() -> {
            press(tabs, r.x + r.width / 2, y);
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, r.x + r.width / 2 + 10, y));
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, toX, y));
            assertThat(tabs.gap()).as("a drag shows its gap").isGreaterThanOrEqualTo(0);
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_RELEASED, toX, y));
        });
    }

    private static void press(MovableTabs tabs, int x, int y) {
        try {
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_PRESSED, x, y));
        } catch (HeadlessException e) {
            // the look's own press handler may ask the toolkit for more than headless has; the drag saw the press first
        }
    }

    private static Rectangle bounds(MovableTabs tabs, int i) throws Exception {
        Rectangle[] r = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> r[0] = tabs.getBoundsAt(i));
        return r[0];
    }

    private static MouseEvent mouse(MovableTabs c, int id, int x, int y) {
        boolean button = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(c, id, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, button ? 1 : 0, false,
                button ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
    }
}
