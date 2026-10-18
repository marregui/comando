package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.JEditorPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.View;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A read-only HTML pane styled from the theme, for rendered Markdown. A web link opens in the browser at a click; a
 * link to a heading of the page ({@code #} and its id) scrolls to it at a double click; any other link goes to the
 * {@link LinkHandler} at a double click.
 */
public class HtmlPane extends JEditorPane {

    /** Receives the links that are not web links, double-clicked or activated from the keyboard: a file beside the document. */
    public interface LinkHandler {
        void open(String href);
    }

    private LinkHandler links = href -> {
    };

    public void setLinkHandler(LinkHandler handler) {
        links = handler;
    }

    /** The text of a light page. */
    private static final Color LIGHT_TEXT = new Color(0x1F1F1F);
    /** A light page's ground: warm paper, as the light editor's, rather than white, which tires the eye. */
    public static final Color PAPER = new Color(0xF5F0E6);

    /** Whether the pane shows a light page: dark text on warm paper, code and diagrams in light colours. */
    private boolean light;

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
        kit.setStyleSheet(styles(false));
        setEditorKit(kit);
        setEditable(false);
        setOpaque(false);
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
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
            // the pane activates a link at every click of a double click; a keyboard activation has no clicks
            int clicks = e.getInputEvent() instanceof MouseEvent m ? m.getClickCount() : 0;
            if (Browser.isWebLink(href)) {
                if (clicks <= 1) {
                    Browser.open(href);
                }
            } else if (clicks == 0 || clicks == 2) {
                String raw = e.getDescription();
                if (raw != null && raw.startsWith("#")) {
                    scrollToHeading(raw.substring(1));
                } else {
                    links.open(href);
                }
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

    /**
     * Shows a light page (dark text on warm paper, code and diagrams in light colours) or a dark one, the window's; what is
     * shown is drawn again in the new colours.
     */
    public void setLight(boolean on) {
        if (on == light) {
            return;
        }
        light = on;
        String html = markdown == null ? getText() : null;
        HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
        kit.setStyleSheet(styles(on));
        // the pane honours its display properties: its foreground is the body's colour, over the style sheet's
        setForeground(on ? LIGHT_TEXT : null);
        setOpaque(on);
        setBackground(on ? PAPER : null);
        // setText fills the document it has, which keeps the style sheet it was made with: a new one takes the new rules
        setDocument(kit.createDefaultDocument());
        if (markdown != null) {
            redraw(markdown);
        } else {
            setText(html);
        }
    }

    public boolean light() {
        return light;
    }

    /** The rules of a light page or a dark one, over Swing's defaults. */
    private static StyleSheet styles(boolean light) {
        StyleSheet css = new StyleSheet();
        css.addStyleSheet(new HTMLEditorKit().getStyleSheet());
        String fg = light ? Theme.hex(LIGHT_TEXT) : Theme.hex(UIManager.getColor("Label.foreground"));
        String card = light ? "#ece5d6" : Theme.hex(Theme.CARD);
        String border = light ? "#d6cdba" : Theme.hex(Theme.CARD_BORDER);
        String muted = light ? "#6c707e" : Theme.hex(Theme.MUTED);
        String link = light ? "#006dcc" : Theme.hex(Theme.ACCENT);
        String mono = Theme.mono().getFamily();
        int size = UIManager.getFont("Label.font").getSize();
        css.addRule("body { font-family: sans-serif; font-size: " + size + "pt; color: " + fg + "; margin: 0; }");
        css.addRule("p { margin-top: 2px; margin-bottom: 6px; }");
        css.addRule("h1 { font-size: " + (size + 6) + "pt; margin: 6px 0 4px 0; }");
        css.addRule("h2 { font-size: " + (size + 4) + "pt; margin: 6px 0 4px 0; }");
        css.addRule("h3 { font-size: " + (size + 2) + "pt; margin: 4px 0 2px 0; }");
        css.addRule("code { font-family: '" + mono + "'; color: " + Theme.hex(Theme.CLAUDE) + "; }");
        css.addRule("pre { font-family: '" + mono + "'; background-color: " + card + "; padding: 6px; margin: 4px 0 6px 0; }");
        css.addRule("pre.code code { color: " + fg + "; }");
        css.addRule("a { color: " + link + "; }");
        css.addRule("table { border-collapse: collapse; margin: 4px 0 6px 0; }");
        css.addRule("th { border: 1px solid " + border + "; padding: 2px 6px; background-color: " + card + "; }");
        css.addRule("td { border: 1px solid " + border + "; padding: 2px 6px; }");
        css.addRule("blockquote { margin-left: 8px; color: " + muted + "; }");
        css.addRule("ul, ol { margin-left: 18px; margin-top: 2px; margin-bottom: 4px; }");
        return css;
    }

    /**
     * Swing's kit with a style sheet of its own: {@link HTMLEditorKit#setStyleSheet} sets one shared by every kit, so
     * one pane turned light would turn every pane light.
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

    /** The folder the Markdown's relative image links are read from; null for Markdown that is no file's. */
    private Path folder;
    /**
     * The heading the reader was last taken to, and the view's top then: a redraw or a resize that finds the view still
     * there scrolls to the heading again, since a picture drawn above it, or a new width, moves it. Null when none.
     */
    private String anchored;
    private int anchoredY = -1;

    /** Shows {@code markdown} rendered; its Mermaid diagrams as pictures once {@link MermaidDiagrams} has drawn them. */
    public void setMarkdown(String markdown) {
        this.markdown = markdown;
        sizedFor = -1;
        setHtml(MarkdownHtml.render(markdown, this::diagram, light, this::picture));
    }

    /**
     * Shows {@code markdown}, the text of a file in {@code folder}: an image link to a picture on this machine, beside
     * the file or by its full path, shows the picture.
     */
    public void setMarkdown(String markdown, Path folder) {
        this.folder = folder;
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
        Optional<Path> file = LocalImages.resolve(folder, destination);
        if (file.isEmpty()) {
            return null;
        }
        String shown = markdown;
        LocalImages.Picture p = LocalImages.current().picture(file.get(), () -> Edt.later(() -> redraw(shown)));
        sizedFor = getWidth();
        Insets in = getInsets();
        return pictureHtml(p, alt, file.get(), getWidth() - in.left - in.right - 8);
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
        String shown = markdown;
        MermaidDiagrams.Diagram d = MermaidDiagrams.current().diagram(source, light, () -> Edt.later(() -> redraw(shown)));
        sizedFor = getWidth();
        Insets in = getInsets();
        return diagramHtml(source, d, getWidth() - in.left - in.right - 8);
    }

    /** A diagram was drawn: the same text again, with the view where the reader left it. */
    private void redraw(String shown) {
        if (shown == null || !shown.equals(markdown)) {
            return;
        }
        Point at = getParent() instanceof JViewport vp ? vp.getViewPosition() : null;
        // taken to a heading and not scrolled since: the heading, wherever the new pictures put it
        String heading = at != null && anchored != null && at.y == anchoredY ? anchored : null;
        int caret = getCaretPosition();
        setMarkdown(shown);
        setCaretPosition(Math.min(caret, getDocument().getLength()));
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
