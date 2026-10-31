package dev.marregui.comando.ui.kit.tabs;

import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * Tabs that move by drag: a tab pressed and dragged a few pixels shows a line in the gap it would land in, and the
 * release reports the move. The pane moves no tab itself; its owner reorders them, as its model says.
 */
public final class MovableTabs extends JTabbedPane {

    /** Where a dragged tab lands: the tab at {@code from} goes to {@code to}, counted after it leaves its place. */
    @FunctionalInterface
    public interface Move {
        void move(int from, int to);
    }

    /** How far the mouse moves with the button down before a press becomes a drag. */
    private static final int DRAG_SLOP = 5;

    private Move move = (from, to) -> {
    };
    private int pressed = -1;
    private Point pressedAt;
    /** The gap a drag would drop into, -1 while no drag runs. */
    private int gap = -1;
    /** A drop just ended: the click the platform may send for the same press and release is not a click on a tab. */
    private boolean dropped;

    public void onMove(final Move move) {
        this.move = move;
    }

    @Override
    protected void processMouseEvent(final MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_CLICKED && dropped) {
            dropped = false;
            e.consume();
            return;
        }
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            dropped = false;
            pressed = SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger() ? indexAtLocation(e.getX(), e.getY()) : -1;
            pressedAt = e.getPoint();
        } else if (e.getID() == MouseEvent.MOUSE_RELEASED) {
            final int from = pressed;
            final int to = gap;
            pressed = -1;
            if (to >= 0) {
                endDrag();
                dropped = true;
                // the gap counts the dragged tab, which leaves its place first
                final int index = to > from ? to - 1 : to;
                if (index != from) {
                    move.move(from, index);
                }
            }
        }
        super.processMouseEvent(e);
    }

    @Override
    protected void processMouseMotionEvent(final MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_DRAGGED && pressed >= 0 && (gap >= 0 || e.getPoint().distance(pressedAt) >= DRAG_SLOP)) {
            final int next = gapAt(e.getX());
            if (next != gap) {
                gap = next;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                repaint();
            }
        }
        super.processMouseMotionEvent(e);
    }

    /** The gap a drag would drop into, for tests; -1 while no drag runs. */
    int gap() {
        return gap;
    }

    private void endDrag() {
        gap = -1;
        setCursor(null);
        repaint();
    }

    /** The gap nearest {@code x}: before the tab whose left half holds it, after the last tab right of them all. */
    private int gapAt(final int x) {
        final int n = getTabCount();
        for (int i = 0; i < n; i++) {
            final Rectangle r = getBoundsAt(i);
            if (r != null && x < r.x + r.width / 2) {
                return i;
            }
        }
        return n;
    }

    /** The drop line over the tabs, which a scrolling tab row paints in a child of the pane. */
    @Override
    public void paint(final Graphics g) {
        super.paint(g);
        final int n = getTabCount();
        if (gap < 0 || n == 0) {
            return;
        }
        final Rectangle r = getBoundsAt(Math.min(gap, n - 1));
        if (r == null) {
            return;
        }
        final int x = gap < n ? r.x : r.x + r.width;
        g.setColor(Theme.tint().body);
        g.fillRect(Math.max(0, x - 1), r.y, 2, r.height);
    }
}
