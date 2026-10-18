package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Markdown to HTML for Swing's HTML 3.2 renderer. Raw HTML in the source is escaped, not rendered. An image is
 * what the caller's {@link Pictures} makes of it, the picture of a file on this machine; else its alt text: Swing
 * would fetch the image's URL while laying out, and the Markdown may come from Claude, so an image link in a plan or a
 * view would be a network request nobody approved. A code block that names its language takes the editors' syntax
 * colours. A heading has the id GitHub gives it, which a link to {@code #} and that id names.
 */
public final class MarkdownHtml {

    /** The HTML of an image link's picture, or null to show its alt text; {@code alt} is plain text. */
    @FunctionalInterface
    public interface Pictures {
        Pictures NONE = (destination, alt) -> null;

        String html(String destination, String alt);
    }

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak("<br>")
            .attributeProviderFactory(c -> new HeadingIds())
            .nodeRendererFactory(c -> new ImageAsText(c, Pictures.NONE))
            .nodeRendererFactory(c -> new ColouredCode(c, null, false))
            .build();

    /** What a heading's id leaves out of its text: all but letters, marks, digits, connectors such as {@code _}, spaces and hyphens. */
    private static final Pattern NOT_IN_SLUG = Pattern.compile("[^\\p{L}\\p{M}\\p{N}\\p{Pc} -]");

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
        return render(markdown, mermaid, false);
    }

    /** As {@link #render(String, Function)}, code blocks in the light or the dark editor theme's colours. */
    public static String render(String markdown, Function<String, String> mermaid, boolean light) {
        return render(markdown, mermaid, light, Pictures.NONE);
    }

    /** As {@link #render(String, Function, boolean)}, an image link shown as {@code pictures} makes it. */
    public static String render(String markdown, Function<String, String> mermaid, boolean light, Pictures pictures) {
        HtmlRenderer renderer = mermaid == null && !light && pictures == Pictures.NONE ? RENDERER : HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .softbreak("<br>")
                .attributeProviderFactory(c -> new HeadingIds())
                .nodeRendererFactory(c -> new ImageAsText(c, pictures))
                .nodeRendererFactory(c -> new ColouredCode(c, mermaid, light))
                .build();
        String html = renderer.render(PARSER.parse(markdown));
        // Swing's renderer does not know <del>, and draws <pre><code> with a wrapping paragraph margin
        return html.replace("<pre><code", "<pre class=\"code\"><code").replace("<del>", "<strike>").replace("</del>", "</strike>");
    }

    /**
     * The id GitHub gives a heading: its text in lower case, without punctuation or symbols, each space a hyphen; a
     * heading whose id is taken already gets {@code -1}, {@code -2}, and so on. One per rendering, as ids count from
     * the top of the document.
     */
    private static final class HeadingIds implements AttributeProvider {
        private final Map<String, Integer> seen = new HashMap<>();

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof Heading) {
                StringBuilder text = new StringBuilder();
                headingText(node, text);
                attributes.put("id", unique(slug(text.toString())));
            }
        }

        private String unique(String slug) {
            String id = slug;
            while (seen.containsKey(id)) {
                int n = seen.merge(slug, 1, Integer::sum);
                id = slug + "-" + n;
            }
            seen.put(id, 0);
            return id;
        }

        private static void headingText(Node node, StringBuilder out) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof Text t) {
                    out.append(t.getLiteral());
                } else if (c instanceof Code code) {
                    out.append(code.getLiteral());
                }
                headingText(c, out);
            }
        }
    }

    /** A heading's text as GitHub makes it an id: lower case, letters, digits, {@code _} and {@code -} kept, each space a hyphen. */
    static String slug(String text) {
        return NOT_IN_SLUG.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("").replace(' ', '-');
    }

    /**
     * Writes an image as the caller's picture of it; else as its alt text in italics, never naming its URL in an
     * attribute Swing loads.
     */
    private static final class ImageAsText implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final Pictures pictures;

        ImageAsText(HtmlNodeRendererContext context, Pictures pictures) {
            this.context = context;
            this.pictures = pictures;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Image.class);
        }

        @Override
        public void render(Node node) {
            StringBuilder alt = new StringBuilder();
            altText(node, alt);
            String picture = pictures.html(((Image) node).getDestination(), alt.toString());
            if (picture != null) {
                context.getWriter().raw(picture);
                return;
            }
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
        private final boolean light;

        ColouredCode(HtmlNodeRendererContext context, Function<String, String> mermaid, boolean light) {
            this.context = context;
            this.mermaid = mermaid;
            this.light = light;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            String code = block.getLiteral() == null ? "" : block.getLiteral();
            String language = Fences.language(block.getInfo());
            HtmlWriter w = context.getWriter();
            if (mermaid != null && language.equalsIgnoreCase("mermaid")) {
                w.line();
                w.raw(mermaid.apply(code));
                w.line();
                return;
            }
            w.line();
            w.raw("<pre class=\"code\"><code>");
            w.raw(coloured(code, language, light));
            w.raw("</code></pre>");
            w.line();
        }
    }

    /** {@code code} as HTML, each token in its colour for {@code language}; escaped and uncoloured when unknown. */
    static String coloured(String code, String language) {
        return coloured(code, language, false);
    }

    static String coloured(String code, String language, boolean light) {
        List<Highlighter.Run> runs = language.isEmpty() ? List.of() : Highlighter.runs(code, language, light);
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
