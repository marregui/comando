package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wrapped rich text: lines broken at the width with {@link LineBreakMeasurer} and at every newline.
 * The layout is kept until the width or the style changes, so repainting and scrolling cost no layout.
 * A source line that wraps is shaped once and its measurer kept across widths: shaping is most of the cost of a
 * wrap, as the JDK measures each glyph's outline for it. When one font sets the whole source line, every line it
 * wraps to is as tall as the first one laid out, at any width, so its lines make their layouts when first painted or
 * hit: a layout of a long text makes only the layouts it shows. Coordinates are relative to the box's top left. Laid
 * out, painted and hit on one thread at a time, as a line laid out late moves its source line's measurer.
 */
final class TextBox {

    /**
     * One laid-out line: {@link #layout()} is null for an empty line; a wrapped line of a source line in one font makes
     * its layout on the first call.
     */
    static final class Line {
        private TextLayout layout;
        private final float top;
        private final float baseline;
        private final float height;
        private final int start;
        private final int end;
        private final int sourceLine;
        /** The box and the source line that make this line's layout on the first call; null once made. */
        private TextBox owner;
        private Paragraph paragraph;
        private final float wrapWidth;

        Line(final TextLayout layout, final float top, final float baseline, final float height, final int start, final int end,
             final int sourceLine) {
            this(layout, top, baseline, height, start, end, sourceLine, null, null, 0);
        }

        private Line(final TextLayout layout, final float top, final float baseline, final float height, final int start,
                     final int end, final int sourceLine, final TextBox owner, final Paragraph paragraph, final float wrapWidth) {
            this.layout = layout;
            this.top = top;
            this.baseline = baseline;
            this.height = height;
            this.start = start;
            this.end = end;
            this.sourceLine = sourceLine;
            this.owner = owner;
            this.paragraph = paragraph;
            this.wrapWidth = wrapWidth;
        }

        TextLayout layout() {
            if (paragraph != null) {
                layout = owner.lineLayout(paragraph, start, end, wrapWidth);
                paragraph = null;
                owner = null;
            }
            return layout;
        }

        float top() {
            return top;
        }

        float baseline() {
            return baseline;
        }

        float height() {
            return height;
        }

        int start() {
            return start;
        }

        int end() {
            return end;
        }

        int sourceLine() {
            return sourceLine;
        }
    }

    /**
     * A source line that wraps, {@code [at, at + length)}, with its measurer. When one font sets all of it, every line
     * it wraps to at any width has the ascent, descent and leading of {@link #metrics}, the first line laid out.
     */
    private static final class Paragraph {
        private final LineBreakMeasurer measurer;
        private final int at;
        private final int length;
        private final boolean oneFont;
        private TextLayout metrics;

        Paragraph(final LineBreakMeasurer measurer, final int at, final int length, final boolean oneFont) {
            this.measurer = measurer;
            this.at = at;
            this.length = length;
            this.oneFont = oneFont;
        }
    }

    private final Rich rich;
    private final String text;
    private int width = -1;
    private Style style;
    private List<Line> lines = List.of();
    private float height;
    private boolean wrap = true;
    /** Unwrapped layouts of source lines that fit a line, by line start; valid for {@link #shapedStyle}. */
    private Map<Integer, TextLayout> singles = new HashMap<>();
    /** The source lines that wrap, by line start; valid for {@link #shapedStyle}. */
    private Map<Integer, Paragraph> paragraphs = new HashMap<>();
    private Style shapedStyle;
    private int paragraphsShaped;
    private int linesLaidOut;

    TextBox(Rich rich) {
        this.rich = rich;
        this.text = rich.text();
    }

    /** No wrapping: long lines are cut at the width (code and diffs keep their columns readable when wide). */
    TextBox noWrap() {
        wrap = false;
        return this;
    }

    String text() {
        return text;
    }

    int length() {
        return text.length();
    }

    Rich rich() {
        return rich;
    }

    List<Line> lines() {
        return lines;
    }

    /** Lays out at {@code w} pixels; returns the height. Free when width and style are unchanged. */
    int layout(final int w, final Style s) {
        if (w == width && s == style) {
            return Math.round(height);
        }
        width = w;
        style = s;
        final List<Line> out = new ArrayList<>();
        float y = 0;
        final float emptyHeight = s.height(rich.baseFont());
        final LineMetrics lm = rich.baseFont().getLineMetrics("Xg", Style.FRC);
        int sourceLine = 0;
        int at = 0;
        final float wrapWidth = Math.max(20f, w);
        while (at <= text.length()) {
            final int nl = text.indexOf('\n', at);
            final int end = nl < 0 ? text.length() : nl;
            if (end == at) {
                out.add(new Line(null, y, y + lm.getAscent(), emptyHeight, at, at, sourceLine));
                y += emptyHeight;
            } else if (fitsOnOneLine(at, end, wrapWidth, s)) {
                final TextLayout layout = single(at, end, s);
                final float h = layout.getAscent() + layout.getDescent() + layout.getLeading() + s.lineGap;
                out.add(new Line(layout, y, y + layout.getAscent() + s.lineGap / 2f, h, at, end, sourceLine));
                y += h;
            } else {
                y = wrap(paragraph(at, end, s), wrapWidth, y, sourceLine, s, out);
            }
            if (nl < 0) {
                break;
            }
            at = nl + 1;
            sourceLine++;
        }
        lines = out;
        height = y;
        return Math.round(height);
    }

    /**
     * Adds the lines {@code p} wraps to at {@code wrapWidth} to {@code out}, from {@code top}; returns the bottom.
     * The breaks are the measurer's. A source line in one font lays out its first line at its first wrap, for the
     * metrics, and every other line on first use; any other source line lays out every line here.
     */
    private float wrap(final Paragraph p, final float wrapWidth, final float top, final int sourceLine, final Style s,
                       final List<Line> out) {
        final LineBreakMeasurer measurer = p.measurer;
        measurer.setPosition(0);
        float y = top;
        while (measurer.getPosition() < p.length) {
            final int from = measurer.getPosition();
            final TextLayout layout;
            if (p.metrics == null) {
                layout = measurer.nextLayout(wrapWidth);
                linesLaidOut++;
                if (p.oneFont) {
                    p.metrics = layout;
                }
            } else {
                layout = null;
                measurer.setPosition(measurer.nextOffset(wrapWidth));
            }
            final TextLayout metrics = layout != null ? layout : p.metrics;
            final float h = metrics.getAscent() + metrics.getDescent() + metrics.getLeading() + s.lineGap;
            final float baseline = y + metrics.getAscent() + s.lineGap / 2f;
            final int start = p.at + from;
            final int end = p.at + measurer.getPosition();
            out.add(layout != null ? new Line(layout, y, baseline, h, start, end, sourceLine)
                    : new Line(null, y, baseline, h, start, end, sourceLine, this, p, wrapWidth));
            y += h;
        }
        return y;
    }

    /**
     * The layout of {@code [start, end)}, one of the lines {@code p} wraps to at {@code wrapWidth}: the measurer
     * breaks there again, so the layout is the one its pass over the source line would have made.
     */
    private TextLayout lineLayout(final Paragraph p, final int start, final int end, final float wrapWidth) {
        final LineBreakMeasurer measurer = p.measurer;
        measurer.setPosition(start - p.at);
        linesLaidOut++;
        return measurer.nextLayout(wrapWidth, end - p.at, false);
    }

    int height() {
        return Math.round(height);
    }

    /**
     * True when source line {@code [at, end)} fits {@code w} unwrapped. A line that may fit by its length is
     * laid out once and the layout kept across widths, so short lines and code cost nothing on a resize.
     */
    private boolean fitsOnOneLine(int at, int end, float w, Style s) {
        if (!wrap) {
            return true;
        }
        float estimate = (end - at) * rich.baseFont().getSize2D() * 0.62f;
        return estimate <= w && single(at, end, s).getAdvance() <= w;
    }

    private TextLayout single(final int at, final int end, final Style s) {
        shapeFor(s);
        return singles.computeIfAbsent(at, k -> new TextLayout(rich.attributed(at, end).getIterator(), Style.FRC));
    }

    /** Source line {@code [at, end)}, which wraps, with its measurer, made on its first wrap with {@code s}. */
    private Paragraph paragraph(final int at, final int end, final Style s) {
        shapeFor(s);
        return paragraphs.computeIfAbsent(at, k -> {
            final AttributedCharacterIterator it = rich.attributed(at, end).getIterator();
            final boolean oneFont = it.getRunLimit(TextAttribute.FONT) == it.getEndIndex();
            paragraphsShaped++;
            return new Paragraph(new LineBreakMeasurer(it, Style.FRC), at, end - at, oneFont);
        });
    }

    /** Drops the shaped source lines when {@code s} is not the style they were shaped with. */
    private void shapeFor(final Style s) {
        if (shapedStyle != s) {
            singles = new HashMap<>();
            paragraphs = new HashMap<>();
            shapedStyle = s;
        }
    }

    /**
     * Takes the line layouts and the measurers of {@code previous} for the source lines that end before {@code prefix},
     * where both texts are the same: a block that grows at its end lays out only its new lines. Only before this box's
     * first layout.
     */
    void adoptLines(final TextBox previous, final int prefix) {
        if (previous.shapedStyle == null || !singles.isEmpty() || !paragraphs.isEmpty()) {
            return;
        }
        shapedStyle = previous.shapedStyle;
        previous.singles.forEach((at, layout) -> {
            if (previous.endsBefore(at, prefix)) {
                singles.put(at, layout);
            }
        });
        previous.paragraphs.forEach((at, p) -> {
            if (previous.endsBefore(at, prefix)) {
                paragraphs.put(at, p);
            }
        });
    }

    /** True when the source line starting at {@code at} ends with a newline before {@code prefix}. */
    private boolean endsBefore(final int at, final int prefix) {
        final int end = text.indexOf('\n', at);
        return end >= 0 && end < prefix;
    }

    /** The source lines that wrap shaped for a measurer, since the box was made; the layout work it did, for tests. */
    int paragraphsShaped() {
        return paragraphsShaped;
    }

    /** The wrapped lines laid out by a measurer since the box was made; the layout work it did, for tests. */
    int linesLaidOut() {
        return linesLaidOut;
    }

    /** The width the box was laid out at. */
    int width() {
        return width;
    }

    /**
     * Paints at {@code (x, y)}; {@code [selStart, selEnd)} and the find ranges are highlighted first.
     * Lines outside {@code clipTop..clipBottom} (box coordinates) are skipped.
     */
    void paint(Graphics2D g, float x, float y, float clipTop, float clipBottom, int selStart, int selEnd, List<int[]> finds,
               int currentFind) {
        for (Line line : lines) {
            if (line.top() + line.height() < clipTop || line.top() > clipBottom) {
                continue;
            }
            Color bg = rich.hasLineBackgrounds() ? rich.lineBackground(line.sourceLine()) : null;
            if (bg != null) {
                g.setColor(bg);
                g.fill(new Rectangle2D.Float(x - 4, y + line.top(), width + 8, line.height()));
            }
            if (line.layout() == null) {
                continue;
            }
            for (int i = 0; finds != null && i < finds.size(); i++) {
                int[] f = finds.get(i);
                highlight(g, line, x, y, f[0], f[1], i == currentFind ? style.findCurrent : style.findMatch);
            }
            if (selStart < selEnd) {
                highlight(g, line, x, y, selStart, selEnd, style.selection);
            }
            line.layout().draw(g, x, y + line.baseline());
        }
    }

    private static void highlight(Graphics2D g, Line line, float x, float y, int from, int to, Color color) {
        int s = Math.max(from, line.start());
        int e = Math.min(to, line.end());
        if (s >= e) {
            return;
        }
        Shape shape = line.layout().getLogicalHighlightShape(s - line.start(), e - line.start());
        g.setColor(color);
        g.fill(AffineTransform.getTranslateInstance(x, y + line.baseline()).createTransformedShape(shape));
    }

    /** The character index at {@code (px, py)} in box coordinates, clamped to the text. */
    int indexAt(float px, float py) {
        if (lines.isEmpty()) {
            return 0;
        }
        Line line = lines.getLast();
        for (Line l : lines) {
            if (py < l.top() + l.height()) {
                line = l;
                break;
            }
        }
        if (line.layout() == null) {
            return line.start();
        }
        TextHitInfo hit = line.layout().hitTestChar(px, py - line.baseline());
        return Math.min(line.end(), line.start() + hit.getInsertionIndex());
    }

    /** The link under {@code (px, py)}, null when none. */
    String linkAt(float px, float py) {
        for (Line l : lines) {
            if (py >= l.top() && py < l.top() + l.height() && l.layout() != null) {
                if (px < 0 || px > l.layout().getAdvance()) {
                    return null;
                }
                TextHitInfo hit = l.layout().hitTestChar(px, py - l.baseline());
                return rich.linkAt(l.start() + hit.getCharIndex());
            }
        }
        return null;
    }

    /** The top of the line holding {@code index}, for scrolling to a match. */
    float topOf(int index) {
        for (Line l : lines) {
            if (index < l.end() || l == lines.getLast()) {
                return l.top();
            }
        }
        return 0;
    }

    /** The widest line's advance, for table columns. */
    float naturalWidth() {
        float max = 0;
        int at = 0;
        while (at <= text.length()) {
            int nl = text.indexOf('\n', at);
            int end = nl < 0 ? text.length() : nl;
            if (end > at) {
                max = Math.max(max, new TextLayout(rich.attributed(at, end).getIterator(), Style.FRC).getAdvance());
            }
            if (nl < 0) {
                break;
            }
            at = nl + 1;
        }
        return max;
    }
}
