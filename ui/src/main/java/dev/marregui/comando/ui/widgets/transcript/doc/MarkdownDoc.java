package dev.marregui.comando.ui.widgets.transcript.doc;

import dev.marregui.comando.ui.kit.text.Fences;
import dev.marregui.comando.ui.kit.text.NoteLinks;
import dev.marregui.comando.ui.kit.text.TextLinks;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown to the transcript's paragraph list. A soft line break stays a line break: Claude's text uses
 * single newlines as line ends. Raw HTML is kept as literal text. {@code path:line} references in text and
 * code spans become links, and so do web addresses and {@code [[name]]} notes in text.
 */
public final class MarkdownDoc {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private MarkdownDoc() {
    }

    public static List<Para> parse(String markdown) {
        List<Para> out = new ArrayList<>();
        blocks(PARSER.parse(markdown), 0, out);
        return out;
    }

    private static void blocks(Node parent, int depth, List<Para> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            block(n, depth, "", out);
        }
    }

    private static void block(Node n, int depth, String marker, List<Para> out) {
        switch (n) {
            case Heading h -> out.add(new Para.Text(Para.Kind.HEADING, h.getLevel(), depth, "", inlines(h)));
            case Paragraph p -> out.add(new Para.Text(Para.Kind.PARAGRAPH, 0, depth, marker, inlines(p)));
            case FencedCodeBlock c -> out.add(new Para.Code(Fences.language(c.getInfo()), trimEnd(c.getLiteral()), depth));
            case IndentedCodeBlock c -> out.add(new Para.Code("", trimEnd(c.getLiteral()), depth));
            case HtmlBlock h -> out.add(new Para.Code("", trimEnd(h.getLiteral()), depth));
            case ThematicBreak t -> out.add(new Para.Rule(depth));
            case BulletList l -> items(l, depth, false, 0, out);
            case OrderedList l -> items(l, depth, true, l.getMarkerStartNumber() == null ? 1 : l.getMarkerStartNumber(), out);
            case BlockQuote q -> {
                for (Node c = q.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof Paragraph p) {
                        out.add(new Para.Text(Para.Kind.QUOTE, 0, depth, "", inlines(p)));
                    } else {
                        block(c, depth + 1, "", out);
                    }
                }
            }
            case TableBlock t -> out.add(table(t, depth));
            default -> blocks(n, depth, out);
        }
    }

    private static void items(Node list, int depth, boolean ordered, int start, List<Para> out) {
        int number = start;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            String marker = ordered ? number++ + "." : depth % 2 == 0 ? "•" : "◦";
            boolean first = true;
            for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof Paragraph p) {
                    out.add(new Para.Text(Para.Kind.ITEM, 0, depth + 1, first ? marker : "", inlines(p)));
                } else {
                    if (first) {
                        out.add(new Para.Text(Para.Kind.ITEM, 0, depth + 1, marker, List.of()));
                    }
                    block(c, depth + 1, "", out);
                }
                first = false;
            }
            if (first) {
                out.add(new Para.Text(Para.Kind.ITEM, 0, depth + 1, marker, List.of()));
            }
        }
    }

    private static Para.Table table(TableBlock t, int depth) {
        List<List<List<Span>>> rows = new ArrayList<>();
        for (Node section = t.getFirstChild(); section != null; section = section.getNext()) {
            if (section instanceof TableHead || section instanceof TableBody) {
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    if (row instanceof TableRow) {
                        List<List<Span>> cells = new ArrayList<>();
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (cell instanceof TableCell) {
                                cells.add(inlines(cell));
                            }
                        }
                        rows.add(cells);
                    }
                }
            }
        }
        return new Para.Table(rows, depth);
    }

    // ---- inlines

    private static List<Span> inlines(Node parent) {
        List<Span> out = new ArrayList<>();
        inline(parent, 0, "", out);
        return merge(out);
    }

    private static void inline(Node parent, int style, String href, List<Span> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            switch (n) {
                case Text t -> text(t.getLiteral(), style, href, out);
                case Code c -> text(c.getLiteral(), style | Span.CODE, href, out);
                case Emphasis e -> inline(e, style | Span.ITALIC, href, out);
                case StrongEmphasis e -> inline(e, style | Span.BOLD, href, out);
                case Link l -> inline(l, style, l.getDestination() == null ? "" : l.getDestination(), out);
                case Image i -> inline(i, style | Span.ITALIC, href, out);
                case SoftLineBreak b -> out.add(new Span("\n", style, href));
                case HardLineBreak b -> out.add(new Span("\n", style, href));
                case HtmlInline h -> out.add(new Span(h.getLiteral(), style, href));
                default -> inline(n, isStrike(n) ? style | Span.STRIKE : style, href, out);
            }
        }
    }

    private static boolean isStrike(Node n) {
        return n.getClass().getSimpleName().equals("Strikethrough");
    }

    /**
     * Adds text, splitting out its links unless the text is inside a link: {@code path:line} references, web addresses,
     * and {@code [[name]]} notes, shown as their labels ({@link TextLinks}).
     */
    private static void text(String text, int style, String href, List<Span> out) {
        if (!href.isEmpty()) {
            out.add(new Span(text, style, href));
            return;
        }
        int at = 0;
        for (TextLinks.Ref r : TextLinks.refs(text, false)) {
            if (r.start() > at) {
                out.add(new Span(text.substring(at, r.start()), style, ""));
            }
            out.add(new Span(NoteLinks.is(r.href()) ? r.label() : text.substring(r.start(), r.end()), style, r.href()));
            at = r.end();
        }
        if (at < text.length()) {
            out.add(new Span(text.substring(at), style, ""));
        }
    }

    /** Joins neighbours with the same style and link, so a paragraph is a few runs, not one per token. */
    private static List<Span> merge(List<Span> spans) {
        List<Span> out = new ArrayList<>(spans.size());
        for (Span s : spans) {
            if (s.text().isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                Span last = out.getLast();
                if (last.style() == s.style() && last.href().equals(s.href())) {
                    out.set(out.size() - 1, new Span(last.text() + s.text(), s.style(), s.href()));
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
