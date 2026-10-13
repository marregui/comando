package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;

/**
 * Fonts, colours and spacing of the transcript at one zoom level. Immutable; a zoom change makes a new
 * one, and every layout made with the previous one is stale.
 */
public final class Style {

    /** Layout and painting use the same context: antialiased, fractional metrics. */
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    final int zoom;
    final Font body;
    final Font bold;
    final Font italic;
    final Font boldItalic;
    final Font mono;
    final Font monoSmall;
    final Font small;
    final Font[] headings;
    final float lineGap;
    final int paraGap;
    final int indent;
    final int pad;

    final Color text = color("Label.foreground", new Color(0xDFE1E5));
    final Color muted = Theme.MUTED;
    final Color accent = Theme.ACCENT;
    final Color link = Theme.ACCENT;
    final Color codeBackground = new Color(0x1E1F22);
    final Color inlineCodeBackground = new Color(0x393B40);
    final Color selection = new Color(0x214283);
    final Color findMatch = new Color(0x6B5A1E);
    final Color findCurrent = new Color(0xA88A1E);

    Style(Font base, Font monoBase, int zoom) {
        this.zoom = zoom;
        float size = Math.max(9f, base.getSize2D() + zoom);
        float monoSize = Math.max(8f, monoBase.getSize2D() - 1f + zoom);
        body = base.deriveFont(Font.PLAIN, size);
        bold = base.deriveFont(Font.BOLD, size);
        italic = base.deriveFont(Font.ITALIC, size);
        boldItalic = base.deriveFont(Font.BOLD | Font.ITALIC, size);
        mono = monoBase.deriveFont(Font.PLAIN, monoSize);
        monoSmall = monoBase.deriveFont(Font.PLAIN, Math.max(8f, monoSize - 1f));
        small = base.deriveFont(Font.PLAIN, Math.max(8f, size - 1f));
        headings = new Font[]{
                base.deriveFont(Font.BOLD, size * 1.45f), base.deriveFont(Font.BOLD, size * 1.25f), base.deriveFont(Font.BOLD, size * 1.1f),
                bold, bold, bold};
        LineMetrics m = body.getLineMetrics("Xg", FRC);
        lineGap = Math.max(2f, m.getHeight() * 0.22f);
        paraGap = Math.round(m.getHeight() * 0.55f);
        indent = Math.round(m.getHeight() * 1.3f);
        pad = Theme.PAD;
    }

    /** The style for the look and feel's default font and the theme's monospaced font. */
    public static Style of(int zoom) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) {
            base = UIManager.getFont("Label.font");
        }
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return new Style(base, Theme.mono(), zoom);
    }

    Font font(int spanStyle, boolean code) {
        if (code) {
            return mono;
        }
        boolean b = (spanStyle & dev.marregui.comando.ui.widgets.transcript.doc.Span.BOLD) != 0;
        boolean i = (spanStyle & dev.marregui.comando.ui.widgets.transcript.doc.Span.ITALIC) != 0;
        return b && i ? boldItalic : b ? bold : i ? italic : body;
    }

    float height(Font f) {
        return f.getLineMetrics("Xg", FRC).getHeight() + lineGap;
    }

    private static Color color(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c == null ? fallback : c;
    }
}
