package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.LineMetrics;
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
 * Coordinates are relative to the box's top left.
 */
final class TextBox {

    /** One laid-out line: {@code layout} is null for an empty line. */
    record Line(TextLayout layout, float top, float baseline, float height, int start, int end, int sourceLine) {
    }

    private final Rich rich;
    private final String text;
    private int width = -1;
    private Style style;
    private List<Line> lines = List.of();
    private float height;
    private boolean wrap = true;
    /** Unwrapped layouts of source lines that fit a line, by line start; valid for {@link #singlesStyle}. */
    private Map<Integer, TextLayout> singles = new HashMap<>();
    private Style singlesStyle;

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
    int layout(int w, Style s) {
        if (w == width && s == style) {
            return Math.round(height);
        }
        width = w;
        style = s;
        List<Line> out = new ArrayList<>();
        float y = 0;
        float emptyHeight = s.height(rich.baseFont());
        LineMetrics lm = rich.baseFont().getLineMetrics("Xg", Style.FRC);
        int sourceLine = 0;
        int at = 0;
        float wrapWidth = Math.max(20f, w);
        while (at <= text.length()) {
            int nl = text.indexOf('\n', at);
            int end = nl < 0 ? text.length() : nl;
            if (end == at) {
                out.add(new Line(null, y, y + lm.getAscent(), emptyHeight, at, at, sourceLine));
                y += emptyHeight;
            } else if (fitsOnOneLine(at, end, wrapWidth, s)) {
                TextLayout layout = single(at, end, s);
                float h = layout.getAscent() + layout.getDescent() + layout.getLeading() + s.lineGap;
                out.add(new Line(layout, y, y + layout.getAscent() + s.lineGap / 2f, h, at, end, sourceLine));
                y += h;
            } else {
                AttributedCharacterIterator it = rich.attributed(at, end).getIterator();
                LineBreakMeasurer measurer = new LineBreakMeasurer(it, Style.FRC);
                while (measurer.getPosition() < it.getEndIndex()) {
                    int from = measurer.getPosition();
                    TextLayout layout = measurer.nextLayout(wrapWidth);
                    float h = layout.getAscent() + layout.getDescent() + layout.getLeading() + s.lineGap;
                    float baseline = y + layout.getAscent() + s.lineGap / 2f;
                    out.add(new Line(layout, y, baseline, h, at + from, at + measurer.getPosition(), sourceLine));
                    y += h;
                }
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

    private TextLayout single(int at, int end, Style s) {
        if (singlesStyle != s) {
            singles = new HashMap<>();
            singlesStyle = s;
        }
        return singles.computeIfAbsent(at, k -> new TextLayout(rich.attributed(at, end).getIterator(), Style.FRC));
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
