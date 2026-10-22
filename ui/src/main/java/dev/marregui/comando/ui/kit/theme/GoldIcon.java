package dev.marregui.comando.ui.kit.theme;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/** A small ingot cast in a gold: the one given, or the one chosen at each paint. */
public final class GoldIcon implements Icon {

    private static final int SIZE = 16;
    /** Null for whichever gold is chosen when it paints. */
    private final Gold gold;

    public GoldIcon(Gold gold) {
        this.gold = gold;
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Gold g = gold == null ? Theme.gold() : gold;
        Graphics2D g2 = (Graphics2D) g0.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // the top face, narrower than the base, then the front: an ingot seen a little from above
            Path2D.Float top = new Path2D.Float();
            top.moveTo(x + 4.5f, y + 4f);
            top.lineTo(x + 11.5f, y + 4f);
            top.lineTo(x + 13f, y + 7f);
            top.lineTo(x + 3f, y + 7f);
            top.closePath();
            Path2D.Float front = new Path2D.Float();
            front.moveTo(x + 3f, y + 7f);
            front.lineTo(x + 13f, y + 7f);
            front.lineTo(x + 15f, y + 12.5f);
            front.lineTo(x + 1f, y + 12.5f);
            front.closePath();
            g2.setPaint(new LinearGradientPaint(0, y + 4f, 0, y + 7f, new float[]{0f, 1f}, new Color[]{g.highlight, g.at(0.3f)}));
            g2.fill(top);
            g2.setPaint(new LinearGradientPaint(0, y + 7f, 0, y + 12.5f, new float[]{0f, 1f}, new Color[]{g.body, g.shade}));
            g2.fill(front);
        } finally {
            g2.dispose();
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
