package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.JEditorPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.View;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseEvent;

/**
 * A read-only HTML pane styled from the theme, for rendered Markdown. A web link opens in the browser at a click; any
 * other link goes to the {@link LinkHandler} at a double click.
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

    public HtmlPane() {
        setContentType("text/html");
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet css = new StyleSheet();
        css.addStyleSheet(kit.getStyleSheet());
        String fg = Theme.hex(UIManager.getColor("Label.foreground"));
        String mono = Theme.mono().getFamily();
        int size = UIManager.getFont("Label.font").getSize();
        css.addRule("body { font-family: sans-serif; font-size: " + size + "pt; color: " + fg + "; margin: 0; }");
        css.addRule("p { margin-top: 2px; margin-bottom: 6px; }");
        css.addRule("h1 { font-size: " + (size + 6) + "pt; margin: 6px 0 4px 0; }");
        css.addRule("h2 { font-size: " + (size + 4) + "pt; margin: 6px 0 4px 0; }");
        css.addRule("h3 { font-size: " + (size + 2) + "pt; margin: 4px 0 2px 0; }");
        css.addRule("code { font-family: '" + mono + "'; color: " + Theme.hex(Theme.CLAUDE) + "; }");
        css.addRule("pre { font-family: '" + mono + "'; background-color: " + Theme.hex(Theme.CARD) + "; padding: 6px; margin: 4px 0 6px 0; }");
        css.addRule("pre.code code { color: " + fg + "; }");
        css.addRule("a { color: " + Theme.hex(Theme.ACCENT) + "; }");
        css.addRule("table { border-collapse: collapse; margin: 4px 0 6px 0; }");
        css.addRule("th { border: 1px solid " + Theme.hex(Theme.CARD_BORDER) + "; padding: 2px 6px; background-color: " + Theme.hex(Theme.CARD) + "; }");
        css.addRule("td { border: 1px solid " + Theme.hex(Theme.CARD_BORDER) + "; padding: 2px 6px; }");
        css.addRule("blockquote { margin-left: 8px; color: " + Theme.hex(Theme.MUTED) + "; }");
        css.addRule("ul, ol { margin-left: 18px; margin-top: 2px; margin-bottom: 4px; }");
        kit.setStyleSheet(css);
        setEditorKit(kit);
        setEditable(false);
        setOpaque(false);
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
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
                links.open(href);
            }
        });
    }

    /** The Markdown shown, so a diagram drawn later refreshes the pane only while it still shows the same text. */
    private String markdown;

    /** Shows {@code markdown} rendered; its Mermaid diagrams as pictures once {@link MermaidDiagrams} has drawn them. */
    public void setMarkdown(String markdown) {
        this.markdown = markdown;
        setHtml(MarkdownHtml.render(markdown, this::diagram));
    }

    private String diagram(String source) {
        String shown = markdown;
        MermaidDiagrams.Diagram d = MermaidDiagrams.current().diagram(source, () -> Edt.later(() -> redraw(shown)));
        Insets in = getInsets();
        return diagramHtml(source, d, getWidth() - in.left - in.right - 8);
    }

    /** A diagram was drawn: the same text again, with the view where the reader left it. */
    private void redraw(String shown) {
        if (shown == null || !shown.equals(markdown)) {
            return;
        }
        Point at = getParent() instanceof JViewport vp ? vp.getViewPosition() : null;
        int caret = getCaretPosition();
        setMarkdown(shown);
        setCaretPosition(Math.min(caret, getDocument().getLength()));
        if (at != null) {
            SwingUtilities.invokeLater(() -> ((JViewport) getParent()).setViewPosition(at));
        }
    }

    /**
     * The HTML of a Mermaid block: its picture, no wider than {@code maxWidth} points when that is positive; else its
     * source as code under a muted note saying why there is no picture yet.
     */
    static String diagramHtml(String source, MermaidDiagrams.Diagram d, int maxWidth) {
        return switch (d) {
            case MermaidDiagrams.Diagram.Ready r -> {
                int w = r.width();
                int h = r.height();
                if (maxWidth > 0 && w > maxWidth) {
                    h = Math.max(1, h * maxWidth / w);
                    w = maxWidth;
                }
                yield "<p><img src=\"" + r.png().toUri() + "\" width=\"" + w + "\" height=\"" + h + "\" alt=\"diagram\"></p>";
            }
            case MermaidDiagrams.Diagram.Pending p -> note("drawing the diagram…") + code(source);
            case MermaidDiagrams.Diagram.Unavailable u -> note(u.why()) + code(source);
        };
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
