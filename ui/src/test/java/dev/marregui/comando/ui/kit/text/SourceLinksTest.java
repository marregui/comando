package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [SourceLinks] finds file paths, with or without a line, in plain text and encodes them as links.
/// - A path with `:line` becomes a link to that line.
/// - A path without a line is a link only when it names a folder; a bare name is not.
/// - Trailing punctuation, times, ports and paths inside web addresses are left alone.
class SourceLinksTest {

    /// Two `path:line` references are found in order with their spans; [SourceLinks#path] and [SourceLinks#line] decode
    /// the href, and the sentence's period is not part of the path.
    @Test
    void findsPathsWithLinesInText() {
        String text = "See db/src/main/java/Lane.java:118 and core/Reducer.java:42.";
        assertThat(SourceLinks.refs(text)).extracting(SourceLinks.Ref::href)
                .containsExactly(SourceLinks.SCHEME + "db/src/main/java/Lane.java#118", SourceLinks.SCHEME + "core/Reducer.java#42");
        SourceLinks.Ref first = SourceLinks.refs(text).getFirst();
        assertThat(text.substring(first.start(), first.end())).isEqualTo("db/src/main/java/Lane.java:118");
        assertThat(SourceLinks.path(first.href())).isEqualTo("db/src/main/java/Lane.java");
        assertThat(SourceLinks.line(first.href())).isEqualTo(118);
    }

    /// Relative, absolute and `~` paths with a folder are found, line 0, the comma after one excluded; a bare file
    /// name, a version pair, a scoped package and a path inside a URL are not.
    @Test
    void findsFilePathsWithoutALineWhenTheyNameAFolder() {
        String text = "cat docs/adr/0108-links.md, /tmp/build.log and ~/notes/a.md; Lane.java, e.g. 1.5/2.0, @types/node.d.ts";
        assertThat(SourceLinks.refs(text)).extracting(SourceLinks.Ref::href)
                .containsExactly(SourceLinks.SCHEME + "docs/adr/0108-links.md", SourceLinks.SCHEME + "/tmp/build.log", SourceLinks.SCHEME + "~/notes/a.md");
        SourceLinks.Ref first = SourceLinks.refs(text).getFirst();
        assertThat(text.substring(first.start(), first.end())).as("the comma after it is not part of it").isEqualTo("docs/adr/0108-links.md");
        assertThat(SourceLinks.line(first.href())).as("no line: the top").isZero();
        assertThat(SourceLinks.path(first.href())).isEqualTo("docs/adr/0108-links.md");
        assertThat(SourceLinks.refs("https://example.org/a/b.md")).as("inside a web address").isEmpty();
    }

    /// A clock time and a host with a port are not read as `path:line`.
    @Test
    void leavesTimesAndAddressesAlone() {
        assertThat(SourceLinks.refs("at 10:30 or http://h:8080/x")).isEmpty();
    }
}
