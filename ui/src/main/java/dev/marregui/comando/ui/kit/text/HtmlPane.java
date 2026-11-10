package dev.marregui.comando.ui.kit.text;

import java.awt.AWTEvent;
import java.awt.Adjustable;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JEditorPane;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

/**
 * A read-only HTML pane styled from the theme, for rendered Markdown. Every link is followed at a double click
 * (record 94): a link to a heading of the page ({@code #} and its id) scrolls to it; any other link, a web page or a
 * file, goes to the {@link LinkHandler}.
 */
public final class HtmlPane extends JEditorPane {

    private static final System.Logger LOG = System.getLogger(HtmlPane.class.getName());

    /**
     * Receives the links double-clicked or activated from the keyboard, except those to a heading of the page: a web
     * page, a file beside the document, a {@code [[name]]} note. Unless set, {@link Links} follows them, read from the
     * page's folder.
     */
    public interface LinkHandler {
        void open(String href);
    }

    private LinkHandler links = href -> Links.open(href, this.folder);

    public void setLinkHandler(final LinkHandler handler) {
        links = handler;
    }

    /** A change of width smaller than this keeps the pictures as they are sized. */
    private static final int RESIZE_SLACK = 16;
    /** The width the page's pictures and diagrams were sized for; -1 when it has none. */
    private int sizedFor = -1;
    /**
     * Sizes the pictures again once a resize settles: a page rendered before it was laid out had no width to fit. The
     * 150 ms settle is unmeasured, chosen as a pause longer than the steps of a window drag.
     */
    private final Debouncer resized = new Debouncer(150, () -> redraw(this.markdown));

    public HtmlPane() {
        setContentType("text/html");
        final OwnStyles kit = new OwnStyles();
        kit.setStyleSheet(styles());
        setEditorKit(kit);
        setEditable(false);
        setOpaque(false);
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        // the body's colour: the pane honours its display properties over the style sheet's
        setForeground(Theme.TRANSCRIPT_TEXT);
        // the page grows as its pictures arrive and settles as it is laid out at its width: a reader taken to a
        // heading and not scrolled since stays at the heading
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                if (anchored != null && getParent() instanceof JViewport vp && vp.getViewPosition().y == anchoredY) {
                    scrollToHeading(anchored);
                }
                if (sizedFor >= 0 && Math.abs(getWidth() - sizedFor) > RESIZE_SLACK) {
                    resized.request();
                }
            }
        });
        addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            final String href = e.getURL() != null ? e.getURL().toString() : e.getDescription();
            if (href == null) {
                return;
            }
            // the pane activates a link at every click of a double click; a keyboard activation has no clicks. A link,
            // to a page or a file, is followed at the double click, as everywhere in comando (record 94)
            final int clicks = e.getInputEvent() instanceof MouseEvent m ? m.getClickCount() : 0;
            if (clicks != 0 && clicks != 2) {
                return;
            }
            final String raw = e.getDescription();
            if (raw != null && raw.startsWith("#")) {
                scrollToHeading(raw.substring(1));
            } else {
                links.open(href);
            }
        });
    }

    /**
     * Scrolls the heading whose id is {@code fragment} (percent-decoded; the exact id first, then one differing only
     * in case) to the top of the view; the top of the page for an empty fragment. The caret goes there too, so a
     * render of a new version of the text keeps the reader there. False when the page has no such heading.
     */
    public boolean scrollToHeading(final String fragment) {
        final String wanted = decoded(fragment);
        int at = wanted.isEmpty() ? 0 : -1;
        int loose = -1;
        final ElementIterator it = new ElementIterator(getDocument());
        for (Element e = it.next(); e != null && at < 0; e = it.next()) {
            if (e.getAttributes().getAttribute(HTML.Attribute.ID) instanceof String id) {
                if (id.equals(wanted)) {
                    at = e.getStartOffset();
                } else if (loose < 0 && id.equalsIgnoreCase(wanted)) {
                    loose = e.getStartOffset();
                }
            }
        }
        if (at < 0) {
            at = loose;
        }
        if (at < 0) {
            return false;
        }
        // the page's height follows its width: a pane laid out once, as a panel just made is, is too short to scroll
        // to its heading until laid out again at that width
        jumping = true;
        try {
            if (getParent() instanceof JViewport vp) {
                vp.doLayout();
            }
            setCaretPosition(at);
            final Rectangle2D r = modelToView2D(at);
            if (r != null) {
                final Rectangle visible = getVisibleRect();
                scrollRectToVisible(new Rectangle(visible.x, at == 0 ? 0 : (int) r.getY(), 1, visible.height));
            }
            anchored = fragment;
            anchoredY = getParent() instanceof JViewport vp ? vp.getViewPosition().y : -1;
            if (getParent() == heard && EventQueue.getCurrentEvent() instanceof InputEvent) {
                // the reader's link: a place of their choosing, though the view may be there already
                readerMoves++;
                readerJumpedDown(anchoredY);
            }
        } catch (final BadLocationException e) {
            return false;
        } finally {
            jumping = false;
        }
        return true;
    }

    private static String decoded(final String fragment) {
        try {
            return URLDecoder.decode(fragment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            return fragment;
        }
    }

    /** Stops hearing the tint chosen; set while the pane is in a window. */
    private Runnable stopHearingTint;
    /** The ground the page's styles were made under. */
    private Ground styledGround = Theme.groundChosen();
    /** The tint the page's styles were made under. */
    private Tint styledTint = Theme.tint();

    @Override
    public void addNotify() {
        super.addNotify();
        // a page's headings, table heads and rules are the tint chosen: another chosen, the page is drawn again
        stopHearingTint = Theme.onTint(this::followTheme);
        // a theme chosen while the pane was in no window went unheard
        followTheme();
    }

    @Override
    public void removeNotify() {
        if (stopHearingTint != null) {
            stopHearingTint.run();
            stopHearingTint = null;
        }
        super.removeNotify();
    }

    /** Draws the page again when the ground or the tint chosen is not the one its styles were made under. */
    public void followTheme() {
        if (styledGround != Theme.groundChosen() || styledTint != Theme.tint()) {
            restyle();
        }
    }

    /** The page drawn again under its rules and the tint chosen. */
    private void restyle() {
        styledGround = Theme.groundChosen();
        styledTint = Theme.tint();
        final String html = markdown == null ? getText() : null;
        final HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
        kit.setStyleSheet(styles());
        // setText fills the document it has, which keeps its first style sheet: a new document takes the new rules
        setDocument(kit.createDefaultDocument());
        if (markdown != null) {
            redraw(markdown);
        } else {
            setText(html);
        }
    }

    /**
     * The page's rules, over Swing's defaults. It reads as a turn's answer in the transcript: ivory text, headings in
     * the headings' serif and the chosen tint over a tinted rule, at the transcript's scale
     * ({@link Theme#HEADING_SCALE}), tinted table heads on a band of the tint's shade, bold brighter, code in the
     * content palette (record 101), quotes beside a tinted bar.
     */
    static StyleSheet styles() {
        final List<String> rules = rules();
        final Styles last = shared;
        if (last != null && last.rules().equals(rules)) {
            return last.sheet();
        }
        final StyleSheet css = new StyleSheet();
        css.addStyleSheet(new HTMLEditorKit().getStyleSheet());
        // Swing parses each length as a number first and catches the failure for "2px": building once per rule set
        // keeps that, and the parse, from repeating for every pane
        rules.forEach(css::addRule);
        shared = new Styles(rules, css);
        return css;
    }

    /**
     * The sheet {@link #styles} last built and the rules it was built from; panes share it and never change it. Written
     * and read on the EDT, where panes are made and restyled; volatile for a test that asks for the sheet from its own
     * thread.
     */
    private static volatile Styles shared;

    private record Styles(List<String> rules, StyleSheet sheet) {
    }

    /** The page's rules under the theme, the tint and the font size as they are now. */
    private static List<String> rules() {
        final List<String> rules = new ArrayList<>();
        final String fg = Theme.hex(Theme.TRANSCRIPT_TEXT);
        final String border = Theme.hex(Theme.CARD_BORDER);
        final String muted = Theme.hex(Theme.MUTED);
        final String link = Theme.hex(Theme.LINK);
        final String mono = Theme.mono().getFamily();
        final String serif = Theme.serif().getFamily();
        final Tint tint = Theme.tint();
        final int size = UIManager.getFont("Label.font").getSize();
        final float[] scale = Theme.HEADING_SCALE;
        rules.add("body { font-family: sans-serif; font-size: " + size + "pt; color: " + fg + "; margin: 0; }");
        rules.add("p { margin-top: 2px; margin-bottom: 6px; }");
        rules.add("h1, h2, h3 { font-family: '" + serif + "'; font-weight: bold; color: " + Theme.hex(tint.body) + "; }");
        rules.add("h1 { font-size: " + Math.round(size * scale[0]) + "pt; margin: 6px 0 4px 0; }");
        rules.add("h2 { font-size: " + Math.round(size * scale[1]) + "pt; margin: 6px 0 4px 0; }");
        rules.add("h3 { font-size: " + Math.round(size * scale[2]) + "pt; margin: 4px 0 2px 0; }");
        rules.add("h1, h2 { border-bottom-width: 1px; border-bottom-style: solid; border-bottom-color: " + Theme.hex(tint.headingRule())
                + "; padding-bottom: 3px; }");
        rules.add("b, strong { color: " + Theme.hex(Theme.ANSWER_STRONG) + "; }");
        rules.add("code { font-family: '" + mono + "'; color: " + Theme.hex(Theme.content().code) + "; background-color: "
                + Theme.hex(Theme.content().codeInlineGround) + "; }");
        rules.add("pre { font-family: '" + mono + "'; background-color: " + Theme.hex(Theme.content().codeGround) + "; padding: 6px; margin: 4px 0 6px 0; }");
        rules.add("pre.code code { color: " + fg + "; }");
        rules.add("a { color: " + link + "; }");
        rules.add("table { margin: 4px 0 6px 0; }");
        // the grid is the table's ground showing through one pixel between cells of the panel's ground: Swing draws a CSS
        // border as a polygon per side of every cell, and on the Metal pipeline each one flushes the render queue, which
        // froze the EDT for up to 4 s painting a page of tables; a cell's ground is one rectangle
        rules.add("table.grid { background-color: " + border + "; }");
        rules.add("th { padding: 2px 6px; color: " + Theme.hex(tint.highlight) + "; background-color: " + Theme.hex(tint.tableHeadGround()) + "; }");
        rules.add("td { padding: 2px 6px; background-color: " + Theme.hex(Theme.ground()) + "; }");
        rules.add("blockquote { margin-left: 8px; color: " + fg + "; font-style: italic; border-left-width: 3px; border-left-style: solid; "
                + "border-left-color: " + Theme.hex(tint.body) + "; padding-left: 8px; }");
        // front matter: its facts as a tab's card shows them, the key muted beside the value, no grid
        rules.add("table.front { margin: 0 0 8px 0; }");
        // Swing reads padding side by side, not a shorthand of four
        rules.add("td.front-key { border-width: 0; padding-top: 1px; padding-bottom: 1px; padding-left: 0; padding-right: 14px; color: "
                + muted + "; }");
        rules.add("td.front-value { border-width: 0; padding-top: 1px; padding-bottom: 1px; padding-left: 0; padding-right: 0; }");
        rules.add("ul, ol { margin-left: 18px; margin-top: 2px; margin-bottom: 4px; }");
        return rules;
    }

    /**
     * Swing's kit with a style sheet of its own: {@link HTMLEditorKit#setStyleSheet} sets one shared by every kit, so
     * one pane's rules would become every pane's.
     */
    private static final class OwnStyles extends HTMLEditorKit {
        private StyleSheet styles;

        @Override
        public StyleSheet getStyleSheet() {
            return styles;
        }

        @Override
        public void setStyleSheet(final StyleSheet s) {
            styles = s;
        }
    }

    /** The Markdown shown, so a diagram drawn later refreshes the pane only while it still shows the same text. */
    private String markdown;
    /** What is shaded as changed in {@link #markdown}; kept through a redraw. */
    private MarkdownHtml.Marks marks = MarkdownHtml.Marks.NONE;
    /** How the page lies against the other side of a comparison; none for any other page. */
    private MarkdownHtml.Anchors anchors = MarkdownHtml.Anchors.NONE;

    /** The folder the Markdown's relative image links are read from; null for Markdown that is no file's. */
    private Path folder;
    /**
     * The heading the reader was last taken to, and the view's top then: a redraw or a resize that finds the view
     * still there scrolls to the heading again, since a picture drawn above it, or a new width, moves it. Null when
     * none.
     */
    private String anchored;
    private int anchoredY = -1;

    /** The viewport the pane last kept its view in, whose moves it hears; null before the first. */
    private JViewport heard;
    /** Where that viewport was last seen, so a change of its size alone is not taken for a move. */
    private Point seenAt;
    /** How many times the reader moved that viewport, by wheel, bar, keys or a heading's link. */
    private int readerMoves;
    /**
     * How many of those moves took the view, across and down, to a place of the reader's choosing: a thumb dragged,
     * a heading's link. A thumb places the view along its own bar only.
     */
    private int readerJumpsX;
    private int readerJumpsY;
    /** Where the last of those took the view, along each axis. */
    private final Point jumpedTo = new Point();
    /**
     * How far the reader's moves by a step (wheel, track, keys) took that viewport, summed along each axis since the
     * reader's last jump along it; the page's own moves are not in it.
     */
    private final Point readerMoved = new Point();
    /** True while the pane scrolls to a heading: a heading's link the reader follows takes the view to that place. */
    private boolean jumping;
    /**
     * Above zero while the pane moves the view itself: the caret's scroll, the view put back. Those moves are not the
     * reader's, even while the reader holds a bar.
     */
    private int ownMoves;
    /** The bars of the scroll pane around {@link #heard}, whose presses the pane hears; empty before the first. */
    private List<JScrollBar> heardBars = List.of();
    /**
     * True while the reader holds a mouse button on one of those bars: the steps the bar repeats then, from a timer,
     * are the reader's.
     */
    private boolean barHeld;
    private final MouseAdapter barPressed = new MouseAdapter() {
        @Override
        public void mousePressed(final MouseEvent e) {
            barHeld = true;
        }

        @Override
        public void mouseReleased(final MouseEvent e) {
            barHeld = false;
        }
    };
    /**
     * Counts the reader's moves of the view: a move made while the reader's input is dispatched, or while the reader
     * holds a bar and the pane is not moving the view itself. The page's own moves, the caret's scroll and the view put
     * back, run from the queue afterwards.
     */
    private final ChangeListener viewMoved = e -> {
        if (e.getSource() instanceof JViewport vp) {
            final Point p = vp.getViewPosition();
            final AWTEvent current = EventQueue.getCurrentEvent();
            if (!p.equals(seenAt) && (current instanceof InputEvent || barHeld && ownMoves == 0)) {
                readerMoves++;
                final int along = jumping ? Adjustable.VERTICAL : placesTheThumbAlong(current);
                readerMoved.translate(p.x - seenAt.x, p.y - seenAt.y);
                if (along == Adjustable.VERTICAL) {
                    readerJumpedDown(p.y);
                } else if (along == Adjustable.HORIZONTAL) {
                    readerJumpsX++;
                    jumpedTo.x = p.x;
                    readerMoved.x = 0;
                }
            }
            seenAt = p;
        }
    };

    /** Records that the reader took the view down the page to {@code y}, a place of their choosing. */
    private void readerJumpedDown(final int y) {
        readerJumpsY++;
        jumpedTo.y = y;
        readerMoved.y = 0;
    }

    /**
     * What a change to the page keeps until the view is put back: where the view was, the reader's moves then, and the
     * heading the reader was taken to and has not scrolled from, else null.
     */
    private record Kept(Point at, int moves, int jumpsX, int jumpsY, Point moved, String heading) {
    }

    /** The place the changes to the page keep while a put-back is queued; null when none is. */
    private Kept keeping;
    /** How many put-backs are queued. */
    private int putBacks;

    /**
     * The diagrams and pictures of the page shown that were not ready when it was rendered: the id of the element
     * that stands in for each, and its HTML once it is ready, else null. Emptied at every render.
     */
    private final Map<String, Supplier<String>> waitingFor = new LinkedHashMap<>();
    private int waitingIds;
    /** Whether a fill is posted to the EDT: the arrivals of a burst are put in place by one fill. */
    private final AtomicBoolean fillPosted = new AtomicBoolean();
    /**
     * Runs, on a drawing or reading thread, when a diagram or picture the page waits for is ready. The one callback
     * the pane hands out, so the pane waits once for each, however often the page is rendered meanwhile.
     */
    private final Runnable arrived = () -> {
        if (fillPosted.compareAndSet(false, true)) {
            Edt.later(this::fill);
        }
    };

    /**
     * Shows {@code markdown} rendered; its Mermaid diagrams as pictures once {@link MermaidDiagrams} has drawn them.
     */
    public void setMarkdown(final String markdown) {
        if (getParent() instanceof JViewport vp) {
            // the reader's moves and presses are heard from the first page shown, not from the first one drawn again
            hear(vp);
        }
        this.markdown = markdown;
        sizedFor = -1;
        waitingFor.clear();
        final String html = MarkdownHtml.render(markdown, this::diagram, this::picture, marks, anchors);
        setHtml(fallBackInHeadings(html, Theme.serif()));
    }

    /**
     * Shows {@code markdown}, the text of a file in {@code folder}: an image link to a picture on this machine, beside
     * the file or by its full path, shows the picture.
     */
    public void setMarkdown(final String markdown, final Path folder) {
        setMarkdown(markdown, folder, MarkdownHtml.Marks.NONE);
    }

    /** As {@link #setMarkdown(String, Path)}, what {@code marks} names shaded as changed, in the theme's colours. */
    public void setMarkdown(final String markdown, final Path folder, final MarkdownHtml.Marks marks) {
        setMarkdown(markdown, folder, marks, MarkdownHtml.Anchors.NONE);
    }

    /** As {@link #setMarkdown(String, Path, MarkdownHtml.Marks)}, laid out for a comparison as {@code anchors} says. */
    public void setMarkdown(final String markdown, final Path folder, final MarkdownHtml.Marks marks,
                            final MarkdownHtml.Anchors anchors) {
        this.folder = folder;
        this.marks = marks;
        this.anchors = anchors;
        anchored = null;
        setMarkdown(markdown);
    }

    /** Lays the page shown out as {@code anchors} says, the view where the reader left it. */
    public void anchors(final MarkdownHtml.Anchors anchors) {
        if (!anchors.equals(this.anchors)) {
            this.anchors = anchors;
            redraw(markdown);
        }
    }

    /**
     * The height on the page of each block that says its source line, by that line, the topmost when several say it,
     * and of the page's end under {@link MarkdownHtml.Anchors#END}. Empty while the pane has no size to lay it out at.
     */
    public NavigableMap<Integer, Integer> anchorHeights() {
        final NavigableMap<Integer, Integer> out = new TreeMap<>();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return out;
        }
        final ElementIterator it = new ElementIterator(getDocument());
        try {
            for (Element e = it.next(); e != null; e = it.next()) {
                final AttributeSet a = e.getAttributes();
                // what is inside a block reads the block's attribute through it: only the block itself defines it
                if (a.isDefined(MarkdownHtml.LINE) && a.getAttribute(MarkdownHtml.LINE) instanceof String line) {
                    final Rectangle2D r = modelToView2D(e.getStartOffset());
                    if (r != null) {
                        out.putIfAbsent(Integer.valueOf(line), (int) r.getY());
                    }
                }
            }
        } catch (final BadLocationException e) {
            throw new IllegalStateException("an element's own offset", e);
        }
        out.put(MarkdownHtml.Anchors.END, getPreferredSize().height);
        return out;
    }

    /** Runs now the redraw that waits for a resize to settle; false when none waits. For tests. */
    boolean flushResize() {
        if (!resized.pending()) {
            return false;
        }
        resized.stop();
        redraw(markdown);
        return true;
    }

    /** The HTML of an image link: its picture when it names one on this machine, else null for its alt text. */
    private String picture(final String destination, final String alt) {
        final Optional<Path> found = LocalImages.resolve(folder, destination);
        if (found.isEmpty()) {
            return null;
        }
        final Path file = found.get();
        final LocalImages.Picture p = LocalImages.current().picture(file, arrived);
        sizedFor = getWidth();
        final String html = pictureHtml(p, alt, file, pictureWidth());
        if (!(p instanceof LocalImages.Picture.Pending)) {
            return html;
        }
        final String id = waiting(() -> {
            final LocalImages.Picture now = LocalImages.current().picture(file, arrived);
            return now instanceof LocalImages.Picture.Pending ? null : pictureHtml(now, alt, file, pictureWidth());
        });
        // inline: the picture may sit in a line of text
        return "<span id=\"" + id + "\">" + html + "</span>";
    }

    /** The widest a picture or diagram is shown, in points; zero or less when the pane has no width yet. */
    private int pictureWidth() {
        final Insets in = getInsets();
        return getWidth() - in.left - in.right - 8;
    }

    /** Records what stands in for a diagram or picture not ready yet; returns the id of the element standing in. */
    private String waiting(final Supplier<String> ready) {
        // a heading's id is GitHub's slug, which has no colon: the two cannot meet
        final String id = "comando:waiting:" + waitingIds++;
        waitingFor.put(id, ready);
        return id;
    }

    /**
     * Puts each diagram and picture that is ready in place of the element standing in for it, with the view where
     * the reader left it. The rest of the page is not rendered again: on a long page with many diagrams that takes
     * seconds, once for every diagram drawn.
     */
    private void fill() {
        fillPosted.set(false);
        final Map<String, String> ready = new LinkedHashMap<>();
        for (final Iterator<Map.Entry<String, Supplier<String>>> it = waitingFor.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Supplier<String>> w = it.next();
            final String html = w.getValue().get();
            if (html != null) {
                ready.put(w.getKey(), html);
                it.remove();
            }
        }
        if (ready.isEmpty() || !(getDocument() instanceof HTMLDocument doc)) {
            return;
        }
        keepingView(() -> {
            for (final Map.Entry<String, String> r : ready.entrySet()) {
                final Element stand = doc.getElement(r.getKey());
                if (stand == null) {
                    continue;
                }
                try {
                    doc.setOuterHTML(stand, inItsLink(stand, r.getValue()));
                } catch (final BadLocationException | IOException e) {
                    // not expected of Swing's own document; the whole page drawn again shows it all the same
                    LOG.log(System.Logger.Level.DEBUG, "cannot put a picture in place [id=" + r.getKey() + ']', e);
                    final String shown = markdown;
                    Edt.later(() -> redraw(shown));
                    return;
                }
            }
        });
    }

    /** {@code html} inside the link the element standing for it is in, if any: replacing the element drops its link. */
    private static String inItsLink(final Element stand, final String html) {
        if (stand.getAttributes().getAttribute(HTML.Tag.A) instanceof AttributeSet a
                && a.getAttribute(HTML.Attribute.HREF) instanceof String href) {
            return "<a href=\"" + attribute(href) + "\">" + html + "</a>";
        }
        return html;
    }

    /** {@code text} as an attribute's value: escaped, quotes included, so it cannot end the attribute. */
    static String attribute(final String text) {
        return MarkdownHtml.escape(text).replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * The HTML of a picture: the image, no wider than {@code maxWidth} points when that is positive; while it is read,
     * a muted note; when it cannot be shown, its alt text and why.
     */
    static String pictureHtml(final LocalImages.Picture p, final String alt, final Path file, final int maxWidth) {
        return switch (p) {
            case LocalImages.Picture.Ready r -> img(r.file(), r.width(), r.height(), maxWidth, alt);
            case LocalImages.Picture.Pending ignored -> "<font color=\"" + Theme.hex(Theme.MUTED) + "\"><i>"
                    + MarkdownHtml.escape("loading " + file.getFileName() + "…") + "</i></font>";
            case LocalImages.Picture.Unavailable u -> "<i>" + MarkdownHtml.escape(alt.isEmpty() ? "image" : alt) + "</i> <font color=\""
                    + Theme.hex(Theme.MUTED) + "\">(" + MarkdownHtml.escape(u.why()) + ")</font>";
        };
    }

    private String diagram(final String source) {
        final MermaidDiagrams.Diagram d = MermaidDiagrams.current().diagram(source, arrived);
        sizedFor = getWidth();
        final String html = diagramHtml(source, d, pictureWidth());
        if (!(d instanceof MermaidDiagrams.Diagram.Pending)) {
            return html;
        }
        final String id = waiting(() -> {
            final MermaidDiagrams.Diagram now = MermaidDiagrams.current().diagram(source, arrived);
            return now instanceof MermaidDiagrams.Diagram.Pending ? null : diagramHtml(source, now, pictureWidth());
        });
        return "<div id=\"" + id + "\">" + html + "</div>";
    }

    /** The same text again, at a new width or in new colours, with the view where the reader left it. */
    private void redraw(final String shown) {
        if (shown == null || !shown.equals(markdown)) {
            return;
        }
        keepingView(() -> {
            final int caret = getCaretPosition();
            setMarkdown(shown);
            setCaretPosition(Math.min(caret, getDocument().getLength()));
        });
    }

    /**
     * Runs {@code change} to the page, then puts the view back where the reader left it, moved as far as the reader
     * moved it since if they did before it is put back.
     */
    private void keepingView(final Runnable change) {
        final Kept kept;
        if (keeping != null) {
            // a change made while an earlier one's put-back waits, as a picture put in place in a redraw's wake: the
            // view is where the earlier change's caret scrolled it, and the reader's place is the one it kept
            kept = keeping;
        } else if (getParent() instanceof JViewport vp) {
            final Point at = hear(vp);
            // taken to a heading and not scrolled since: the heading, wherever the new pictures put it
            kept = new Kept(at, readerMoves, readerJumpsX, readerJumpsY, new Point(readerMoved),
                    anchored != null && at.y == anchoredY ? anchored : null);
        } else {
            change.run();
            return;
        }
        keeping = kept;
        putBacks++;
        try {
            change.run();
        } finally {
            SwingUtilities.invokeLater(() -> {
                if (--putBacks == 0) {
                    keeping = null;
                }
                putBack(kept);
            });
        }
    }

    /** Puts the view back at the place {@code kept}, moved as the reader moved it since. */
    private void putBack(final Kept kept) {
        if (readerMoves != kept.moves()) {
            // the reader moved the view since, and the caret's scroll, queued by the change, took it to the caret,
            // before or between those moves: the place the reader last chose, else the place kept, plus how far the
            // reader's steps moved it since, unless the pane has left the viewport it was moved in
            if (getParent() == heard) {
                final boolean acrossChosen = readerJumpsX != kept.jumpsX();
                final boolean downChosen = readerJumpsY != kept.jumpsY();
                final int x = (acrossChosen ? jumpedTo.x : kept.at().x) + readerMoved.x
                        - (acrossChosen ? 0 : kept.moved().x);
                final int y = (downChosen ? jumpedTo.y : kept.at().y) + readerMoved.y
                        - (downChosen ? 0 : kept.moved().y);
                movingTheView(() -> heard.setViewPosition(new Point(x, y)));
            }
        } else if (kept.heading() != null) {
            movingTheView(() -> scrollToHeading(kept.heading()));
        } else if (getParent() instanceof JViewport vp) {
            // drawn again as it joined a window, it may have left it since
            movingTheView(() -> vp.setViewPosition(kept.at()));
        }
    }

    /** Runs {@code move}, a move of the view the pane makes itself. */
    private void movingTheView(final Runnable move) {
        ownMoves++;
        try {
            move.run();
        } finally {
            ownMoves--;
        }
    }

    /**
     * As the editor's, the move marked as the pane's own, which matters only while the reader holds a bar outside any
     * input: the caret's deferred scroll comes here. A move made here while the reader's input is dispatched, as Page
     * Down's, is the reader's all the same.
     */
    @Override
    public void scrollRectToVisible(final Rectangle r) {
        movingTheView(() -> super.scrollRectToVisible(r));
    }

    /**
     * Whether a move of a scroll bar made while {@code input} is dispatched puts its thumb where the reader points: a
     * drag of the thumb, or a press where the look and feel moves the thumb to the press (the middle button). A press
     * of the left button on the track steps a page. Only a move says so: a press that moves nothing is not one.
     */
    public static boolean placesTheThumb(final InputEvent input) {
        return placesTheThumbAlong(input) >= 0;
    }

    /**
     * As {@link #placesTheThumb}, along which axis of the page, {@link Adjustable#HORIZONTAL} or
     * {@link Adjustable#VERTICAL}, the bar's thumb is placed; -1 when it is not.
     */
    private static int placesTheThumbAlong(final AWTEvent event) {
        if (event instanceof MouseEvent m && m.getSource() instanceof JScrollBar bar
                && (m.getID() == MouseEvent.MOUSE_DRAGGED
                || m.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isMiddleMouseButton(m))) {
            return bar.getOrientation();
        }
        return -1;
    }

    /** Hears the reader's moves of {@code vp}, the pane's viewport, from now on; where its view is. */
    private Point hear(final JViewport vp) {
        if (vp != heard) {
            if (heard != null) {
                heard.removeChangeListener(viewMoved);
            }
            for (final JScrollBar bar : heardBars) {
                bar.removeMouseListener(barPressed);
            }
            vp.addChangeListener(viewMoved);
            heard = vp;
            heardBars = vp.getParent() instanceof JScrollPane sp
                    ? List.of(sp.getVerticalScrollBar(), sp.getHorizontalScrollBar()) : List.of();
            for (final JScrollBar bar : heardBars) {
                bar.addMouseListener(barPressed);
            }
            barHeld = false;
        }
        seenAt = vp.getViewPosition();
        return seenAt;
    }

    /**
     * The HTML of a Mermaid block: its picture, no wider than {@code maxWidth} points when that is positive; else its
     * source as code under a muted note saying why there is no picture yet.
     */
    static String diagramHtml(final String source, final MermaidDiagrams.Diagram d, final int maxWidth) {
        return switch (d) {
            case MermaidDiagrams.Diagram.Ready r -> "<p>" + img(r.png(), r.width(), r.height(), maxWidth, "diagram") + "</p>";
            case MermaidDiagrams.Diagram.Pending p -> note("drawing the diagram…") + code(source);
            case MermaidDiagrams.Diagram.Unavailable u -> note(u.why()) + code(source);
        };
    }

    /** An image of a file, no wider than {@code maxWidth} points when that is positive, its height scaled with it. */
    private static String img(final Path file, final int width, final int height, final int maxWidth, final String alt) {
        int w = width;
        int h = height;
        if (maxWidth > 0 && w > maxWidth) {
            h = Math.max(1, (int) ((long) h * maxWidth / w));
            w = maxWidth;
        }
        return "<img src=\"" + attribute(file.toUri().toString()) + "\" width=\"" + w + "\" height=\"" + h + "\" alt=\"" + attribute(alt) + "\">";
    }

    private static String note(final String text) {
        return "<p><font color=\"" + Theme.hex(Theme.MUTED) + "\"><i>" + MarkdownHtml.escape(text) + "</i></font></p>";
    }

    private static String code(final String source) {
        return "<pre class=\"code\"><code>" + MarkdownHtml.escape(source) + "</code></pre>";
    }

    private static final Pattern HEADING = Pattern.compile("(<h[1-3][^>]*>)(.*?)(</h[1-3]>)", Pattern.DOTALL);

    /**
     * {@code html} with the characters of its first three levels of heading that {@code serif} cannot draw set in
     * Java's logical sans serif, as the transcript does: Swing's HTML draws a glyph its font lacks as nothing, and New
     * York lacks the arrow. Tags, entities and code inside a heading are left as they are.
     */
    static String fallBackInHeadings(final String html, final Font serif) {
        final Matcher m = HEADING.matcher(html);
        final StringBuilder out = new StringBuilder(html.length() + 64);
        while (m.find()) {
            final String inner = m.group(2);
            final StringBuilder h = new StringBuilder(inner.length() + 32);
            int code = 0;
            int i = 0;
            while (i < inner.length()) {
                final char c = inner.charAt(i);
                if (c == '<' || c == '&') {
                    int end = inner.indexOf(c == '<' ? '>' : ';', i);
                    end = end < 0 ? inner.length() - 1 : end;
                    final String piece = inner.substring(i, end + 1);
                    code += piece.startsWith("<code") ? 1 : piece.startsWith("</code") ? -1 : 0;
                    h.append(piece);
                    i = end + 1;
                    continue;
                }
                final int cp = inner.codePointAt(i);
                if (code > 0 || serif.canDisplay(cp)) {
                    h.appendCodePoint(cp);
                    i += Character.charCount(cp);
                    continue;
                }
                int j = i;
                while (j < inner.length() && inner.charAt(j) != '<' && inner.charAt(j) != '&' && !serif.canDisplay(inner.codePointAt(j))) {
                    j += Character.charCount(inner.codePointAt(j));
                }
                h.append("<span style=\"font-family: Dialog\">").append(inner, i, j).append("</span>");
                i = j;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + h + m.group(3)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Shows {@code bodyHtml} as the page's body; text from outside must be escaped by the caller. */
    public void setHtml(final String bodyHtml) {
        setText("<html><body>" + bodyHtml + "</body></html>");
    }

}
