package dev.marregui.comando.ui.panels;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A resource's row in the sidebar, one line: its state icon, which a click connects or disconnects, its name with its
 * marks, and at its right edge, on the row the pointer is over, the ⓘ that shows the row's card; a click on the name
 * shows none, so a double click acts without a card first. Everything else is in the row's menu (records 90 and 92). A
 * list paints it as a cell renderer, so its buttons are not live: the list finds them under a click with
 * {@link #onIcon} and {@link #onInfo}.
 */
abstract class IconRow extends JPanel {

    /**
     * A row's name and its marks, shown as plain text: the name in bold, in {@code nameColor} or, when that is null, the
     * row's foreground; each mark after it in its own colour. Not HTML: a list paints its rows through one renderer,
     * and an HTML label parses its text into a new document at every paint (the row's text, foreground and graphics
     * configuration change per cell), which cost 35 % of comando's allocation while a spinner turned (2026-11-02).
     */
    record Title(String name, Color nameColor, List<Mark> marks) {
        Title {
            marks = List.copyOf(marks);
        }
    }

    /** A mark after the name: {@code text} in {@code color}. */
    record Mark(String text, Color color) {
    }

    /** The name: bold, in a font the look's updates keep bold. */
    private final JLabel title = new JLabel() {
        @Override
        public void updateUI() {
            super.updateUI();
            setFont(getFont().deriveFont(Font.BOLD));
        }
    };
    /** The name and its marks on one line; the marks' labels are kept and reused, never parsed. */
    private final JPanel line = new JPanel();
    private final List<JLabel> marks = new ArrayList<>();
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
        line.setOpaque(false);
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.add(title);
        add(line, BorderLayout.CENTER);
        info.putClientProperty("JButton.buttonType", "toolBarButton");
        info.setFocusable(false);
        info.setMargin(new Insets(0, 2, 0, 2));
        info.getAccessibleContext().setAccessibleName("Show the card");
        add(info, BorderLayout.EAST);
        hovered(false);
    }

    /** Shows {@code t} on the row's line, the name in {@code foreground} unless {@code t} gives its own colour. */
    final void title(final Title t, final Color foreground) {
        title.setText(t.name());
        title.setForeground(t.nameColor() == null ? foreground : t.nameColor());
        while (marks.size() < t.marks().size()) {
            final JLabel mark = new JLabel();
            mark.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_XS, 0, 0));
            marks.add(mark);
            line.add(mark);
        }
        for (int i = 0; i < marks.size(); i++) {
            final boolean shown = i < t.marks().size();
            marks.get(i).setVisible(shown);
            if (shown) {
                marks.get(i).setText(t.marks().get(i).text());
                marks.get(i).setForeground(t.marks().get(i).color());
            }
        }
    }

    /** The line shown, as text: the name and the marks, one space apart; for tests. */
    final String titleText() {
        final StringBuilder sb = new StringBuilder(title.getText());
        for (final JLabel m : marks) {
            if (m.isVisible()) {
                sb.append(' ').append(m.getText());
            }
        }
        return sb.toString();
    }

    /** The colour the name is drawn in; for tests. */
    final Color titleColor() {
        return title.getForeground();
    }

    /** Whether the pointer is over this row: only then is its ⓘ drawn and clickable. */
    final void hovered(final boolean on) {
        hovered = on;
        info.setIcon(on ? INFO : NO_INFO);
        info.setToolTipText(on ? INFO_TIP : null);
    }

    /** Whether {@code p}, a point in the cell whose bounds this row was laid out to, is on the ⓘ of a hovered row. */
    boolean onInfo(final Rectangle cell, final Point p) {
        layOut(cell);
        return hovered && SwingUtilities.getDeepestComponentAt(this, p.x - cell.x, p.y - cell.y) == info;
    }

    /** Where the ⓘ is, in the list's coordinates. */
    Rectangle infoBounds(final Rectangle cell) {
        layOut(cell);
        final Rectangle r = SwingUtilities.convertRectangle(info.getParent(), info.getBounds(), this);
        r.translate(cell.x, cell.y);
        return r;
    }

    /** An icon that takes room and draws nothing: the ⓘ's place on a row the pointer is not over. */
    private record EmptyIcon(int width, int height) implements Icon {
        @Override
        public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
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
    boolean onIcon(final Rectangle cell, final Point p) {
        layOut(cell);
        return SwingUtilities.getDeepestComponentAt(this, p.x - cell.x, p.y - cell.y) == power && power.isVisible();
    }

    /** Where the state icon is, in the list's coordinates, for a menu to drop from it; empty while it is hidden. */
    Rectangle iconBounds(final Rectangle cell) {
        layOut(cell);
        if (!power.isVisible()) {
            return new Rectangle();
        }
        final Rectangle r = SwingUtilities.convertRectangle(power.getParent(), power.getBounds(), this);
        r.translate(cell.x, cell.y);
        return r;
    }

    /** The tooltip of the state icon or the ⓘ when {@code p} is on one; empty over the name. */
    Optional<String> iconTip(final Rectangle cell, final Point p) {
        if (onInfo(cell, p)) {
            return Optional.of(INFO_TIP);
        }
        return onIcon(cell, p) ? Optional.ofNullable(power.getToolTipText()) : Optional.empty();
    }

    /** A renderer is never in a window, so nothing lays it out for a hit test; this does, as painting would. */
    private void layOut(final Rectangle cell) {
        setBounds(0, 0, cell.width, cell.height);
        layOut((Container) this);
    }

    private static void layOut(final Container c) {
        c.doLayout();
        for (final Component child : c.getComponents()) {
            if (child instanceof Container k) {
                layOut(k);
            }
        }
    }
}
