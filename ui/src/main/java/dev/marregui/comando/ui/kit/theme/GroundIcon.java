package dev.marregui.comando.ui.kit.theme;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A swatch of a ground, the one given or the one chosen at each paint: a rounded square of the ground, edged so it shows
 * on any other, with a bar of a tint down its left side, as a report lies on it: the theme's, else the tint chosen.
 */
public final class GroundIcon implements Icon {

    private static final int SIZE = 16;
    /** Null for whichever ground is chosen when it paints. */
    private final Ground ground;
    /** Null for whichever tint is chosen when it paints. */
    private final Tint tint;

    public GroundIcon(Ground ground) {
        this(ground, null);
    }

    /** The swatch of a curated theme: its ground and its tint (record 103). */
    public GroundIcon(NamedTheme theme) {
        this(theme.ground, theme.tint);
    }

    private GroundIcon(Ground ground, Tint tint) {
        this.ground = ground;
        this.tint = tint;
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D square = new RoundRectangle2D.Float(x + 1.5f, y + 1.5f, 13, 13, 4, 4);
            g.setColor(ground == null ? Theme.ground() : ground.color);
            g.fill(square);
            g.clip(square);
            g.setPaint((tint == null ? Theme.tint() : tint).ramp(y + 2f, y + 14f, false));
            g.fillRect(x + 1, y + 1, 4, 14);
            g.setClip(null);
            g.setColor(Theme.MUTED);
            g.draw(square);
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
