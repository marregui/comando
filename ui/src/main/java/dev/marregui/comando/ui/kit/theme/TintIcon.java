package dev.marregui.comando.ui.kit.theme;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A swatch of a tint, the one given or the one chosen at each paint: a rounded square along its ramp, highlight at the
 * top to the shade at the bottom.
 */
public final class TintIcon implements Icon {

    private static final int SIZE = 16;
    /** Null for whichever tint is chosen when it paints. */
    private final Tint tint;

    public TintIcon(Tint tint) {
        this.tint = tint;
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint((tint == null ? Theme.tint() : tint).ramp(y + 2f, y + 14f, false));
            g.fillRoundRect(x + 2, y + 2, 12, 12, 4, 4);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}
