package dev.marregui.comando.ui.panels;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Optional;

/**
 * A resource's row in the sidebar, one line: its state icon, which a click connects or disconnects, and its name with
 * its marks; everything else is in the row's menu, and the row's card shows over its name (records 90 and 92). A list
 * paints it as a cell renderer, so its icon is not live: the list finds it under a click with {@link #onIcon}.
 */
abstract class IconRow extends JPanel {

    protected final JLabel title = new JLabel();
    protected final JButton power = new JButton();

    IconRow() {
        super(new BorderLayout(4, 0));
        setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        power.putClientProperty("JButton.buttonType", "toolBarButton");
        power.setFocusable(false);
        power.setMargin(new Insets(0, 2, 0, 2));
        add(power, BorderLayout.WEST);
        add(title, BorderLayout.CENTER);
    }

    /** Whether {@code p}, a point in the cell whose bounds this row was laid out to, is on the state icon. */
    boolean onIcon(Rectangle cell, Point p) {
        layOut(cell);
        return SwingUtilities.getDeepestComponentAt(this, p.x - cell.x, p.y - cell.y) == power && power.isVisible();
    }

    /** Where the state icon is, in the list's coordinates, for a menu to drop from it; empty while it is hidden. */
    Rectangle iconBounds(Rectangle cell) {
        layOut(cell);
        if (!power.isVisible()) {
            return new Rectangle();
        }
        Rectangle r = SwingUtilities.convertRectangle(power.getParent(), power.getBounds(), this);
        r.translate(cell.x, cell.y);
        return r;
    }

    /** The state icon's tooltip when {@code p} is on it; empty over the name, where the card shows. */
    Optional<String> iconTip(Rectangle cell, Point p) {
        return onIcon(cell, p) ? Optional.ofNullable(power.getToolTipText()) : Optional.empty();
    }

    /** A renderer is never in a window, so nothing lays it out for a hit test; this does, as painting would. */
    private void layOut(Rectangle cell) {
        setBounds(0, 0, cell.width, cell.height);
        layOut((Container) this);
    }

    private static void layOut(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container k) {
                layOut(k);
            }
        }
    }
}
