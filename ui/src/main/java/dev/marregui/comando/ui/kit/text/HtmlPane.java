package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import javax.swing.JEditorPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.View;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Font;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only HTML pane styled from the theme, for rendered Markdown. Every link is followed at a double click (record
 * 94): a link to a heading of the page ({@code #} and its id) scrolls to it; any other link, a web page or a file, goes
 * to the {@link LinkHandler}.
 */
public class HtmlPane extends JEditorPane {

    private static final System.Logger LOG = System.getLogger(HtmlPane.class.getName());

    /**
     * Receives the links double-clicked or activated from the keyboard, but a heading of the page: a web page, a file
     * beside the document, a {@code [[name]]} note. Unless set, {@link Links} follows them, read from the page's folder.
     */
    public interface LinkHandler {
        void open(String href);
    }

    private LinkHandler links = href -> Links.open(href, this.folder);

    public void setLinkHandler(LinkHandler handler) {
        links = handler;
    }

    /** A change of width smaller than this keeps the pictures as they are sized. */
    private static final int RESIZE_SLACK = 16;
    /** The width the page's pictures and diagrams were sized for; -1 when it has none. */
    private int sizedFor = -1;
    /** Sizes the pictures again once a resize settles: a page rendered before it was laid out had no width to fit. */
    private final Timer resized = new Timer(150, e -> redraw(this.markdown));

    public HtmlPane() {
        setContentType("text/html");
        resized.setRepeats(false);
        OwnStyles kit = new OwnStyles();
        kit.setStyleSheet(styles());
        setEditorKit(kit);
        setEditable(false);
        setOpaque(false);
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        // the body's colour: the pane honours its display properties over the style sheet's
        setForeground(Theme.TRANSCRIPT_TEXT);
        // the page grows as its pictures arrive and settles as it is laid out at its width: a reader taken to a heading
        // and not scrolled since stays at the heading
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (anchored != null && getParent() instanceof JViewport vp && vp.getViewPosition().y == anchoredY) {
                    scrollToHeading(anchored);
                }
                if (sizedFor >= 0 && Math.abs(getWidth() - sizedFor) > RESIZE_SLACK) {
                    resized.restart();
                }
            }
        });
        addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            String href = e.getURL() != null ? e.getURL().toString() : e.getDescription();
            if (href == null) {
                return;
            }
            // the pane activates a link at every click of a double click; a keyboard activation has no clicks. A link,
            // to a page or a file, is followed at the double click, as everywhere in comando (record 94)
            int clicks = e.getInputEvent() instanceof MouseEvent m ? m.getClickCount() : 0;
            if (clicks != 0 && clicks != 2) {
                return;
            }
            String raw = e.getDescription();
            if (raw != null && raw.startsWith("#")) {
                scrollToHeading(raw.substring(1));
            } else {
                links.open(href);
            }
        });
    }

    /**
     * Scrolls the heading whose id is {@code fragment} (percent-decoded; the exact id first, then one differing only in
     * case) to the top of the view; the top of the page for an empty fragment. The caret goes there too, so a render of
     * a new version of the text keeps the reader there. False when the page has no such heading.
     */
    public boolean scrollToHeading(String fragment) {
        String wanted = decoded(fragment);
        int at = wanted.isEmpty() ? 0 : -1;
        int loose = -1;
        ElementIterator it = new ElementIterator(getDocument());
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
        // the page's height follows its width: a pane laid out once, as a panel just made is, is too short to scroll to
        // its heading until laid out again at that width
        if (getParent() instanceof JViewport vp) {
            vp.doLayout();
        }
        setCaretPosition(at);
        try {
            Rectangle2D r = modelToView2D(at);
            if (r != null) {
                Rectangle visible = getVisibleRect();
                scrollRectToVisible(new Rectangle(visible.x, at == 0 ? 0 : (int) r.getY(), 1, visible.height));
            }
            anchored = fragment;
            anchoredY = getParent() instanceof JViewport vp ? vp.getViewPosition().y : -1;
        } catch (BadLocationException e) {
            return false;
        }
        return true;
    }

    private static String decoded(String fragment) {
        try {
            return URLDecoder.decode(fragment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return fragment;
        }
    }

    /** Stops hearing the tint chosen; set while the pane is in a window. */
    private Runnable stopHearingTint;

    @Override
    public void addNotify() {
        super.addNotify();
        // a page's headings, table heads and rules are the tint chosen: another chosen, the page is drawn again
        stopHearingTint = Theme.onTint(this::restyle);
    }

    @Override
    public void removeNotify() {
        if (stopHearingTint != null) {
            stopHearingTint.run();
            stopHearingTint = null;
        }
        super.removeNotify();
    }

    /** The page drawn again under its rules and the tint chosen. */
    private void restyle() {
        String html = markdown == null ? getText() : null;
        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
        kit.setStyleSheet(styles());
        // setText fills the document it has, which keeps the style sheet it was made with: a new one takes the new rules
        setDocument(kit.createDefaultDocument());
        if (markdown != null) {
            redraw(markdown);
        } else {
            setText(html);
        }
    }

    /**
     * The page's rules, over Swing's defaults. It reads as a turn's answer in the transcript: ivory text, headings in the
     * headings' serif and the chosen tint over a tinted rule, at the transcript's scale ({@link Theme#HEADING_SCALE}),
     * tinted table heads on a band of the tint's shade, bold brighter, inline code teal, quotes beside a tinted bar.
     */
    static StyleSheet styles() {
        StyleSheet css = new StyleSheet();
        css.addStyleSheet(new HTMLEditorKit().getStyleSheet());
        String fg = Theme.hex(Theme.TRANSCRIPT_TEXT);
        String card = Theme.hex(Theme.CARD);
        String border = Theme.hex(Theme.CARD_BORDER);
        String muted = Theme.hex(Theme.MUTED);
        String link = Theme.hex(Theme.ACCENT);
        String mono = Theme.mono().getFamily();
        String serif = Theme.serif().getFamily();
        Tint tint = Theme.tint();
        int size = UIManager.getFont("Label.font").getSize();
        float[] scale = Theme.HEADING_SCALE;
        css.addRule("body { font-family: sans-serif; font-size: " + size + "pt; color: " + fg + "; margin: 0; }");
        css.addRule("p { margin-top: 2px; margin-bottom: 6px; }");
        css.addRule("h1, h2, h3 { font-family: '" + serif + "'; font-weight: bold; color: " + Theme.hex(tint.body) + "; }");
        css.addRule("h1 { font-size: " + Math.round(size * scale[0]) + "pt; margin: 6px 0 4px 0; }");
        css.addRule("h2 { font-size: " + Math.round(size * scale[1]) + "pt; margin: 6px 0 4px 0; }");
        css.addRule("h3 { font-size: " + Math.round(size * scale[2]) + "pt; margin: 4px 0 2px 0; }");
        css.addRule("h1, h2 { border-bottom-width: 1px; border-bottom-style: solid; border-bottom-color: " + Theme.hex(tint.headingRule())
                + "; padding-bottom: 3px; }");
        css.addRule("b, strong { color: " + Theme.hex(Theme.ANSWER_STRONG) + "; }");
        css.addRule("code { font-family: '" + mono + "'; color: " + Theme.hex(Theme.ANSWER_CODE) + "; background-color: "
                + Theme.hex(Theme.ANSWER_CODE_GROUND) + "; }");
        css.addRule("pre { font-family: '" + mono + "'; background-color: " + card + "; padding: 6px; margin: 4px 0 6px 0; }");
        css.addRule("pre.code code { color: " + fg + "; }");
        css.addRule("a { color: " + link + "; }");
        css.addRule("table { border-collapse: collapse; margin: 4px 0 6px 0; }");
        css.addRule("th { border: 1px solid " + border + "; padding: 2px 6px; color: " + Theme.hex(tint.highlight) + "; background-color: "
                + Theme.hex(tint.tableHeadGround()) + "; }");
        css.addRule("td { border: 1px solid " + border + "; padding: 2px 6px; }");
        css.addRule("blockquote { margin-left: 8px; color: " + fg + "; font-style: italic; border-left-width: 3px; border-left-style: solid; "
                + "border-left-color: " + Theme.hex(tint.body) + "; padding-left: 8px; }");
        // front matter: its facts as a tab's card shows them, a quiet key and the value, no grid
        css.addRule("table.front { margin: 0 0 8px 0; }");
        // Swing reads padding side by side, not a shorthand of four
        css.addRule("td.front-key { border-width: 0; padding-top: 1px; padding-bottom: 1px; padding-left: 0; padding-right: 14px; color: "
                + muted + "; }");
        css.addRule("td.front-value { border-width: 0; padding-top: 1px; padding-bottom: 1px; padding-left: 0; padding-right: 0; }");
        css.addRule("ul, ol { margin-left: 18px; margin-top: 2px; margin-bottom: 4px; }");
        return css;
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
        public void setStyleSheet(StyleSheet s) {
            styles = s;
        }
    }

    /** The Markdown shown, so a diagram drawn later refreshes the pane only while it still shows the same text. */
    private String markdown;
    /** What is shaded as changed in {@link #markdown}; kept through a redraw. */
    private MarkdownHtml.Marks marks = MarkdownHtml.Marks.NONE;

    /** The folder the Markdown's relative image links are read from; null for Markdown that is no file's. */
    private Path folder;
    /**
     * The heading the reader was last taken to, and the view's top then: a redraw or a resize that finds the view still
     * there scrolls to the heading again, since a picture drawn above it, or a new width, moves it. Null when none.
     */
    private String anchored;
    private int anchoredY = -1;

    /**
     * The diagrams and pictures of the page shown that were not ready when it was rendered: the id of the element that
     * stands in for each, and its HTML once it is ready, else null. Emptied at every render.
     */
    private final Map<String, Supplier<String>> waitingFor = new LinkedHashMap<>();
    private int waitingIds;
    /** Whether a fill is posted to the EDT: the arrivals of a burst are put in place by one fill. */
    private final AtomicBoolean fillPosted = new AtomicBoolean();
    /**
     * Runs, on a drawing or reading thread, when a diagram or picture the page waits for is ready. The one callback the
     * pane hands out, so the pane waits once for each, however often the page is rendered meanwhile.
     */
    private final Runnable arrived = () -> {
        if (fillPosted.compareAndSet(false, true)) {
            Edt.later(this::fill);
        }
    };

    /** Shows {@code markdown} rendered; its Mermaid diagrams as pictures once {@link MermaidDiagrams} has drawn them. */
    public void setMarkdown(String markdown) {
        this.markdown = markdown;
        sizedFor = -1;
        waitingFor.clear();
        String html = MarkdownHtml.render(markdown, this::diagram, this::picture, marks);
        setHtml(fallBackInHeadings(html, Theme.serif()));
    }

    /**
     * Shows {@code markdown}, the text of a file in {@code folder}: an image link to a picture on this machine, beside
     * the file or by its full path, shows the picture.
     */
    public void setMarkdown(String markdown, Path folder) {
        setMarkdown(markdown, folder, MarkdownHtml.Marks.NONE);
    }

    /** As {@link #setMarkdown(String, Path)}, what {@code marks} names shaded as changed, in the theme's colours. */
    public void setMarkdown(String markdown, Path folder, MarkdownHtml.Marks marks) {
        this.folder = folder;
        this.marks = marks;
        anchored = null;
        setMarkdown(markdown);
    }

    /** Runs a re-size waiting for the resize to settle, now; false when none waits. For tests. */
    boolean flushResize() {
        if (!resized.isRunning()) {
            return false;
        }
        resized.stop();
        redraw(markdown);
        return true;
    }

    /** The HTML of an image link: its picture when it names one on this machine, else null for its alt text. */
    private String picture(String destination, String alt) {
        Optional<Path> found = LocalImages.resolve(folder, destination);
        if (found.isEmpty()) {
            return null;
        }
        Path file = found.get();
        LocalImages.Picture p = LocalImages.current().picture(file, arrived);
        sizedFor = getWidth();
        String html = pictureHtml(p, alt, file, pictureWidth());
        if (!(p instanceof LocalImages.Picture.Pending)) {
            return html;
        }
        String id = waiting(() -> {
            LocalImages.Picture now = LocalImages.current().picture(file, arrived);
            return now instanceof LocalImages.Picture.Pending ? null : pictureHtml(now, alt, file, pictureWidth());
        });
        // inline: the picture may sit in a line of text
        return "<span id=\"" + id + "\">" + html + "</span>";
    }

    /** The widest a picture or diagram is shown, in points; zero or less when the pane has no width yet. */
    private int pictureWidth() {
        Insets in = getInsets();
        return getWidth() - in.left - in.right - 8;
    }

    /** Records what stands in for a diagram or picture not ready yet; returns the id of the element standing in. */
    private String waiting(Supplier<String> ready) {
        // a heading's id is GitHub's slug, which has no colon: the two cannot meet
        String id = "comando:waiting:" + waitingIds++;
        waitingFor.put(id, ready);
        return id;
    }

    /**
     * Puts each diagram and picture that is ready in place of the element standing in for it, with the view where the
     * reader left it. The rest of the page is not rendered again: on a long page with many diagrams that takes seconds,
     * once for every diagram drawn.
     */
    private void fill() {
        fillPosted.set(false);
        Map<String, String> ready = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, Supplier<String>>> it = waitingFor.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Supplier<String>> w = it.next();
            String html = w.getValue().get();
            if (html != null) {
                ready.put(w.getKey(), html);
                it.remove();
            }
        }
        if (ready.isEmpty() || !(getDocument() instanceof HTMLDocument doc)) {
            return;
        }
        keepingView(() -> {
            for (Map.Entry<String, String> r : ready.entrySet()) {
                Element stand = doc.getElement(r.getKey());
                if (stand == null) {
                    continue;
                }
                try {
                    doc.setOuterHTML(stand, inItsLink(stand, r.getValue()));
                } catch (BadLocationException | IOException e) {
                    // not expected of Swing's own document; the whole page drawn again shows it all the same
                    LOG.log(System.Logger.Level.DEBUG, "cannot put a picture in place [id=" + r.getKey() + "]: " + e.getMessage());
                    String shown = markdown;
                    Edt.later(() -> redraw(shown));
                    return;
                }
            }
        });
    }

    /** {@code html} inside the link the element standing for it is in, if any: replacing the element drops its link. */
    private static String inItsLink(Element stand, String html) {
        if (stand.getAttributes().getAttribute(HTML.Tag.A) instanceof AttributeSet a
                && a.getAttribute(HTML.Attribute.HREF) instanceof String href) {
            return "<a href=\"" + attribute(href) + "\">" + html + "</a>";
        }
        return html;
    }

    /** {@code text} as an attribute's value: escaped, quotes included, so it cannot end the attribute and add another. */
    static String attribute(String text) {
        return MarkdownHtml.escape(text).replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * The HTML of a picture: the image, no wider than {@code maxWidth} points when that is positive; while it is read,
     * a muted note; when it cannot be shown, its alt text and why.
     */
    static String pictureHtml(LocalImages.Picture p, String alt, Path file, int maxWidth) {
        return switch (p) {
            case LocalImages.Picture.Ready r -> img(r.file(), r.width(), r.height(), maxWidth, alt);
            case LocalImages.Picture.Pending ignored -> "<font color=\"" + Theme.hex(Theme.MUTED) + "\"><i>"
                    + MarkdownHtml.escape("loading " + file.getFileName() + "…") + "</i></font>";
            case LocalImages.Picture.Unavailable u -> "<i>" + MarkdownHtml.escape(alt.isEmpty() ? "image" : alt) + "</i> <font color=\""
                    + Theme.hex(Theme.MUTED) + "\">(" + MarkdownHtml.escape(u.why()) + ")</font>";
        };
    }

    private String diagram(String source) {
        MermaidDiagrams.Diagram d = MermaidDiagrams.current().diagram(source, arrived);
        sizedFor = getWidth();
        String html = diagramHtml(source, d, pictureWidth());
        if (!(d instanceof MermaidDiagrams.Diagram.Pending)) {
            return html;
        }
        String id = waiting(() -> {
            MermaidDiagrams.Diagram now = MermaidDiagrams.current().diagram(source, arrived);
            return now instanceof MermaidDiagrams.Diagram.Pending ? null : diagramHtml(source, now, pictureWidth());
        });
        return "<div id=\"" + id + "\">" + html + "</div>";
    }

    /** The same text again, at a new width or in new colours, with the view where the reader left it. */
    private void redraw(String shown) {
        if (shown == null || !shown.equals(markdown)) {
            return;
        }
        keepingView(() -> {
            int caret = getCaretPosition();
            setMarkdown(shown);
            setCaretPosition(Math.min(caret, getDocument().getLength()));
        });
    }

    /** Runs {@code change} to the page, then puts the view back where the reader left it. */
    private void keepingView(Runnable change) {
        Point at = getParent() instanceof JViewport vp ? vp.getViewPosition() : null;
        // taken to a heading and not scrolled since: the heading, wherever the new pictures put it
        String heading = at != null && anchored != null && at.y == anchoredY ? anchored : null;
        change.run();
        if (heading != null) {
            SwingUtilities.invokeLater(() -> scrollToHeading(heading));
        } else if (at != null) {
            SwingUtilities.invokeLater(() -> ((JViewport) getParent()).setViewPosition(at));
        }
    }

    /**
     * The HTML of a Mermaid block: its picture, no wider than {@code maxWidth} points when that is positive; else its
     * source as code under a muted note saying why there is no picture yet.
     */
    static String diagramHtml(String source, MermaidDiagrams.Diagram d, int maxWidth) {
        return switch (d) {
            case MermaidDiagrams.Diagram.Ready r -> "<p>" + img(r.png(), r.width(), r.height(), maxWidth, "diagram") + "</p>";
            case MermaidDiagrams.Diagram.Pending p -> note("drawing the diagram…") + code(source);
            case MermaidDiagrams.Diagram.Unavailable u -> note(u.why()) + code(source);
        };
    }

    /** An image of a file, no wider than {@code maxWidth} points when that is positive, its height scaled with it. */
    private static String img(Path file, int width, int height, int maxWidth, String alt) {
        int w = width;
        int h = height;
        if (maxWidth > 0 && w > maxWidth) {
            h = Math.max(1, (int) ((long) h * maxWidth / w));
            w = maxWidth;
        }
        return "<img src=\"" + attribute(file.toUri().toString()) + "\" width=\"" + w + "\" height=\"" + h + "\" alt=\"" + attribute(alt) + "\">";
    }

    private static String note(String text) {
        return "<p><font color=\"" + Theme.hex(Theme.MUTED) + "\"><i>" + MarkdownHtml.escape(text) + "</i></font></p>";
    }

    private static String code(String source) {
        return "<pre class=\"code\"><code>" + MarkdownHtml.escape(source) + "</code></pre>";
    }

    private static final Pattern HEADING = Pattern.compile("(<h[1-3][^>]*>)(.*?)(</h[1-3]>)", Pattern.DOTALL);

    /**
     * {@code html} with the characters of its first three levels of heading that {@code serif} cannot draw set in Java's
     * logical sans serif, as the transcript does: Swing's HTML draws a glyph its font lacks as nothing, and New York
     * lacks the arrow. Tags, entities and code inside a heading are left as they are.
     */
    static String fallBackInHeadings(String html, Font serif) {
        Matcher m = HEADING.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 64);
        while (m.find()) {
            String inner = m.group(2);
            StringBuilder h = new StringBuilder(inner.length() + 32);
            int code = 0;
            int i = 0;
            while (i < inner.length()) {
                char c = inner.charAt(i);
                if (c == '<' || c == '&') {
                    int end = inner.indexOf(c == '<' ? '>' : ';', i);
                    end = end < 0 ? inner.length() - 1 : end;
                    String piece = inner.substring(i, end + 1);
                    code += piece.startsWith("<code") ? 1 : piece.startsWith("</code") ? -1 : 0;
                    h.append(piece);
                    i = end + 1;
                    continue;
                }
                int cp = inner.codePointAt(i);
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

    public void setHtml(String bodyHtml) {
        setText("<html><body>" + bodyHtml + "</body></html>");
    }

    private boolean fitWidth;

    /** Sizes the pane to its parent's width and its content's height, for panes in a vertical list. */
    public void setFitWidth(boolean fit) {
        fitWidth = fit;
    }

    @Override
    public Dimension getPreferredSize() {
        if (fitWidth && getParent() != null && getParent().getWidth() > 0) {
            Insets pi = getParent().getInsets();
            int w = Math.max(50, getParent().getWidth() - pi.left - pi.right);
            View root = getUI().getRootView(this);
            Insets in = getInsets();
            root.setSize(w - in.left - in.right, Integer.MAX_VALUE);
            int h = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + in.top + in.bottom;
            return new Dimension(w, h);
        }
        return super.getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return fitWidth ? new Dimension(Integer.MAX_VALUE, getPreferredSize().height) : super.getMaximumSize();
    }
}
