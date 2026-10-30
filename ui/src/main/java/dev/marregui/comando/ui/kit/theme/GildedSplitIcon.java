package dev.marregui.comando.ui.kit.theme;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.Optional;

/**
 * The theme button's icon (record 128): a rounded square in a rim of polished metal, the ramp of the tint chosen,
 * split corner to corner between the ground chosen and its twin's, the dark one above. Read at each paint, so it
 * follows the theme. A theme without a twin is split with Graphite's or Paper's ground.
 */
public final class GildedSplitIcon implements Icon {

    private static final int SIZE = 16;
    /** The highlight's ink on the rim, as light on metal. */
    private static final Color GLINT = new Color(255, 255, 255, 96);

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Tint tint = Theme.tint();
            Ground now = Theme.groundChosen();
            Ground twin = NamedTheme.chosen().flatMap(GildedSplitIcon::twinOf).map(t -> t.ground)
                    .orElse(now.light ? Ground.GRAPHITE : Ground.PAPER);
            Color dark = (now.light ? twin : now).color;
            Color light = (now.light ? now : twin).color;
            float o = 1f;
            float s = SIZE - 2f;
            RoundRectangle2D rim = new RoundRectangle2D.Float(x + o, y + o, s, s, 6, 6);
            g.setPaint(tint.ramp(y + o, y + o + s, false));
            g.fill(rim);
            float in = 2f;
            RoundRectangle2D face = new RoundRectangle2D.Float(x + o + in, y + o + in, s - 2 * in, s - 2 * in, 3.5f, 3.5f);
            g.clip(face);
            g.setColor(dark);
            g.fill(face);
            int fx = Math.round(x + o + in);
            int fy = Math.round(y + o + in);
            int fw = Math.round(s - 2 * in);
            g.setColor(light);
            g.fillPolygon(new Polygon(new int[]{fx + fw, fx + fw, fx}, new int[]{fy, fy + fw, fy + fw}, 3));
            g.setClip(null);
            // a glint on the rim's upper left, as on polished metal
            g.setColor(GLINT);
            g.fill(new Ellipse2D.Float(x + o + 1.5f, y + o + 0.4f, 5.5f, 1.8f));
        } finally {
            g.dispose();
        }
    }

    /** The theme of the other mode {@code t} pairs with: its twin, or the dark theme whose twin it is. */
    private static Optional<NamedTheme> twinOf(NamedTheme t) {
        if (t.twin.isPresent()) {
            return NamedTheme.withId(t.twin.get());
        }
        return Arrays.stream(NamedTheme.values()).filter(o -> o.twin.map(t.id::equals).orElse(false)).findFirst();
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
