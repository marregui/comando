package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextLinksTest {

    @Test
    void findsEveryKindOfLinkInOrder() {
        String text = "See [[feedback-verification-discipline]], https://example.org/a and core/Reducer.java:42.";
        assertThat(TextLinks.refs(text, false)).extracting(TextLinks.Ref::href).containsExactly(
                NoteLinks.SCHEME + "feedback-verification-discipline", "https://example.org/a", SourceLinks.SCHEME + "core/Reducer.java#42");
        TextLinks.Ref note = TextLinks.refs(text, false).getFirst();
        assertThat(text.substring(note.start(), note.end())).isEqualTo("[[feedback-verification-discipline]]");
        assertThat(note.label()).isEqualTo("feedback-verification-discipline");
    }

    @Test
    void aNoteLinkNamesItsHeadingAndItsLabel() {
        TextLinks.Ref labelled = TextLinks.refs("[[notes#2-install|the install]]", false).getFirst();
        assertThat(labelled.href()).isEqualTo(NoteLinks.SCHEME + "notes#2-install");
        assertThat(labelled.label()).isEqualTo("the install");
        assertThat(NoteLinks.name(labelled.href())).isEqualTo("notes");
        assertThat(NoteLinks.heading(labelled.href())).isEqualTo("2-install");
        assertThat(TextLinks.refs("[[notes#setup]]", false).getFirst().label()).isEqualTo("notes › setup");
        assertThat(TextLinks.refs("[[ ]] and [[]]", false)).as("no name, no link").isEmpty();
    }

    @Test
    void aWebAddressEndsBeforeThePunctuationAfterIt() {
        assertThat(TextLinks.refs("(see https://x.y/z).", false)).extracting(TextLinks.Ref::href).containsExactly("https://x.y/z");
        assertThat(TextLinks.refs("https://en.wikipedia.org/wiki/Foo_(bar), next", false)).extracting(TextLinks.Ref::href)
                .as("a bracket it opened is its own").containsExactly("https://en.wikipedia.org/wiki/Foo_(bar)");
        assertThat(TextLinks.refs("at http://h:8080/x", false)).extracting(TextLinks.Ref::href)
                .as("a port is not a line").containsExactly("http://h:8080/x");
    }

    @Test
    void markdownLinksOnlyInTextNotParsedAsMarkdown() {
        String text = "read [the guide](docs/guide.md#setup) or [site](https://example.org), not ![pic](a.png)";
        assertThat(TextLinks.refs(text, true)).extracting(TextLinks.Ref::href)
                .containsExactly("docs/guide.md#setup", "https://example.org");
        assertThat(TextLinks.refs(text, false)).extracting(TextLinks.Ref::href).as("the address and the path in it are still ones")
                .containsExactly(SourceLinks.SCHEME + "docs/guide.md", "https://example.org");
    }

    @Test
    void theLinkAtAnIndex() {
        String text = "x [[a]] y";
        assertThat(TextLinks.at(text, 3, false).href()).isEqualTo(NoteLinks.SCHEME + "a");
        assertThat(TextLinks.at(text, 0, false)).isNull();
        assertThat(TextLinks.at(text, 7, false)).as("the end is outside").isNull();
    }
}
