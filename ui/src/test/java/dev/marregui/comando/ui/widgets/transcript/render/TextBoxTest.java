package dev.marregui.comando.ui.widgets.transcript.render;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.LineMetrics;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// [TextBox] lays out only what a paint needs and keeps the shaped text of its wrapped lines across widths.
/// - a width change neither shapes a wrapped source line again nor lays out a line before it is painted
/// - a layout makes the layouts of the painted lines, not of every wrapped line
/// - the wrapped lines are the lines, positions, hit tests and pixels a [LineBreakMeasurer] gives
/// - a box that grew at its end shapes only its source lines that were not whole before
class TextBoxTest {

    /// The wrapped source lines of a tool's output are shaped once, at the first layout; three width changes and a
    /// return to the first width shape none of them again and lay out none of their lines, as nothing was painted.
    @Test
    void aWidthChangeNeitherShapesNorLaysOutAWrappedLineAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final Style s = Style.of(0);
            final TextBox box = new TextBox(new Rich(s.monoSmall, s.muted).append(toolOutput(40, 600)).linked(s.link));
            box.layout(700, s);
            final int shaped = box.paragraphsShaped();
            final int laidOut = box.linesLaidOut();
            assertThat(shaped).as("each long source line wraps").isEqualTo(40);

            box.layout(690, s);
            box.layout(1100, s);
            box.layout(333, s);
            box.layout(700, s);

            assertThat(box.paragraphsShaped()).as("shaped once, kept across widths").isEqualTo(shaped);
            assertThat(box.linesLaidOut()).as("no line laid out before it is painted").isEqualTo(laidOut);
        });
    }

    /// A layout of 40 source lines that wrap to over 200 lines makes at most one line layout per source line; a paint
    /// of the first 150 pixels then makes the layouts of the lines it draws and no more.
    @Test
    void aLayoutMakesTheLayoutsOfThePaintedLinesOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final Style s = Style.of(0);
            final TextBox box = new TextBox(new Rich(s.monoSmall, s.muted).append(toolOutput(40, 600)).linked(s.link));
            box.layout(700, s);
            assertThat(box.lines()).as("the source lines wrap").hasSizeGreaterThan(200);
            assertThat(box.linesLaidOut()).as("one line per wrapped source line at most").isLessThanOrEqualTo(40);

            final int before = box.linesLaidOut();
            final float clipBottom = 150;
            final long painted = box.lines().stream().filter(l -> l.top() + l.height() >= 0 && l.top() <= clipBottom).count();
            final BufferedImage img = new BufferedImage(800, 200, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = img.createGraphics();
            box.paint(g, 0, 0, 0, clipBottom, 0, 0, null, -1);
            g.dispose();

            assertThat(box.linesLaidOut() - before).as("only the painted lines").isLessThanOrEqualTo((int) painted);
        });
    }

    /// Tool output with links, prose in three fonts, glyphs from the fallback font and right-to-left words, each box
    /// laid out at five widths in turn, one of them breaking inside words: every line has the range, top, baseline and
    /// height the measurer gives, the same advance and the same hit at nine points, laid out last line first, and the
    /// box paints with a selection pixel for pixel as the measurer's layouts do.
    @Test
    void theWrappedLinesAreTheLinesALineBreakMeasurerLaysOut() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final Style s = Style.of(0);
            for (final Rich rich : corpus(s)) {
                final TextBox box = new TextBox(rich);
                for (final int width : new int[]{700, 333, 90, 20, 700}) {
                    box.layout(width, s);
                    assertSameAsReference(box, rich, width, s);
                }
            }
        });
    }

    /// A block of ten source lines, five of them long, and the start of a sixth long one grows: the sixth is whole
    /// and a seventh long one follows. The grown box shapes those two and takes the other five from the old box; its
    /// lines are the measurer's.
    @Test
    void aGrownBoxShapesOnlyTheSourceLinesNotWholeBefore() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final Style s = Style.of(0);
            final String[] lines = toolOutput(7, 600).split("\n");
            final String whole = String.join("\n", Arrays.asList(lines).subList(0, 10)) + "\n";
            final TextBox old = new TextBox(new Rich(s.mono, s.text).append(whole + lines[10].substring(0, 300)));
            old.layout(700, s);
            final Rich grown = new Rich(s.mono, s.text).append(whole + lines[10] + "\n" + lines[12]);
            final TextBox box = new TextBox(grown);
            box.adoptLines(old, whole.length());
            box.layout(700, s);

            assertThat(box.paragraphsShaped()).as("the line that was arriving and the new one").isEqualTo(2);
            assertSameAsReference(box, grown, 700, s);
        });
    }

    private static void assertSameAsReference(final TextBox box, final Rich rich, final int width, final Style s) {
        final List<TextBox.Line> expected = reference(rich, width, s);
        final List<TextBox.Line> actual = box.lines();
        assertThat(actual).hasSameSizeAs(expected);
        // last line first: a line laid out late does not depend on which lines were laid out before it
        for (int i = expected.size() - 1; i >= 0; i--) {
            assertSameLine(actual.get(i), expected.get(i), width + " px, line " + i);
        }
        final int selStart = rich.length() / 5;
        final int selEnd = rich.length() * 3 / 5;
        final int height = Math.max(1, box.height());
        assertThat(pixels(height, g -> box.paint(g, 4, 0, 0, height, selStart, selEnd, null, -1)))
                .as("painted at " + width + " px")
                .isEqualTo(pixels(height, g -> paintReference(g, expected, selStart, selEnd, s)));
    }

    private static void assertSameLine(final TextBox.Line a, final TextBox.Line e, final String where) {
        assertThat(a.start()).as(where).isEqualTo(e.start());
        assertThat(a.end()).as(where).isEqualTo(e.end());
        assertThat(a.sourceLine()).as(where).isEqualTo(e.sourceLine());
        assertThat(a.top()).as(where).isEqualTo(e.top());
        assertThat(a.baseline()).as(where).isEqualTo(e.baseline());
        assertThat(a.height()).as(where).isEqualTo(e.height());
        if (e.layout() == null) {
            assertThat(a.layout()).as(where).isNull();
            return;
        }
        final TextLayout al = a.layout();
        final TextLayout el = e.layout();
        assertThat(al.getCharacterCount()).as(where).isEqualTo(el.getCharacterCount());
        assertThat(al.getAdvance()).as(where).isEqualTo(el.getAdvance());
        assertThat(al.getVisibleAdvance()).as(where).isEqualTo(el.getVisibleAdvance());
        for (int k = 0; k <= 8; k++) {
            final float x = el.getAdvance() * k / 8f;
            assertThat(al.hitTestChar(x, 0).getInsertionIndex()).as(where + " hit at " + x).isEqualTo(el.hitTestChar(x, 0).getInsertionIndex());
        }
    }

    /// The lines as laid out with a fresh [LineBreakMeasurer] per source line and every layout made at once.
    private static List<TextBox.Line> reference(final Rich rich, final int w, final Style s) {
        final String text = rich.text();
        final List<TextBox.Line> out = new ArrayList<>();
        final float emptyHeight = s.height(rich.baseFont());
        final LineMetrics lm = rich.baseFont().getLineMetrics("Xg", Style.FRC);
        final float wrapWidth = Math.max(20f, w);
        float y = 0;
        int sourceLine = 0;
        int at = 0;
        while (at <= text.length()) {
            final int nl = text.indexOf('\n', at);
            final int end = nl < 0 ? text.length() : nl;
            if (end == at) {
                out.add(new TextBox.Line(null, y, y + lm.getAscent(), emptyHeight, at, at, sourceLine));
                y += emptyHeight;
            } else {
                final TextLayout whole = new TextLayout(rich.attributed(at, end).getIterator(), Style.FRC);
                final boolean fits = (end - at) * rich.baseFont().getSize2D() * 0.62f <= wrapWidth && whole.getAdvance() <= wrapWidth;
                if (fits) {
                    final float h = whole.getAscent() + whole.getDescent() + whole.getLeading() + s.lineGap;
                    out.add(new TextBox.Line(whole, y, y + whole.getAscent() + s.lineGap / 2f, h, at, end, sourceLine));
                    y += h;
                } else {
                    final AttributedCharacterIterator it = rich.attributed(at, end).getIterator();
                    final LineBreakMeasurer measurer = new LineBreakMeasurer(it, Style.FRC);
                    while (measurer.getPosition() < it.getEndIndex()) {
                        final int from = measurer.getPosition();
                        final TextLayout layout = measurer.nextLayout(wrapWidth);
                        final float h = layout.getAscent() + layout.getDescent() + layout.getLeading() + s.lineGap;
                        out.add(new TextBox.Line(layout, y, y + layout.getAscent() + s.lineGap / 2f, h, at + from, at + measurer.getPosition(), sourceLine));
                        y += h;
                    }
                }
            }
            if (nl < 0) {
                break;
            }
            at = nl + 1;
            sourceLine++;
        }
        return out;
    }

    private static void paintReference(final Graphics2D g, final List<TextBox.Line> lines, final int selStart, final int selEnd, final Style s) {
        for (final TextBox.Line line : lines) {
            if (line.layout() == null) {
                continue;
            }
            final int from = Math.max(selStart, line.start());
            final int to = Math.min(selEnd, line.end());
            if (from < to) {
                final Shape shape = line.layout().getLogicalHighlightShape(from - line.start(), to - line.start());
                g.setColor(s.selection);
                g.fill(AffineTransform.getTranslateInstance(4, line.baseline()).createTransformedShape(shape));
            }
            line.layout().draw(g, 4, line.baseline());
        }
    }

    private interface Painter {
        void paint(Graphics2D g);
    }

    private static int[] pixels(final int height, final Painter painter) {
        final int width = 800;
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        painter.paint(g);
        g.dispose();
        return img.getRGB(0, 0, width, height, null, 0, width);
    }

    private static List<Rich> corpus(final Style s) {
        final List<Rich> out = new ArrayList<>();
        out.add(new Rich(s.monoSmall, s.muted).append(toolOutput(12, 400)).linked(s.link));
        final Rich prose = new Rich(s.body, s.text);
        final Random rnd = new Random(11);
        while (prose.length() < 3000) {
            prose.append(words(rnd, 12) + " ");
            prose.append(words(rnd, 3) + " ", s.bold, s.strong);
            prose.append(words(rnd, 2), s.mono, s.text);
            prose.append(" ");
        }
        out.add(prose);
        out.add(new Rich(s.monoSmall, s.text).append(("fallback → 日本語のテキスト 😀 " + words(rnd, 9) + " ").repeat(30)));
        out.add(new Rich(s.body, s.text).append(("right to left שלום עולם مرحبا بالعالم " + words(rnd, 7) + " ").repeat(30) + "\n\nshort\n"));
        return out;
    }

    /// {@code lines} source lines of {@code length} characters of words, each with a path and an address in it for
    /// links, and a short line after each.
    private static String toolOutput(final int lines, final int length) {
        final Random rnd = new Random(42);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            final StringBuilder line = new StringBuilder(String.format("%4d: see /Users/someone/src/File%d.java:%d at https://example.com/%d ", i, i, i, i));
            while (line.length() < length) {
                line.append(words(rnd, 1)).append(' ');
            }
            sb.append(line, 0, length).append('\n');
            sb.append("short ").append(i).append('\n');
        }
        return sb.toString().strip();
    }

    private static String words(final Random rnd, final int n) {
        final String[] pool = {"layout", "of", "a", "paragraph", "wraps", "at", "the", "width", "glyph", "transcript", "measure"};
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : " ").append(pool[rnd.nextInt(pool.length)]);
        }
        return sb.toString();
    }
}
