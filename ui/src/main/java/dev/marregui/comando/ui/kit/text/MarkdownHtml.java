package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Markdown to HTML for Swing's HTML 3.2 renderer. Raw HTML in the source is escaped, not rendered. An image
 * becomes its alt text: Swing would fetch the image's URL while laying out, and the Markdown may come from
 * Claude, so an image link in a plan or a view would be a network request nobody approved. A code block that names
 * its language takes the editors' syntax colours.
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
            .nodeRendererFactory(c -> new ColouredCode(c, null))
            .build();

    private MarkdownHtml() {
    }

    public static String render(String markdown) {
        return render(markdown, null);
    }

    /**
     * Markdown to HTML; a {@code mermaid} code block becomes what {@code mermaid} makes of its text, the picture or
     * the source with a note. Without it, a Mermaid block shows as code.
     */
    public static String render(String markdown, Function<String, String> mermaid) {
        HtmlRenderer renderer = mermaid == null ? RENDERER : HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .softbreak("<br>")
                .nodeRendererFactory(ImageAsText::new)
                .nodeRendererFactory(c -> new ColouredCode(c, mermaid))
                .build();
        String html = renderer.render(PARSER.parse(markdown));
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

    /**
     * A fenced code block in the editors' colours for the language its fence names; plain when it names none. A
     * Mermaid block becomes what the diagram function makes of it, when there is one.
     */
    private static final class ColouredCode implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final Function<String, String> mermaid;

        ColouredCode(HtmlNodeRendererContext context, Function<String, String> mermaid) {
            this.context = context;
            this.mermaid = mermaid;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            String code = block.getLiteral() == null ? "" : block.getLiteral();
            String info = block.getInfo() == null ? "" : block.getInfo().strip();
            String language = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            HtmlWriter w = context.getWriter();
            if (mermaid != null && language.equalsIgnoreCase("mermaid")) {
                w.line();
                w.raw(mermaid.apply(code));
                w.line();
                return;
            }
            w.line();
            w.raw("<pre class=\"code\"><code>");
            w.raw(coloured(code, language));
            w.raw("</code></pre>");
            w.line();
        }
    }

    /** {@code code} as HTML, each token in its colour for {@code language}; escaped and uncoloured when unknown. */
    static String coloured(String code, String language) {
        List<Highlighter.Run> runs = language.isEmpty() ? List.of() : Highlighter.runs(code, language);
        StringBuilder out = new StringBuilder(code.length() + runs.size() * 24);
        int at = 0;
        for (Highlighter.Run r : runs) {
            if (r.start() < at) {
                continue;
            }
            out.append(escape(code.substring(at, r.start())));
            out.append("<font color=\"").append(Theme.hex(r.color())).append("\">")
                    .append(escape(code.substring(r.start(), Math.min(r.end(), code.length())))).append("</font>");
            at = Math.min(r.end(), code.length());
        }
        out.append(escape(code.substring(at)));
        return out.toString();
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
