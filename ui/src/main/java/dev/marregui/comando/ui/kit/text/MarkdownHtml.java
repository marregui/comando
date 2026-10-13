package dev.marregui.comando.ui.kit.text;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;
import java.util.Set;

/**
 * Markdown to HTML for Swing's HTML 3.2 renderer. Raw HTML in the source is escaped, not rendered. An image
 * becomes its alt text: Swing would fetch the image's URL while laying out, and the Markdown may come from
 * Claude, so an image link in a plan or a view would be a network request nobody approved.
 */
public final class MarkdownHtml {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak("<br>")
            .nodeRendererFactory(ImageAsText::new)
            .build();

    private MarkdownHtml() {
    }

    public static String render(String markdown) {
        String html = RENDERER.render(PARSER.parse(markdown));
        // Swing's renderer does not know <del>, and draws <pre><code> with a wrapping paragraph margin
        return html.replace("<pre><code", "<pre class=\"code\"><code").replace("<del>", "<strike>").replace("</del>", "</strike>");
    }

    /** Writes an image as its alt text in italics and never names its URL in an attribute Swing loads. */
    private static final class ImageAsText implements NodeRenderer {
        private final HtmlNodeRendererContext context;

        ImageAsText(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Image.class);
        }

        @Override
        public void render(Node node) {
            StringBuilder alt = new StringBuilder();
            altText(node, alt);
            context.getWriter().tag("i");
            context.getWriter().text(alt.isEmpty() ? "image" : alt.toString());
            context.getWriter().tag("/i");
        }

        private static void altText(Node node, StringBuilder out) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof Text t) {
                    out.append(t.getLiteral());
                }
                altText(c, out);
            }
        }
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
