package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.capability.Zoomable;
import dev.marregui.comando.ui.widgets.transcript.doc.Span;

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

    final Color text;
    final Color muted = Theme.MUTED;
    final Color accent = Theme.ACCENT;
    final Color link = Theme.ACCENT;
    final Color codeBackground = new Color(0x1E1F22);
    final Color inlineCodeBackground = new Color(0x393B40);
    final Color selection = new Color(0x214283);
    final Color findMatch = new Color(0x6B5A1E);
    final Color tableRule = new Color(0x4E5157);
    final Color findCurrent = new Color(0xA88A1E);

    private final Font base;
    private final Font monoBase;
    /** This style with the conclusion's text colour; made once, so layouts made with it stay valid. */
    private Style conclusion;

    Style(Font base, Font monoBase, int zoom) {
        this(base, monoBase, zoom, color("Label.foreground", new Color(0xDFE1E5)));
    }

    private Style(Font base, Font monoBase, int zoom, Color text) {
        this.base = base;
        this.monoBase = monoBase;
        this.text = text;
        this.zoom = zoom;
        float size = Zoomable.clampSize(base.getSize2D() + zoom);
        float monoSize = Zoomable.clampSize(monoBase.getSize2D() - 1f + zoom);
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
        return new Style(baseFont(), Theme.mono(), zoom);
    }

    /** {@code zoom} kept to the steps that move the body text between 8 and 40 pt: a step past either end changes nothing. */
    public static int clampZoom(int zoom) {
        float base = baseFont().getSize2D();
        return Math.round(Zoomable.clampSize(base + zoom) - base);
    }

    private static Font baseFont() {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) {
            base = UIManager.getFont("Label.font");
        }
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return base;
    }

    /** The same style with the text in {@link Theme#GOLD}: a turn's conclusion. */
    Style conclusion() {
        if (conclusion == null) {
            conclusion = text.equals(Theme.GOLD) ? this : new Style(base, monoBase, zoom, Theme.GOLD);
        }
        return conclusion;
    }

    Font font(int spanStyle, boolean code) {
        if (code) {
            return mono;
        }
        boolean b = (spanStyle & Span.BOLD) != 0;
        boolean i = (spanStyle & Span.ITALIC) != 0;
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
