package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.JEditorPane;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.View;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Insets;
import java.net.URI;
import java.util.Locale;

/** A read-only HTML pane styled from the theme, for rendered Markdown. Links open in the browser. */
public class HtmlPane extends JEditorPane {

    /** Receives links that are not web links, such as {@code comando-file:} links to source lines. */
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
            if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("HTTP")) {
                open(href);
            } else {
                links.open(href);
            }
        });
    }

    public void setMarkdown(String markdown) {
        setHtml(MarkdownHtml.render(markdown));
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

    static void open(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            System.getLogger(HtmlPane.class.getName()).log(System.Logger.Level.WARNING, "cannot open " + url + ": " + e.getMessage());
        }
    }
}
