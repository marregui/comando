package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.font.TextAttribute;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.plaf.ColorUIResource;

/**
 * The sidebar's look (record 98): the transcript's ground, the transcript's ivory for names, the chosen tint for group
 * headers and the selection: a band of the tint's shade, the name in its highlight and the report's bar down the left
 * edge. The tint marks structure and the selection, never the names.
 */
public final class SidebarLook {

    /** A row's name. */
    public static final Color TEXT = Theme.TRANSCRIPT_TEXT;
    /** The width of the selection's bar, as the report's. */
    public static final int BAR = 3;

    private SidebarLook() {
    }

    /**
     * {@code c} on the panels' ground, as the look and feel's own: another ground chosen, the look sets it again
     * (record 99).
     */
    public static void ground(final JComponent c) {
        c.setOpaque(true);
        c.setBackground(new ColorUIResource(Theme.ground()));
    }

    /** A scroll pane whose viewport, and the corner a scroll bar leaves, are the sidebar's ground. */
    public static void ground(final JScrollPane sp) {
        ground((JComponent) sp);
        sp.getViewport().setOpaque(true);
        sp.getViewport().setBackground(new ColorUIResource(Theme.ground()));
    }

    /** A list of the sidebar: ground, ivory names, the tinted selection, set again when another tint is chosen. */
    public static void list(final JList<?> l) {
        ground(l);
        l.setForeground(TEXT);
        whileShown(l, () -> {
            final Tint t = Theme.tint();
            l.setSelectionBackground(t.tableHeadGround());
            l.setSelectionForeground(t.highlight);
            l.putClientProperty("FlatLaf.style", selection(t));
            l.repaint();
        });
    }

    /** A tree of the sidebar, as a list: the tinted selection, set again when another tint is chosen. */
    public static void tree(final JTree tree) {
        ground(tree);
        whileShown(tree, () -> {
            tree.putClientProperty("FlatLaf.style", selection(Theme.tint()));
            tree.repaint();
        });
    }

    private static String selection(final Tint t) {
        final String band = Theme.hex(t.tableHeadGround());
        final String name = Theme.hex(t.highlight);
        return "selectionBackground: " + band + "; selectionForeground: " + name + "; selectionInactiveBackground: " + band
                + "; selectionInactiveForeground: " + name;
    }

    /** The report's bar, along the tint's ramp, down the left edge of the row at {@code row}. */
    public static void bar(final Graphics g0, final Rectangle row) {
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setPaint(Theme.tint().ramp(row.y, row.y + row.height, false));
            g.fillRect(0, row.y, BAR, row.height);
        } finally {
            g.dispose();
        }
    }

    /**
     * A group's header ({@code Brain}, {@code Without a session}): small capitals, set as spaced capitals at the
     * scale's caption step, in the tint's body, read at each paint.
     */
    public static JLabel header(final String text) {
        final JLabel l = new JLabel() {
            @Override
            public Color getForeground() {
                return Theme.tint().body;
            }
        };
        l.putClientProperty("html.disable", Boolean.TRUE);
        l.setText(text.toUpperCase(Locale.ROOT));
        l.setFont(headerFont(l.getFont()));
        l.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_M, Theme.SPACE_S, Theme.SPACE_XS, Theme.SPACE_S));
        l.setOpaque(false);
        return l;
    }

    /** A group header's font for names set in {@code base}: bold capitals at the caption step, spaced (record 102). */
    public static Font headerFont(final Font base) {
        return Theme.font(base, Theme.TextSize.CAPTION).deriveFont(Font.BOLD).deriveFont(Map.of(TextAttribute.TRACKING, 0.12f));
    }

    /**
     * Runs {@code apply} now and each time another tint is chosen, while {@code c} is in a window, shown or not, as
     * {@code addNotify} and {@code removeNotify} bound it: a component in a tab behind takes a theme chosen with the
     * rest, so the first frame that shows it is in that theme. Listening only while shown, as an ancestor listener
     * does, left it in the theme before until a later event after its tab came forward: one frame of the old ground,
     * a blink (record 127).
     */
    public static void whileShown(final JComponent c, final Runnable apply) {
        apply.run();
        final Runnable[] stop = {c.isDisplayable() ? Theme.onTint(apply) : () -> { }};
        c.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) {
                return;
            }
            stop[0].run();
            stop[0] = () -> { };
            if (c.isDisplayable()) {
                apply.run();
                stop[0] = Theme.onTint(apply);
            }
        });
    }
}
