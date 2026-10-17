package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.ui.kit.text.LocalImages;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSheetTest {

    @Test
    void theSheetRendersItsTablesAndShowsItsFencesAsTyped() {
        String html = MarkdownHtml.render(MarkdownSheet.markdown());
        assertThat(html).contains("<h1 id=\"markdown\">Markdown</h1>", "<h2 id=\"prompts-to-claude\">Prompts to Claude</h2>", "<table>")
                .as("no table left as text").doesNotContain("|---|");
        assertThat(html).as("the fence example shows its backticks").contains("```sql\nSELECT * FROM trades LIMIT 10;\n```");
        assertThat(html).as("the table example shows its pipes").contains("|:-----|------:|");
    }

    /** What the sheet says comando draws, and what it does not, as the renderer does it. */
    @Test
    void whatTheSheetSaysIsWhatThePreviewDoes() {
        assertThat(MarkdownHtml.render("line one\nline two")).as("a line break is a new line").contains("line one<br>line two");
        assertThat(MarkdownHtml.render("- a\n  - b")).as("two spaces nest").contains("<li>a\n<ul>\n<li>b</li>");
        assertThat(MarkdownHtml.render("1) one")).contains("<ol>");
        assertThat(MarkdownHtml.render("<https://example.org>")).contains("href=\"https://example.org\"");
        assertThat(MarkdownHtml.render("see https://example.org")).doesNotContain("<a ");
        assertThat(LocalImages.resolve(Path.of("/kb/notes"), "images/chart.png")).as("a picture from the document's folder")
                .contains(Path.of("/kb/notes/images/chart.png"));
        assertThat(LocalImages.resolve(Path.of("/kb/notes"), "../img/flow.svg")).contains(Path.of("/kb/img/flow.svg"));
        assertThat(LocalImages.resolve(Path.of("/kb/notes"), "https://example.org/chart.png")).as("nothing from the web").isEmpty();
        assertThat(MarkdownHtml.render("![what it shows](https://example.org/chart.png)")).contains("<i>what it shows</i>").doesNotContain("<img");
        assertThat(MarkdownHtml.render("## 3. Decisions\n\n## 3. Decisions")).as("a heading's id, and a repeated one's")
                .contains("id=\"3-decisions\"", "id=\"3-decisions-1\"");
        assertThat(MarkdownHtml.render("~~strike~~")).contains("~~strike~~");
        assertThat(MarkdownHtml.render("- [ ] task")).contains("[ ] task");
        assertThat(MarkdownHtml.render("<b>x</b>")).contains("&lt;b&gt;x&lt;/b&gt;");
        assertThat(MarkdownHtml.render("| a | b |\n|:--|--:|\n| 1 | 2 |")).contains("<th align=\"left\">a</th>", "<td align=\"right\">2</td>");
        assertThat(MarkdownHtml.render("\\*x\\*")).contains("*x*").doesNotContain("<em>");
    }

    @Test
    void whatTheSheetSaysAboutPromptsIsWhatTheEditorDoes() {
        assertThat(Classifier.classify("SELECT * FROM trades LIMIT 10;").target()).isEqualTo(Target.SQL);
        assertThat(Classifier.classify("select the rows that matter to me").target()).as("English words: a prompt").isEqualTo(Target.CLAUDE);
        assertThat(Classifier.classify("-- @claude\nSELECT * FROM trades;").target()).isEqualTo(Target.CLAUDE);
    }
}
