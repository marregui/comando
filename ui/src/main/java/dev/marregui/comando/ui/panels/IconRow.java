package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Optional;

/**
 * A resource's row in the sidebar, one line: its state icon, which a click connects or disconnects, its name with its
 * marks, and at its right edge, on the row the pointer is over, the ⓘ that shows the row's card; a click on the name
 * shows none, so a double click acts without a card first. Everything else is in the row's menu (records 90 and 92). A
 * list paints it as a cell renderer, so its buttons are not live: the list finds them under a click with
 * {@link #onIcon} and {@link #onInfo}.
 */
abstract class IconRow extends JPanel {

    protected final JLabel title = new JLabel();
    protected final JButton power = new JButton();
    /** The ⓘ: drawn on the row the pointer is over; elsewhere its place stays empty, so no row changes width. */
    protected final JButton info = new JButton();
    static final String INFO_TIP = "Show the card: its facts and its summary";
    private static final Icon INFO = Icons.get("info");
    private static final Icon NO_INFO = new EmptyIcon(INFO.getIconWidth(), INFO.getIconHeight());
    private boolean hovered;

    IconRow() {
        super(new BorderLayout(4, 0));
        setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        power.putClientProperty("JButton.buttonType", "toolBarButton");
        power.setFocusable(false);
        power.setMargin(new Insets(0, 2, 0, 2));
        add(power, BorderLayout.WEST);
        add(title, BorderLayout.CENTER);
        info.putClientProperty("JButton.buttonType", "toolBarButton");
        info.setFocusable(false);
        info.setMargin(new Insets(0, 2, 0, 2));
        info.getAccessibleContext().setAccessibleName("Show the card");
        add(info, BorderLayout.EAST);
        hovered(false);
    }

    /** Whether the pointer is over this row: only then is its ⓘ drawn and clickable. */
    void hovered(boolean on) {
        hovered = on;
        info.setIcon(on ? INFO : NO_INFO);
        info.setToolTipText(on ? INFO_TIP : null);
    }

    /** Whether {@code p}, a point in the cell whose bounds this row was laid out to, is on the ⓘ of a hovered row. */
    boolean onInfo(Rectangle cell, Point p) {
        layOut(cell);
        return hovered && SwingUtilities.getDeepestComponentAt(this, p.x - cell.x, p.y - cell.y) == info;
    }

    /** Where the ⓘ is, in the list's coordinates. */
    Rectangle infoBounds(Rectangle cell) {
        layOut(cell);
        Rectangle r = SwingUtilities.convertRectangle(info.getParent(), info.getBounds(), this);
        r.translate(cell.x, cell.y);
        return r;
    }

    /** An icon that takes room and draws nothing: the ⓘ's place on a row the pointer is not over. */
    private record EmptyIcon(int width, int height) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
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

    /** The tooltip of the state icon or the ⓘ when {@code p} is on one; empty over the name. */
    Optional<String> iconTip(Rectangle cell, Point p) {
        if (onInfo(cell, p)) {
            return Optional.of(INFO_TIP);
        }
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
