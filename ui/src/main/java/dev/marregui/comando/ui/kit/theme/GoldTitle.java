package dev.marregui.comando.ui.kit.theme;

import javax.swing.JLabel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;

/**
 * A title in the headings' serif, its letters along the chosen gold's ramp, as a transcript answer's first headings:
 * the heading of a card that should read as the reply does. Plain text only; a glyph the serif lacks is not drawn, so
 * a title keeps to words.
 */
public final class GoldTitle extends JLabel {

    public GoldTitle(String text, float size) {
        super(text);
        putClientProperty("html.disable", Boolean.TRUE);
        setFont(Theme.serif().deriveFont(size));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        String text = getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            Insets in = getInsets();
            TextLayout layout = new TextLayout(text, getFont(), g.getFontRenderContext());
            float baseline = in.top + layout.getAscent();
            g.setPaint(Theme.gold().ramp(baseline - layout.getAscent() * 0.8f, baseline, true));
            g.fill(layout.getOutline(AffineTransform.getTranslateInstance(in.left, baseline)));
        } finally {
            g.dispose();
        }
    }
}
