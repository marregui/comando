package dev.marregui.comando.ui.widgets.transcript.doc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [MarkdownDoc] parses Markdown into the transcript's paragraphs and spans.
/// - headings, paragraphs and inline styles; nested lists with markers and depth
/// - code blocks with their fence language, tables, quotes and rules
/// - source references, web addresses and note links become links, except inside links and code
/// - unchanged paragraphs compare equal while the last one streams
/// - raw HTML stays text
class MarkdownDocTest {

    /// A heading and a paragraph with bold, italic and code spans parse into two paragraphs, a soft line break kept as `\n`.
    @Test
    void headingsParagraphsAndInlineStyles() {
        List<Para> doc = MarkdownDoc.parse("# Title\n\nSome **bold** and *it* and `code`.\nNext line.");
        assertThat(doc).hasSize(2);
        assertThat(doc.get(0)).isEqualTo(new Para.Text(Para.Kind.HEADING, 1, 0, "", List.of(Span.plain("Title"))));
        Para.Text p = (Para.Text) doc.get(1);
        assertThat(p.spans()).containsExactly(
                Span.plain("Some "), new Span("bold", Span.BOLD, ""), Span.plain(" and "), new Span("it", Span.ITALIC, ""),
                Span.plain(" and "), new Span("code", Span.CODE, ""), Span.plain(".\nNext line."));
    }

    /// Bullets carry `•` at depth 1 and `◦` at depth 2; an ordered list keeps its own numbers starting at 3.
    @Test
    void nestedListsCarryMarkersAndDepth() {
        List<Para> doc = MarkdownDoc.parse("- one\n  - inner\n- two\n\n3. three\n4. four");
        assertThat(doc).extracting(p -> ((Para.Text) p).marker()).containsExactly("•", "◦", "•", "3.", "4.");
        assertThat(doc).extracting(p -> ((Para.Text) p).depth()).containsExactly(1, 2, 1, 1, 1);
    }

    /// A fenced block keeps its language, a table keeps its header and styled cells, and a quote and a rule parse as their
    /// kinds.
    @Test
    void codeBlocksTablesQuotesAndRules() {
        List<Para> doc = MarkdownDoc.parse("```java\nint x;\n```\n\n| a | b |\n|---|---|\n| 1 | **2** |\n\n> quoted\n\n---\n");
        assertThat(doc.get(0)).isEqualTo(new Para.Code("java", "int x;", 0));
        Para.Table t = (Para.Table) doc.get(1);
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(1).get(1)).containsExactly(new Span("2", Span.BOLD, ""));
        assertThat(doc.get(2)).isEqualTo(new Para.Text(Para.Kind.QUOTE, 0, 0, "", List.of(Span.plain("quoted"))));
        assertThat(doc.get(3)).isEqualTo(new Para.Rule(0));
    }

    /// A `file:line` in a code span becomes a `comando-file:` link; inside a Markdown link the link's own target wins.
    @Test
    void sourceReferencesBecomeLinksOutsideLinks() {
        Para.Text p = (Para.Text) MarkdownDoc.parse("See `db/Lane.java:118` and [x.java:3](https://h).").getFirst();
        assertThat(p.spans()).contains(new Span("db/Lane.java:118", Span.CODE, "comando-file:db/Lane.java#118"));
        assertThat(p.spans()).contains(new Span("x.java:3", 0, "https://h"));
    }

    /// A `[[note|label]]` shows its label linked as `comando-note:`, and a bare URL becomes a link without the trailing
    /// period.
    @Test
    void webAddressesAndNoteLinksBecomeLinksShownAsTheirLabels() {
        Para.Text p = (Para.Text) MarkdownDoc.parse("Read [[project-current-lane|the lane]] and https://example.org/x.").getFirst();
        assertThat(p.spans()).containsExactly(Span.plain("Read "), new Span("the lane", 0, "comando-note:project-current-lane"),
                Span.plain(" and "), new Span("https://example.org/x", 0, "https://example.org/x"), Span.plain("."));
    }

    /// A URL or note link inside a code span stays unlinked code; only a `file:line` reference in code is linked.
    @Test
    void aCodeSpanIsAsWrittenButForItsSourceReferences() {
        Para.Text p = (Para.Text) MarkdownDoc.parse("`https://example.org/x` and `[[notes]]` but `db/Lane.java:118`").getFirst();
        assertThat(p.spans()).containsExactly(new Span("https://example.org/x", Span.CODE, ""), Span.plain(" and "),
                new Span("[[notes]]", Span.CODE, ""), Span.plain(" but "), new Span("db/Lane.java:118", Span.CODE, "comando-file:db/Lane.java#118"));
    }

    /// Re-parsing with more text in the last paragraph leaves the earlier paragraph equal and the last one different.
    @Test
    void unchangedParagraphsCompareEqualWhileTheLastOneStreams() {
        List<Para> before = MarkdownDoc.parse("First paragraph.\n\nSecond, stre");
        List<Para> after = MarkdownDoc.parse("First paragraph.\n\nSecond, streaming on");
        assertThat(after.getFirst()).isEqualTo(before.getFirst());
        assertThat(after.get(1)).isNotEqualTo(before.get(1));
    }

    /// Inline HTML tags remain literal text.
    @Test
    void rawHtmlStaysText() {
        Para.Text p = (Para.Text) MarkdownDoc.parse("a <b>tag</b> here").getFirst();
        assertThat(p.plain()).isEqualTo("a <b>tag</b> here");
    }

    /// The fence language is the first word of the info string after any spaces, ignoring what follows a tab or spaces; no
    /// info gives an empty language.
    @Test
    void aFenceLanguageIsTheFirstWordOfItsInfo() {
        assertThat(MarkdownDoc.parse("```python\ttitle=x\nprint(1)\n```").getFirst()).isEqualTo(new Para.Code("python", "print(1)", 0));
        assertThat(MarkdownDoc.parse("```  sql   linenos\nselect 1\n```").getFirst()).isEqualTo(new Para.Code("sql", "select 1", 0));
        assertThat(MarkdownDoc.parse("```\nplain\n```").getFirst()).isEqualTo(new Para.Code("", "plain", 0));
    }
}
