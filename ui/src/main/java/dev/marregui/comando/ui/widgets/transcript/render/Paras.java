package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.kit.text.Highlighter;
import dev.marregui.comando.ui.widgets.transcript.doc.Para;
import dev.marregui.comando.ui.widgets.transcript.doc.Span;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Painters for the paragraphs of Markdown text. Each rebuilds its text only when the style changes. */
final class Paras {

    private Paras() {
    }

    /** A paragraph's painter; coordinates are its top left. */
    interface View {
        int layout(int w, Style s);

        void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks);

        void collect(int x, int y, Item owner, List<Placed> out);

        Para para();
    }

    static View of(Para p) {
        return switch (p) {
            case Para.Text t -> new TextView(t);
            case Para.Code c -> new CodeView(c);
            case Para.Table t -> new TableView(t);
            case Para.Rule r -> new RuleView(r);
        };
    }

    static Rich rich(List<Span> spans, Font base, Color color, Style s, boolean keepFont) {
        Rich r = new Rich(base, color);
        for (Span sp : spans) {
            boolean code = sp.has(Span.CODE);
            Font f = code ? s.mono : keepFont ? base : s.font(sp.style(), false);
            Color c = sp.isLink() ? s.link : color;
            int start = r.length();
            r.append(sp.text(), f, c, code ? s.inlineCodeBackground : null, sp.isLink(), sp.has(Span.STRIKE));
            if (sp.isLink()) {
                r.link(start, r.length(), sp.href());
            }
        }
        return r;
    }

    static void paintBox(Graphics2D g, TextBox b, int x, int y, Rectangle clip, Marks marks) {
        int[] sel = marks.selection(b);
        b.paint(g, x, y, clip.y - y, clip.y + clip.height - y, sel[0], sel[1], marks.finds(b), marks.currentFind(b));
    }

    // ---- text

    static final class TextView implements View {
        private final Para.Text para;
        private Style built;
        private TextBox box;
        private TextLayout marker;
        private int indent;
        private int top;
        private int width;

        TextView(Para.Text para) {
            this.para = para;
        }

        @Override
        public Para para() {
            return para;
        }

        @Override
        public int layout(int w, Style s) {
            if (s != built) {
                built = s;
                Font base = switch (para.kind()) {
                    case HEADING -> s.headings[Math.clamp(para.level() - 1, 0, 5)];
                    case QUOTE -> s.italic;
                    default -> s.body;
                };
                Color color = para.kind() == Para.Kind.QUOTE ? s.muted : s.text;
                box = new TextBox(rich(para.spans(), base, color, s, para.kind() == Para.Kind.HEADING));
                marker = para.marker().isEmpty() ? null : new TextLayout(para.marker(), s.body, Style.FRC);
            }
            indent = para.depth() * s.indent + (para.kind() == Para.Kind.QUOTE ? s.indent : 0);
            top = para.kind() == Para.Kind.HEADING ? s.paraGap / 2 : 0;
            width = w;
            int h = box.length() == 0 ? Math.round(s.height(s.body)) : box.layout(Math.max(40, w - indent), s);
            return top + h + (para.kind() == Para.Kind.HEADING && para.level() <= 2 ? 6 : 0);
        }

        @Override
        public void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            Style s = built;
            if (marker != null) {
                g.setColor(s.muted);
                float baseline = box.lines().isEmpty() ? y + top + marker.getAscent() : y + top + box.lines().getFirst().baseline();
                marker.draw(g, x + indent - marker.getAdvance() - 6, baseline);
            }
            if (para.kind() == Para.Kind.QUOTE) {
                g.setColor(s.muted);
                g.fillRect(x + indent - s.indent + 4, y, 3, box.height());
            }
            paintBox(g, box, x + indent, y + top, clip, marks);
            if (para.kind() == Para.Kind.HEADING && para.level() <= 2) {
                g.setColor(new Color(s.muted.getRed(), s.muted.getGreen(), s.muted.getBlue(), 80));
                g.fillRect(x, y + top + box.height() + 3, width, 1);
            }
        }

        @Override
        public void collect(int x, int y, Item owner, List<Placed> out) {
            if (box.length() > 0) {
                out.add(new Placed(box, x + indent, y + top, owner));
            }
        }
    }

    // ---- code

    static final class CodeView implements View {
        private static final int INSET = 8;
        private final Para.Code para;
        private final String text;
        private final List<Highlighter.Run> colors;
        private Style built;
        private TextBox box;
        private int indent;

        CodeView(Para.Code para) {
            this.para = para;
            this.text = Rich.detab(para.text());
            this.colors = Highlighter.runs(text, para.language());
        }

        @Override
        public Para para() {
            return para;
        }

        @Override
        public int layout(int w, Style s) {
            if (s != built) {
                built = s;
                Rich r = new Rich(s.mono, s.text).append(text);
                for (Highlighter.Run c : colors) {
                    r.color(c.start(), c.end(), c.color());
                }
                box = new TextBox(r);
            }
            indent = para.depth() * s.indent;
            return box.layout(Math.max(40, w - indent - 2 * INSET), s) + 2 * INSET;
        }

        @Override
        public void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            g.setColor(built.codeBackground);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.fillRoundRect(x + indent, y, box.width() + 2 * INSET, box.height() + 2 * INSET, 8, 8);
            paintBox(g, box, x + indent + INSET, y + INSET, clip, marks);
        }

        @Override
        public void collect(int x, int y, Item owner, List<Placed> out) {
            out.add(new Placed(box, x + indent + INSET, y + INSET, owner));
        }
    }

    // ---- table

    static final class TableView implements View {
        private static final int CELL = 6;
        private final Para.Table para;
        private Style built;
        private TextBox[][] cells;
        private int[] widths;
        private int[] heights;
        private int indent;

        TableView(Para.Table para) {
            this.para = para;
        }

        @Override
        public Para para() {
            return para;
        }

        @Override
        public int layout(int w, Style s) {
            int columns = para.rows().stream().mapToInt(List::size).max().orElse(0);
            if (s != built) {
                built = s;
                cells = new TextBox[para.rows().size()][columns];
                for (int r = 0; r < para.rows().size(); r++) {
                    List<List<Span>> row = para.rows().get(r);
                    for (int c = 0; c < columns; c++) {
                        List<Span> spans = c < row.size() ? row.get(c) : List.of();
                        cells[r][c] = new TextBox(rich(spans, r == 0 ? s.bold : s.body, s.text, s, r == 0));
                    }
                }
            }
            indent = para.depth() * s.indent;
            int avail = Math.max(80, w - indent);
            widths = new int[columns];
            float[] natural = new float[columns];
            float total = 0;
            for (int c = 0; c < columns; c++) {
                for (TextBox[] row : cells) {
                    natural[c] = Math.max(natural[c], row[c].naturalWidth() + 2 * CELL + 2);
                }
                total += natural[c];
            }
            for (int c = 0; c < columns; c++) {
                widths[c] = total <= avail ? Math.round(natural[c]) : Math.max(40, Math.round(natural[c] * avail / total));
            }
            heights = new int[cells.length];
            int h = 0;
            for (int r = 0; r < cells.length; r++) {
                int rh = 0;
                for (int c = 0; c < columns; c++) {
                    rh = Math.max(rh, cells[r][c].layout(widths[c] - 2 * CELL, s));
                }
                heights[r] = rh + 2 * CELL;
                h += heights[r];
            }
            return h + 1;
        }

        @Override
        public void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            int tableWidth = 0;
            for (int w : widths) {
                tableWidth += w;
            }
            int ry = y;
            for (int r = 0; r < cells.length; r++) {
                if (r == 0) {
                    g.setColor(built.codeBackground);
                    g.fillRect(x + indent, ry, tableWidth, heights[r]);
                }
                int cx = x + indent;
                for (int c = 0; c < widths.length; c++) {
                    paintBox(g, cells[r][c], cx + CELL, ry + CELL, clip, marks);
                    cx += widths[c];
                }
                g.setColor(new Color(0x4E5157));
                g.fillRect(x + indent, ry, tableWidth, 1);
                ry += heights[r];
            }
            g.fillRect(x + indent, ry, tableWidth, 1);
            int cx = x + indent;
            for (int width : widths) {
                g.fillRect(cx, y, 1, ry - y);
                cx += width;
            }
            g.fillRect(cx, y, 1, ry - y + 1);
        }

        @Override
        public void collect(int x, int y, Item owner, List<Placed> out) {
            int ry = y;
            for (int r = 0; r < cells.length; r++) {
                int cx = x + indent;
                for (int c = 0; c < widths.length; c++) {
                    out.add(new Placed(cells[r][c], cx + CELL, ry + CELL, owner));
                    cx += widths[c];
                }
                ry += heights[r];
            }
        }
    }

    // ---- rule

    static final class RuleView implements View {
        private final Para.Rule para;
        private Style built;
        private int width;

        RuleView(Para.Rule para) {
            this.para = para;
        }

        @Override
        public Para para() {
            return para;
        }

        @Override
        public int layout(int w, Style s) {
            built = s;
            width = w;
            return s.paraGap;
        }

        @Override
        public void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks) {
            g.setColor(built.muted);
            g.fillRect(x, y + built.paraGap / 2, width, 1);
        }

        @Override
        public void collect(int x, int y, Item owner, List<Placed> out) {
            // nothing to select
        }
    }

    /** Reuses the views of paragraphs that did not change, so streaming relays out only the last one. */
    static List<View> reuse(List<Para> next, List<View> previous) {
        Map<Para, ArrayDeque<View>> old = new HashMap<>();
        for (View v : previous) {
            old.computeIfAbsent(v.para(), k -> new ArrayDeque<>()).add(v);
        }
        List<View> out = new ArrayList<>(next.size());
        for (Para p : next) {
            ArrayDeque<View> same = old.get(p);
            out.add(same != null && !same.isEmpty() ? same.poll() : of(p));
        }
        return out;
    }
}
