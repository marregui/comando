package dev.marregui.comando.ui.kit.theme;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.UIResource;

/**
 * A tooltip's edge, as a turn's report in the transcript: a hairline round it and a bar of the chosen tint down its
 * left side, along the tint's ramp; read at each paint, so a tooltip shown after a choice has the new tint.
 */
public final class TintBarBorder extends AbstractBorder implements UIResource {

    /** The bar's width, as the report's. */
    static final int BAR = 3;

    @Override
    public void paintBorder(final Component c, final Graphics g0, final int x, final int y, final int width, final int height) {
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setColor(Theme.CARD_BORDER);
            g.drawRect(x, y, width - 1, height - 1);
            g.setPaint(Theme.tint().ramp(y, y + height, false));
            g.fillRect(x, y, BAR, height);
        } finally {
            g.dispose();
        }
    }

    @Override
    public Insets getBorderInsets(final Component c, final Insets insets) {
        insets.set(5, BAR + 7, 5, 8);
        return insets;
    }
}
