package dev.marregui.comando.ui.kit.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A theme drawn small, in its own colours whichever theme is chosen (record 128): its ground, the report's bar in its
 * tint, a heading in the tint, a line of code in its syntax colours and a card with muted text on it, as a panel of
 * the window would show them. The theme chooser's cards draw it.
 */
public final class ThemePreview {

    private ThemePreview() {
    }

    /** {@code theme} drawn in the box at {@code x}, {@code y}, {@code w} by {@code h}, with rounded corners. */
    public static void paint(final Graphics2D g0, final NamedTheme theme, final int x, final int y, final int w, final int h, final Font text, final Font mono) {
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final Ground ground = theme.ground;
            final Tint tint = theme.tint;
            final boolean light = ground.light;
            final ContentPalette p = ContentPalette.of(tint, ground);
            final RoundRectangle2D box = new RoundRectangle2D.Float(x, y, w, h, Theme.RADIUS, Theme.RADIUS);
            g.setColor(ground.color);
            g.fill(box);
            g.clip(box);
            final int pad = Theme.SPACE_S;
            // the report's bar down the left, along the tint's ramp
            g.setPaint(tint.ramp(y + pad, y + h - pad, false));
            g.fillRect(x + pad, y + pad, 3, h - 2 * pad);
            final int left = x + pad + 3 + pad;
            final Font heading = Theme.serif().deriveFont(text.getSize2D() + 2f);
            g.setFont(heading);
            final FontMetrics hm = g.getFontMetrics();
            final int line = y + pad + hm.getAscent();
            g.setPaint(tint.ramp(line - hm.getAscent(), line, true));
            g.drawString("Heading", left, line);
            g.setColor(Tint.over(ground.color, tint.shade, 0.7f));
            g.fillRect(left, line + 3, w - (left - x) - pad, 1);
            // a line of code: a keyword, the ink, a string, a number
            g.setFont(mono);
            final FontMetrics mm = g.getFontMetrics();
            final int code = line + 4 + mm.getHeight();
            int cx = left;
            cx = word(g, mm, "select ", p.keyword, cx, code);
            cx = word(g, mm, "g", p.ink, cx, code);
            cx = word(g, mm, ", ", p.ink, cx, code);
            cx = word(g, mm, "'tint'", p.string, cx, code);
            cx = word(g, mm, ", ", p.ink, cx, code);
            word(g, mm, "42", p.number, cx, code);
            // a card with a line of muted text on it
            final int cardTop = code + mm.getDescent() + pad / 2;
            final int cardH = Math.max(mm.getHeight() + pad, y + h - pad - cardTop);
            final RoundRectangle2D card = new RoundRectangle2D.Float(left, cardTop, w - (left - x) - pad, cardH, 6, 6);
            g.setColor(((OnGround) Theme.CARD).on(ground));
            g.fill(card);
            g.setColor(((OnGround) Theme.CARD_BORDER).on(ground));
            g.setStroke(new BasicStroke(1f));
            g.draw(card);
            g.setFont(text.deriveFont(text.getSize2D() - 1f));
            final FontMetrics tm = g.getFontMetrics();
            g.setColor(Theme.fixed(Theme.MUTED, light));
            g.drawString("a card, muted", left + pad, cardTop + (cardH + tm.getAscent() - tm.getDescent()) / 2);
        } finally {
            g.dispose();
        }
    }

    private static int word(final Graphics2D g, final FontMetrics m, final String s, final Color c, final int x, final int baseline) {
        g.setColor(c);
        g.drawString(s, x, baseline);
        return x + m.stringWidth(s);
    }
}
