package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [TextLinks] finds note, web, source and Markdown links in text, in order.
/// - Every kind of link is found in text order with its span and label.
/// - A note link carries its heading and label; an empty note is no link.
/// - A web address ends before trailing punctuation but keeps a bracket it opened, and its port.
/// - Markdown links are read only when the text is not parsed as Markdown.
/// - [TextLinks#at] gives the link covering an index, end exclusive.
class TextLinksTest {

    /// A note, a URL and a `path:line` in one sentence come back in order; the note's span is its brackets and its
    /// label its name.
    @Test
    void findsEveryKindOfLinkInOrder() {
        final String text = "See [[feedback-verification-discipline]], https://example.org/a and core/Reducer.java:42.";
        assertThat(TextLinks.refs(text, false)).extracting(TextLinks.Ref::href).containsExactly(
                NoteLinks.SCHEME + "feedback-verification-discipline", "https://example.org/a", SourceLinks.SCHEME + "core/Reducer.java#42");
        final TextLinks.Ref note = TextLinks.refs(text, false).getFirst();
        assertThat(text.substring(note.start(), note.end())).isEqualTo("[[feedback-verification-discipline]]");
        assertThat(note.label()).isEqualTo("feedback-verification-discipline");
    }

    /// `[[name#heading|label]]` keeps the heading in the href and uses the label; without a label it reads
    /// `name › heading`; blank and empty brackets are no link.
    @Test
    void aNoteLinkNamesItsHeadingAndItsLabel() {
        final TextLinks.Ref labelled = TextLinks.refs("[[notes#2-install|the install]]", false).getFirst();
        assertThat(labelled.href()).isEqualTo(NoteLinks.SCHEME + "notes#2-install");
        assertThat(labelled.label()).isEqualTo("the install");
        assertThat(NoteLinks.name(labelled.href())).isEqualTo("notes");
        assertThat(NoteLinks.heading(labelled.href())).isEqualTo("2-install");
        assertThat(TextLinks.refs("[[notes#setup]]", false).getFirst().label()).isEqualTo("notes › setup");
        assertThat(TextLinks.refs("[[ ]] and [[]]", false)).as("no name, no link").isEmpty();
    }

    /// A closing bracket and period after a URL are dropped, a bracket pair inside it is kept, and a port stays part of
    /// it.
    @Test
    void aWebAddressEndsBeforeThePunctuationAfterIt() {
        assertThat(TextLinks.refs("(see https://x.y/z).", false)).extracting(TextLinks.Ref::href).containsExactly("https://x.y/z");
        assertThat(TextLinks.refs("https://en.wikipedia.org/wiki/Foo_(bar), next", false)).extracting(TextLinks.Ref::href)
                .as("a bracket it opened is its own").containsExactly("https://en.wikipedia.org/wiki/Foo_(bar)");
        assertThat(TextLinks.refs("at http://h:8080/x", false)).extracting(TextLinks.Ref::href)
                .as("a port is not a line").containsExactly("http://h:8080/x");
    }

    /// With Markdown links on, `[text](target)` yields its target and an image is skipped; off, the path and URL inside
    /// are found as source and web links.
    @Test
    void markdownLinksOnlyInTextNotParsedAsMarkdown() {
        final String text = "read [the guide](docs/guide.md#setup) or [site](https://example.org), not ![pic](a.png)";
        assertThat(TextLinks.refs(text, true)).extracting(TextLinks.Ref::href)
                .containsExactly("docs/guide.md#setup", "https://example.org");
        assertThat(TextLinks.refs(text, false)).extracting(TextLinks.Ref::href).as("the address and the path in it are still ones")
                .containsExactly(SourceLinks.SCHEME + "docs/guide.md", "https://example.org");
    }

    /// An index inside the note finds it; an index before it or at its end finds null.
    @Test
    void theLinkAtAnIndex() {
        final String text = "x [[a]] y";
        assertThat(TextLinks.at(text, 3, false).href()).isEqualTo(NoteLinks.SCHEME + "a");
        assertThat(TextLinks.at(text, 0, false)).isNull();
        assertThat(TextLinks.at(text, 7, false)).as("the end is outside").isNull();
    }
}
