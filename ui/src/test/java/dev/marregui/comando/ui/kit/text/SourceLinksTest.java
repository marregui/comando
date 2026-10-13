package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceLinksTest {

    @Test
    void linksPathsWithLinesInTextAndCodeSpans() {
        String html = "<p>See <code>db/src/main/java/Lane.java:118</code> and core/Reducer.java:42.</p>";
        String linked = SourceLinks.linkHtml(html);
        assertThat(linked).contains("db/src/main/java/Lane.java:118</a>").contains("core/Reducer.java:42</a>");
        assertThat(linked).contains("href=\"" + SourceLinks.SCHEME);
    }

    @Test
    void leavesPreBlocksLinksAndTimesAlone() {
        String html = "<pre><code>at Lane.java:118</code></pre><p><a href=\"x\">a.java:1</a> at 10:30 or http://h:8080/x</p>";
        assertThat(SourceLinks.linkHtml(html)).isEqualTo(html);
    }
}
