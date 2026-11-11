package dev.marregui.comando.ui.kit.theme;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;

/**
 * A region's frame under the console style (record 151): a hairline and four corner brackets, read from the theme at
 * each paint. Under the plain style it paints nothing and takes no room. It is no {@code UIResource}, so a look and
 * feel update keeps it; the update lays the region out again, on the insets of the style then chosen.
 */
public final class BracketBorder extends AbstractBorder {

    /** The room the frame takes on each side under the console style. */
    public static final int INSET = 3;
    /** A bracket's arm, along each edge from its corner: the console look's mark, set by eye, not measured. */
    static final int ARM = 10;

    @Override
    public void paintBorder(final Component c,
                            final Graphics g0,
                            final int x,
                            final int y,
                            final int width,
                            final int height) {
        if (Theme.style() != Style.CONSOLE) {
            return;
        }
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setColor(Theme.CARD_BORDER);
            g.drawRect(x, y, width - 1, height - 1);
            // a frame narrower than two arms gets brackets that meet halfway rather than cross
            final int arm = Math.min(ARM, Math.min(width, height) / 2);
            g.setColor(Theme.tint().shade);
            brackets(g, x, y, width, height, arm);
        } finally {
            g.dispose();
        }
    }

    /**
     * The four corner brackets of the box at {@code x}, {@code y}, {@code width} by {@code height}, each arm
     * {@code arm} long and one pixel wide, in the colour {@code g} has. Filled, not stroked: a one-pixel rectangle stays
     * on its pixels with antialiasing on.
     */
    static void brackets(final Graphics2D g,
                         final int x,
                         final int y,
                         final int width,
                         final int height,
                         final int arm) {
        final int right = x + width - 1;
        final int bottom = y + height - 1;
        g.fillRect(x, y, arm, 1);
        g.fillRect(x, y, 1, arm);
        g.fillRect(right - arm + 1, y, arm, 1);
        g.fillRect(right, y, 1, arm);
        g.fillRect(x, bottom, arm, 1);
        g.fillRect(x, bottom - arm + 1, 1, arm);
        g.fillRect(right - arm + 1, bottom, arm, 1);
        g.fillRect(right, bottom - arm + 1, 1, arm);
    }

    @Override
    public Insets getBorderInsets(final Component c, final Insets insets) {
        final int room = Theme.style() == Style.CONSOLE ? INSET : 0;
        insets.set(room, room, room, room);
        return insets;
    }
}
