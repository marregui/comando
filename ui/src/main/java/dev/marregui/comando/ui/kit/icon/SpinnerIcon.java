package dev.marregui.comando.ui.kit.icon;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import javax.swing.Icon;

import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * A bar wheel as an icon (record 42): bars around a centre, the head in the tint chosen and the bars behind it fading
 * into the ground, turning clockwise one bar per {@link #advance()}; the shades are read at each paint, so another
 * theme shows at the next turn. The icon has no clock of its own; its owner advances it on a timer and repaints where
 * it is drawn, so one icon serves every place that spins, in step. Not thread-safe: advanced and painted on the EDT.
 */
public final class SpinnerIcon implements Icon {

    /** How long one bar's turn lasts: eight bars make one revolution in 640 ms. */
    public static final int STEP_MILLIS = 80;

    private static final int BAR_COUNT = 8;

    private final int size;
    private final Area[] bars = new Area[BAR_COUNT];
    private int head = BAR_COUNT - 1;

    public SpinnerIcon(final int size) {
        this.size = size;
        final double c = size / 2.0;
        // the outer end of a bar stays inside the square: shift + width + height < size / 2
        final double barHeight = Math.max(1.5, size / 8.0);
        final double barWidth = size * 0.18;
        final double shift = size * 0.16;
        final AffineTransform toCentre = AffineTransform.getTranslateInstance(c, c);
        final AffineTransform alongRadius = AffineTransform.getTranslateInstance(shift, -barHeight / 2.0);
        for (int i = 0; i < BAR_COUNT; i++) {
            final Area bar = new Area();
            bar.add(new Area(new Ellipse2D.Double(0, 0, barHeight, barHeight)));
            bar.add(new Area(new Ellipse2D.Double(barWidth, 0, barHeight, barHeight)));
            bar.add(new Area(new Rectangle2D.Double(barHeight / 2.0, 0, barWidth, barHeight)));
            bar.transform(alongRadius);
            bar.transform(toCentre);
            bar.transform(AffineTransform.getRotateInstance(2.0 * Math.PI / BAR_COUNT * i, c, c));
            bars[i] = bar;
        }
    }

    /** Turns the wheel one bar clockwise. */
    public void advance() {
        head = head == 0 ? BAR_COUNT - 1 : head - 1;
    }

    /** The bar the wheel's head is on, for tests. */
    int head() {
        return head;
    }

    @Override
    public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.clipRect(x, y, size, size);
            g2.translate(x, y);
            for (int i = 0; i < BAR_COUNT; i++) {
                g2.setColor(Theme.spinnerShade((i + head) % BAR_COUNT));
                g2.fill(bars[i]);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
