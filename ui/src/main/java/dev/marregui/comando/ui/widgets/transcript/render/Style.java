package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.kit.theme.Gold;
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
    final Color muted;
    /** Every text drawn outside a bubble or a card, in one colour on the transcript's ground. */
    final Color outside = Theme.TRANSCRIPT_TEXT;
    final Color accent = Theme.ACCENT;
    final Color link = Theme.ACCENT;
    final Color codeBackground = new Color(0x1E1F22);
    final Color inlineCodeBackground;
    final Color selection = new Color(0x214283);
    final Color findMatch = new Color(0x6B5A1E);
    final Color tableRule = new Color(0x4E5157);
    final Color findCurrent = new Color(0xA88A1E);

    /** The gold this style paints with, as {@link Theme#gold()} was when it was made. */
    final Gold gold;
    /**
     * True for Claude's answers on the transcript's ground ({@link #outside()}): gold headings, list marks and table
     * heads, a brighter bold, tinted code; elsewhere each of these is the text's own colour, as before.
     */
    final boolean answer;
    /** Bold text. */
    final Color strong;
    /** A heading's text; an answer's first two levels run along {@link #gold}'s ramp over it. */
    final Color heading;
    /** A list's bullets and numbers, and a quote's bar. */
    final Color mark;
    /** Inline code's text; null keeps the colour of the text around it. */
    final Color codeText;
    final Color tableHead;
    final Color tableHeadBackground;
    /** The line under a first- or second-level heading. */
    final Color headingRule;

    private final Font base;
    private final Font monoBase;
    /** This style with every text colour {@link #outside}; made once, so layouts made with it stay valid. */
    private Style outsideStyle;

    Style(Font base, Font monoBase, int zoom) {
        this(base, monoBase, zoom, color("Label.foreground", new Color(0xDFE1E5)), Theme.MUTED, false, Theme.gold());
    }

    private Style(Font base, Font monoBase, int zoom, Color text, Color muted, boolean answer, Gold gold) {
        this.base = base;
        this.monoBase = monoBase;
        this.text = text;
        this.muted = muted;
        this.zoom = zoom;
        this.gold = gold;
        this.answer = answer;
        if (answer) {
            strong = new Color(0xFAF7F0);
            heading = gold.body;
            mark = gold.body;
            codeText = new Color(0x8FC9C0);
            inlineCodeBackground = new Color(0x26282C);
            tableHead = gold.highlight;
            tableHeadBackground = Gold.over(Theme.TRANSCRIPT, gold.shade, 0.22f);
            headingRule = Gold.over(Theme.TRANSCRIPT, gold.shade, 0.7f);
        } else {
            strong = text;
            heading = text;
            mark = muted;
            codeText = null;
            inlineCodeBackground = new Color(0x393B40);
            tableHead = text;
            tableHeadBackground = codeBackground;
            headingRule = Theme.withAlpha(muted, 80);
        }
        float size = Zoomable.clampSize(base.getSize2D() + zoom);
        float monoSize = Zoomable.clampSize(monoBase.getSize2D() - 1f + zoom);
        body = base.deriveFont(Font.PLAIN, size);
        bold = base.deriveFont(Font.BOLD, size);
        italic = base.deriveFont(Font.ITALIC, size);
        boldItalic = base.deriveFont(Font.BOLD | Font.ITALIC, size);
        mono = monoBase.deriveFont(Font.PLAIN, monoSize);
        monoSmall = monoBase.deriveFont(Font.PLAIN, Math.max(8f, monoSize - 1f));
        small = base.deriveFont(Font.PLAIN, Math.max(8f, size - 1f));
        // the first three levels in the serif, set apart from the text as a title is; the smaller ones bold
        Font serif = Theme.serif();
        headings = new Font[]{
                serif.deriveFont(Font.BOLD, size * 1.45f), serif.deriveFont(Font.BOLD, size * 1.25f), serif.deriveFont(Font.BOLD, size * 1.1f),
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

    /**
     * The same style with its text and muted text in {@link #outside}, and an {@link #answer}'s gold: Claude's answers,
     * quotes included.
     */
    Style outside() {
        if (outsideStyle == null) {
            outsideStyle = answer ? this : new Style(base, monoBase, zoom, outside, outside, true, gold);
        }
        return outsideStyle;
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
