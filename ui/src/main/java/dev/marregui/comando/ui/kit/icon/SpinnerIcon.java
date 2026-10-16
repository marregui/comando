package dev.marregui.comando.ui.kit.icon;

import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;

/**
 * The bar spinner of the original SQL client's {@code InfiniteSpinner}, as an icon: bars around a centre, shaded
 * along a colour wheel that turns clockwise one bar per {@link #advance()}. The icon has no clock of its own; its
 * owner advances it on a timer and repaints where it is drawn, so one icon serves every place that spins, in step.
 * Not thread-safe: advanced and painted on the event thread.
 */
public final class SpinnerIcon implements Icon {

    /** How long one bar's turn lasts, as the original's refresh: eight bars make one revolution in 640 ms. */
    public static final int STEP_MILLIS = 80;

    private static final int BAR_COUNT = 8;

    private final int size;
    private final Area[] bars = new Area[BAR_COUNT];
    private final Color[] colors = new Color[BAR_COUNT];
    private int head = BAR_COUNT - 1;

    public SpinnerIcon(int size) {
        this.size = size;
        double c = size / 2.0;
        // the outer end of a bar stays inside the square: shift + width + height < size / 2
        double barHeight = Math.max(1.5, size / 8.0);
        double barWidth = size * 0.18;
        double shift = size * 0.16;
        AffineTransform toCentre = AffineTransform.getTranslateInstance(c, c);
        AffineTransform alongRadius = AffineTransform.getTranslateInstance(shift, -barHeight / 2.0);
        for (int i = 0; i < BAR_COUNT; i++) {
            Area bar = new Area();
            bar.add(new Area(new Ellipse2D.Double(0, 0, barHeight, barHeight)));
            bar.add(new Area(new Ellipse2D.Double(barWidth, 0, barHeight, barHeight)));
            bar.add(new Area(new Rectangle2D.Double(barHeight / 2.0, 0, barWidth, barHeight)));
            bar.transform(alongRadius);
            bar.transform(toCentre);
            bar.transform(AffineTransform.getRotateInstance(2.0 * Math.PI / BAR_COUNT * i, c, c));
            bars[i] = bar;
            // the original's shades of blue: the head bar deep, the tail lighter with every step
            colors[i] = Theme.spinnerShade(i);
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
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.clipRect(x, y, size, size);
            g2.translate(x, y);
            for (int i = 0; i < BAR_COUNT; i++) {
                g2.setColor(colors[(i + head) % BAR_COUNT]);
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
