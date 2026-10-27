package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
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
    final Color codeBackground = Theme.CODE_GROUND;
    final Color inlineCodeBackground;
    final Color selection = Theme.SELECTION;
    final Color findMatch = Theme.FIND_MATCH;
    final Color tableRule = Theme.TABLE_RULE;
    final Color findCurrent = Theme.FIND_CURRENT;

    /** The tint this style paints with, as {@link Theme#tint()} was when it was made. */
    final Tint tint;
    /**
     * True for Claude's answers on the transcript's ground ({@link #outside()}): tinted headings, list marks and table
     * heads, a brighter bold, tinted code; elsewhere each of these is the text's own colour, as before.
     */
    final boolean answer;
    /** Bold text. */
    final Color strong;
    /** A heading's text; an answer's first two levels run along {@link #tint}'s ramp over it. */
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
        this(base, monoBase, zoom, color("Label.foreground", Theme.TEXT_FALLBACK), Theme.MUTED, false, Theme.tint());
    }

    private Style(Font base, Font monoBase, int zoom, Color text, Color muted, boolean answer, Tint tint) {
        this.base = base;
        this.monoBase = monoBase;
        this.text = text;
        this.muted = muted;
        this.zoom = zoom;
        this.tint = tint;
        this.answer = answer;
        if (answer) {
            strong = Theme.ANSWER_STRONG;
            heading = tint.body;
            mark = tint.body;
            codeText = Theme.ANSWER_CODE;
            inlineCodeBackground = Theme.ANSWER_CODE_GROUND;
            tableHead = tint.highlight;
            tableHeadBackground = tint.tableHeadGround();
            headingRule = tint.headingRule();
        } else {
            strong = text;
            heading = text;
            mark = muted;
            codeText = null;
            inlineCodeBackground = Theme.INLINE_CODE_GROUND;
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
        float[] scale = Theme.HEADING_SCALE;
        headings = new Font[]{
                serif.deriveFont(Font.BOLD, size * scale[0]), serif.deriveFont(Font.BOLD, size * scale[1]), serif.deriveFont(Font.BOLD, size * scale[2]),
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
     * The same style with its text and muted text in {@link #outside}, and an {@link #answer}'s tint: Claude's answers,
     * quotes included.
     */
    Style outside() {
        if (outsideStyle == null) {
            outsideStyle = answer ? this : new Style(base, monoBase, zoom, outside, outside, true, tint);
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
