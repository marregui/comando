package dev.marregui.comando.ui.kit.theme;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import javax.swing.JLabel;

/**
 * A title in the headings' serif, its letters along the chosen tint's ramp, as a transcript answer's first headings:
 * the heading of a card that should read as the reply does. Plain text only; a glyph the serif lacks is not drawn, so
 * a title keeps to words.
 */
public final class TintTitle extends JLabel {

    public TintTitle(final String text, final float size) {
        super(text);
        putClientProperty("html.disable", Boolean.TRUE);
        setFont(Theme.serif().deriveFont(size));
    }

    @Override
    protected void paintComponent(final Graphics g0) {
        final String text = getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            final Insets in = getInsets();
            final TextLayout layout = new TextLayout(text, getFont(), g.getFontRenderContext());
            final float baseline = in.top + layout.getAscent();
            g.setPaint(Theme.tint().ramp(baseline - layout.getAscent() * 0.8f, baseline, true));
            g.fill(layout.getOutline(AffineTransform.getTranslateInstance(in.left, baseline)));
        } finally {
            g.dispose();
        }
    }
}
