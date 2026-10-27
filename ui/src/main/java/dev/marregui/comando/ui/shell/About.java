package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.AttributedString;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * About comando: its icon, name and tagline, the facts of this installation, a few links and the maker's line, worded by
 * {@code about.md} beside this class. The file's heading is the name, its first paragraph the tagline, its table the
 * facts, its paragraph of links the links and its last paragraph the maker's line; {@code {version}}, {@code {java}},
 * {@code {claude}}, {@code {system}}, {@code {home}} and {@code {year}} take this run's values, and a fact left empty is
 * not shown. A link to {@code comando:tutorial} or {@code comando:home} opens the tutorial or the data root.
 * <p>
 * The window is drawn by hand: warm charcoal, a hairline in the tint, the name in Helvetica Neue Light, the tagline and the
 * maker's line in Charter italic, the facts' labels in spaced small capitals; Esc, ⌘W or a click elsewhere closes it.
 */
final class About {

    record Row(String label, String value) {
    }

    /** A link of the card: its words and where it goes. */
    record Way(String text, String target) {
    }

    record Content(String name, String tagline, List<Row> rows, List<Way> links, String footer) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");
    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();

    private About() {
    }

    /** The content of {@code about.md} with this run's {@code values}. */
    static Content load(Map<String, String> values) {
        try (InputStream in = About.class.getResourceAsStream("about.md")) {
            if (in == null) {
                throw new IllegalStateException("about.md is missing from the build");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), values);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read about.md", e);
        }
    }

    /** {@code markdown} as About's content, its placeholders replaced by {@code values}; one not in them stays as written. */
    static Content parse(String markdown, Map<String, String> values) {
        String name = "";
        List<String> paragraphs = new ArrayList<>();
        List<Row> rows = new ArrayList<>();
        List<Way> links = new ArrayList<>();
        for (Node n = PARSER.parse(markdown).getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h && name.isEmpty()) {
                name = fill(text(h), values);
            } else if (n instanceof TableBlock table) {
                rows.addAll(rows(table, values));
            } else if (n instanceof Paragraph p && hasLink(p)) {
                for (Node c = p.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof Link l) {
                        links.add(new Way(fill(text(l), values), fill(l.getDestination(), values)));
                    }
                }
            } else if (n instanceof Paragraph p) {
                paragraphs.add(fill(text(p), values));
            }
        }
        // the first paragraph is the tagline, the last the maker's line
        String tagline = paragraphs.isEmpty() ? "" : paragraphs.getFirst();
        String footer = paragraphs.size() < 2 ? "" : paragraphs.getLast();
        return new Content(name, tagline, List.copyOf(rows), List.copyOf(links), footer);
    }

    private static List<Row> rows(TableBlock table, Map<String, String> values) {
        List<Row> rows = new ArrayList<>();
        for (Node part = table.getFirstChild(); part != null; part = part.getNext()) {
            if (!(part instanceof TableBody body)) {
                continue; // the head: About's table has no titles
            }
            for (Node r = body.getFirstChild(); r != null; r = r.getNext()) {
                if (r instanceof TableRow row && row.getFirstChild() != null && row.getFirstChild().getNext() != null) {
                    String label = fill(text(row.getFirstChild()), values);
                    String value = fill(text(row.getFirstChild().getNext()), values);
                    if (!value.isBlank()) {
                        rows.add(new Row(label, value));
                    }
                }
            }
        }
        return rows;
    }

    private static boolean hasLink(Paragraph p) {
        for (Node c = p.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Link) {
                return true;
            }
        }
        return false;
    }

    private static String text(Node node) {
        StringBuilder out = new StringBuilder();
        collect(node, out);
        return out.toString().strip();
    }

    private static void collect(Node node, StringBuilder out) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            switch (c) {
                case Text t -> out.append(t.getLiteral());
                case Code code -> out.append(code.getLiteral());
                case SoftLineBreak b -> out.append(' ');
                default -> collect(c, out);
            }
        }
    }

    private static String fill(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * This run's values: comando's {@code version}, the Java runtime, the Claude Code version the sessions reported
     * ({@code claude}, empty when none has started), the system, the data root {@code home} (the home folder as
     * {@code ~}) and the year.
     */
    static Map<String, String> facts(String version, Path home, String claude) {
        Map<String, String> v = new HashMap<>();
        v.put("version", version == null ? "" : version);
        v.put("java", Runtime.version() + " · " + System.getProperty("java.vendor", ""));
        v.put("claude", claude == null ? "" : claude);
        String os = System.getProperty("os.name", "");
        v.put("system", (os.startsWith("Mac") ? "macOS" : os) + " " + System.getProperty("os.version", "") + " · "
                + System.getProperty("os.arch", ""));
        v.put("home", home == null ? "" : Labels.shortPath(home.toAbsolutePath().toString()));
        v.put("year", String.valueOf(Year.now().getValue()));
        return v;
    }

    /**
     * Shows About over {@code owner}: borderless, rounded, centred on it; {@code follow} gets the target of a link
     * clicked, after the window closes.
     */
    static JDialog show(Window owner, Content content, Consumer<String> follow) {
        JDialog d = new JDialog(owner, "About " + content.name());
        d.setUndecorated(true);
        d.setBackground(Theme.CLEAR);
        d.getRootPane().setOpaque(false);
        d.getRootPane().putClientProperty("Window.shadow", Boolean.TRUE);
        Card card = new Card(content, target -> {
            d.dispose();
            follow.accept(target);
        });
        d.setContentPane(card);
        for (KeyStroke k : List.of(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                KeyStroke.getKeyStroke(KeyEvent.VK_W, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()))) {
            d.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, "close");
        }
        d.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                d.dispose();
            }
        });
        d.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                d.dispose();
            }
        });
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return d;
    }

    /** About's card, drawn by hand; package-private for tests. */
    static final class Card extends JComponent {

        static final int WIDTH = 440;
        private static final int RADIUS = 22;
        private static final int ICON = 104;
        private static final int SIDE = 48;
        private static final int ROW = 25;

        private static final Color TOP = Theme.ABOUT_TOP;
        private static final Color BOTTOM = Theme.ABOUT_BOTTOM;
        private static final Color INK = Theme.ABOUT_INK;
        private static final Color SOFT = Theme.ABOUT_SOFT;
        private static final Color MUTED = Theme.ABOUT_MUTED;

        private static final Font NAME = face("HelveticaNeue-Light", Font.PLAIN, 34f, Font.SANS_SERIF);
        private static final Font TAGLINE = face("Charter-Italic", Font.ITALIC, 16f, Font.SERIF);
        private static final Font LABEL = tracked(face("AvenirNext-DemiBold", Font.BOLD, 9.5f, Font.SANS_SERIF), 0.14f);
        private static final Font VALUE = face("HelveticaNeue", Font.PLAIN, 12.5f, Font.SANS_SERIF);
        private static final Font LINK = tracked(face("AvenirNext-Medium", Font.PLAIN, 10.5f, Font.SANS_SERIF), 0.12f);
        private static final Font FOOTER = face("Charter-Italic", Font.ITALIC, 12.5f, Font.SERIF);
        private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

        private final Content content;
        private final Consumer<String> follow;
        private final List<TextLayout> tagline;
        private final int height;
        private final List<Rectangle> linkBounds = new ArrayList<>();
        private int hovered = -1;
        private Point dragFrom;
        private BufferedImage icon;
        private BufferedImage shadow;
        private int iconPixels;

        Card(Content content, Consumer<String> follow) {
            this.content = content;
            this.follow = follow;
            this.tagline = lines(content.tagline(), TAGLINE, WIDTH - 2 * SIDE - 20);
            this.height = layoutHeight();
            setOpaque(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hover(linkAt(e.getPoint()));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover(-1);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    dragFrom = linkAt(e.getPoint()) < 0 ? e.getPoint() : null;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    // the card has no title bar: it moves by its body
                    Window w = SwingUtilities.getWindowAncestor(Card.this);
                    if (dragFrom != null && w != null) {
                        Point at = w.getLocation();
                        w.setLocation(at.x + e.getX() - dragFrom.x, at.y + e.getY() - dragFrom.y);
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int i = linkAt(e.getPoint());
                    if (i >= 0) {
                        follow.accept(content.links().get(i).target());
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(WIDTH, height);
        }

        /** The index of the link at {@code p}, or -1. */
        int linkAt(Point p) {
            for (int i = 0; i < linkBounds.size(); i++) {
                if (linkBounds.get(i).contains(p)) {
                    return i;
                }
            }
            return -1;
        }

        /** Where each link is drawn; empty until the card is painted. */
        List<Rectangle> linkBounds() {
            return List.copyOf(linkBounds);
        }

        private void hover(int i) {
            if (i != hovered) {
                hovered = i;
                setCursor(Cursor.getPredefinedCursor(i >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                repaint();
            }
        }

        // ---- layout: the same steps paint follows

        private int layoutHeight() {
            int y = 44 + ICON + 26;
            y += lineHeight(NAME) + 6;
            for (TextLayout l : tagline) {
                y += lineHeight(l);
            }
            y += 22 + 1 + 22;
            y += content.rows().size() * ROW;
            y += content.rows().isEmpty() ? 0 : 20;
            y += content.links().isEmpty() ? 0 : 18 + 26;
            y += content.footer().isEmpty() ? 0 : lineHeight(FOOTER);
            return y + 34;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                int w = getWidth();
                int h = getHeight();
                RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, RADIUS * 2f, RADIUS * 2f);
                g.setPaint(new GradientPaint(0, 0, TOP, 0, h, BOTTOM));
                g.fill(shape);
                g.setColor(Theme.withAlpha(Theme.tint().body, 72));
                g.draw(shape);

                int y = 44;
                paintIcon(g, (w - ICON) / 2, y);
                y += ICON + 26;
                y = centred(g, layout(content.name(), NAME), INK, w, y);
                y += 6;
                for (TextLayout l : tagline) {
                    y = centred(g, l, SOFT, w, y);
                }
                y += 22;
                g.setColor(Theme.withAlpha(Theme.tint().body, 170));
                g.fillRect(w / 2 - 20, y, 40, 1);
                y += 1 + 22;
                y = paintRows(g, w, y);
                if (!content.rows().isEmpty()) {
                    y += 20;
                }
                if (!content.links().isEmpty()) {
                    paintLinks(g, w, y);
                    y += 18 + 26;
                }
                if (!content.footer().isEmpty()) {
                    centred(g, layout(content.footer(), FOOTER), MUTED, w, y);
                }
            } finally {
                g.dispose();
            }
        }

        private int paintRows(Graphics2D g, int w, int y) {
            if (content.rows().isEmpty()) {
                return y;
            }
            float labels = 0;
            float values = 0;
            for (Row r : content.rows()) {
                labels = Math.max(labels, layout(r.label().toUpperCase(Locale.ROOT), LABEL).getAdvance());
                values = Math.max(values, layout(r.value(), VALUE).getAdvance());
            }
            float gap = 16;
            float valueMax = w - 2 * SIDE - labels - gap;
            float block = labels + gap + Math.min(values, valueMax);
            float left = (w - block) / 2f;
            for (Row r : content.rows()) {
                TextLayout label = layout(r.label().toUpperCase(Locale.ROOT), LABEL);
                TextLayout value = layout(fit(r.value(), valueMax), VALUE);
                float baseline = y + ROW / 2f + value.getAscent() / 2f - 1;
                g.setColor(MUTED);
                label.draw(g, left + labels - label.getAdvance(), baseline - 0.5f);
                g.setColor(INK);
                value.draw(g, left + labels + gap, baseline);
                y += ROW;
            }
            return y;
        }

        private void paintLinks(Graphics2D g, int w, int y) {
            List<TextLayout> texts = new ArrayList<>();
            TextLayout dot = layout("·", VALUE);
            float spacing = 14;
            float total = 0;
            for (Way l : content.links()) {
                TextLayout t = layout(l.text().toUpperCase(Locale.ROOT), LINK);
                texts.add(t);
                total += t.getAdvance();
            }
            total += (texts.size() - 1) * (2 * spacing + dot.getAdvance());
            float x = (w - total) / 2f;
            float baseline = y + 13;
            linkBounds.clear();
            for (int i = 0; i < texts.size(); i++) {
                TextLayout t = texts.get(i);
                g.setColor(i == hovered ? Theme.tint().highlight : Theme.tint().body);
                t.draw(g, x, baseline);
                if (i == hovered) {
                    g.fillRect(Math.round(x), Math.round(baseline + 3), Math.round(t.getAdvance()), 1);
                }
                linkBounds.add(new Rectangle(Math.round(x) - 4, y - 2, Math.round(t.getAdvance()) + 8, 22));
                x += t.getAdvance();
                if (i < texts.size() - 1) {
                    x += spacing;
                    g.setColor(MUTED);
                    dot.draw(g, x, baseline);
                    x += dot.getAdvance() + spacing;
                }
            }
        }

        /** Draws {@code l} centred on the card, its top at {@code y}; returns the y below it. */
        private static int centred(Graphics2D g, TextLayout l, Color color, int w, int y) {
            g.setColor(color);
            l.draw(g, (w - l.getAdvance()) / 2f, y + l.getAscent());
            return y + lineHeight(l);
        }

        // ---- the icon: drawn from its 1024-pixel picture at the screen's scale, over a soft shadow

        private void paintIcon(Graphics2D g, int x, int y) {
            double scale = g.getTransform().getScaleX();
            int pixels = (int) Math.round(ICON * Math.max(1, scale));
            if (icon == null || iconPixels != pixels) {
                BufferedImage source = AppIcon.load();
                if (source == null) {
                    return;
                }
                icon = scaled(source, pixels);
                shadow = shadowOf(icon);
                iconPixels = pixels;
            }
            Graphics2D s = (Graphics2D) g.create();
            s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            s.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.42f));
            s.drawImage(shadow, x - 18, y - 4, ICON + 36, ICON + 36, null);
            s.dispose();
            Graphics2D i = (Graphics2D) g.create();
            i.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            i.drawImage(icon, x, y, ICON, ICON, null);
            i.dispose();
        }

        /** {@code source} at {@code pixels} a side, halved step by step so the downscale keeps its detail. */
        private static BufferedImage scaled(BufferedImage source, int pixels) {
            BufferedImage img = source;
            int side = img.getWidth();
            do {
                side = Math.max(pixels, side / 2);
                BufferedImage next = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = next.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(img, 0, 0, side, side, null);
                g.dispose();
                img = next;
            } while (side > pixels);
            return img;
        }

        /** The icon's silhouette in black, small and padded, so drawn large it is a soft shadow. */
        private static BufferedImage shadowOf(BufferedImage icon) {
            int small = 14;
            int pad = 3;
            BufferedImage s = new BufferedImage(small + 2 * pad, small + 2 * pad, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = s.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(icon, pad, pad + 1, small, small, null);
            g.setComposite(AlphaComposite.SrcIn);
            g.setColor(Theme.SHADOW);
            g.fillRect(0, 0, s.getWidth(), s.getHeight());
            g.dispose();
            return s;
        }

        // ---- type

        private static Font face(String name, int style, float size, String fallback) {
            Font f = new Font(name, Font.PLAIN, 1);
            boolean found = !f.getFamily().equals(Font.DIALOG);
            return found ? f.deriveFont(size) : new Font(fallback, style, 1).deriveFont(size);
        }

        private static Font tracked(Font f, float tracking) {
            return f.deriveFont(Map.of(TextAttribute.TRACKING, tracking));
        }

        private static TextLayout layout(String text, Font font) {
            return new TextLayout(text.isEmpty() ? " " : text, font, FRC);
        }

        private static int lineHeight(Font font) {
            return lineHeight(layout("Hg", font));
        }

        private static int lineHeight(TextLayout l) {
            return (int) Math.ceil(l.getAscent() + l.getDescent() + l.getLeading());
        }

        /** {@code text} in lines no wider than {@code width}. */
        private static List<TextLayout> lines(String text, Font font, float width) {
            List<TextLayout> out = new ArrayList<>();
            if (text.isEmpty()) {
                return out;
            }
            AttributedString s = new AttributedString(text, Map.of(TextAttribute.FONT, font));
            LineBreakMeasurer m = new LineBreakMeasurer(s.getIterator(), FRC);
            while (m.getPosition() < text.length()) {
                out.add(m.nextLayout(width));
            }
            return out;
        }

        /** {@code text} cut in the middle with an ellipsis until it fits {@code width}, as a long path is. */
        static String fit(String text, float width) {
            if (layout(text, VALUE).getAdvance() <= width) {
                return text;
            }
            int keep = text.length() - 1;
            while (keep > 2) {
                String cut = text.substring(0, keep / 2) + "…" + text.substring(text.length() - (keep - keep / 2));
                if (layout(cut, VALUE).getAdvance() <= width) {
                    return cut;
                }
                keep--;
            }
            return "…";
        }
    }
}
