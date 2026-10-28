package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.text.html.StyleSheet;
import java.awt.Font;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// [HtmlPane#styles] gives a rendered page the transcript's dark look in the chosen tint.
/// - Headings, table heads, body, code and quotes take the tint, the palette and the ivory text.
/// - Headings use the serif on the transcript's heading scale and follow a tint change.
/// - A heading character the serif lacks is set in the sans serif, and nothing else is.
class DarkPageTest {

    @AfterEach
    void defaultTint() {
        Theme.tint(Tint.TREASURE);
    }

    /// `h2` is the tint's body and `th` its highlight, the body and quotes are the transcript text, code is the
    /// palette's code colour, `h1`–`h3` are serif sized by [Theme#HEADING_SCALE], and a tint change recolours `h2`.
    @Test
    void aPageReadsAsAnAnswerInTheTintChosen() {
        StyleSheet dark = HtmlPane.styles();
        assertThat(dark.getForeground(dark.getRule("h2"))).isEqualTo(Tint.TREASURE.body);
        assertThat(dark.getForeground(dark.getRule("th"))).isEqualTo(Tint.TREASURE.highlight);
        assertThat(dark.getForeground(dark.getRule("body"))).isEqualTo(Theme.TRANSCRIPT_TEXT);
        assertThat(dark.getFont(dark.getRule("h1")).getFamily()).isEqualTo(Theme.serif().getFamily());
        assertThat(dark.getForeground(dark.getRule("code"))).as("one code rule: the palette's code colour, not Claude's orange").isEqualTo(Theme.content().code);
        assertThat(dark.getForeground(dark.getRule("blockquote"))).as("one quote rule: ivory").isEqualTo(Theme.TRANSCRIPT_TEXT);
        int body = dark.getFont(dark.getRule("body")).getSize();
        int h1 = dark.getFont(dark.getRule("h1")).getSize();
        int h2 = dark.getFont(dark.getRule("h2")).getSize();
        int h3 = dark.getFont(dark.getRule("h3")).getSize();
        assertThat(List.of(h1, h2, h3)).as("the transcript's scale over the text's size " + body).containsExactly(
                Math.round(body * Theme.HEADING_SCALE[0]), Math.round(body * Theme.HEADING_SCALE[1]), Math.round(body * Theme.HEADING_SCALE[2]));
        Theme.tint(Tint.TROPICAL_FOREST);
        assertThat(HtmlPane.styles().getForeground(HtmlPane.styles().getRule("h2"))).isEqualTo(Tint.TROPICAL_FOREST.body);
    }

    /// [HtmlPane#fallBackInHeadings] wraps a run of private-use characters the serif cannot draw in a `Dialog` span
    /// inside a heading, but not inside its `code` or outside headings; a heading the serif draws whole is unchanged.
    /// Skipped when the serif draws every private-use character.
    @Test
    void aHeadingsCharacterTheSerifLacksIsSetInTheSansSerifAndNothingElseIs() {
        Font serif = new Font(Font.SERIF, Font.BOLD, 1).deriveFont(16f);
        int lacked = -1;
        for (int cp = 0xE000; cp < 0xF8FF && lacked < 0; cp++) {
            if (!serif.canDisplay(cp)) {
                lacked = cp;
            }
        }
        assumeTrue(lacked >= 0, "a serif that draws every private-use character");
        String c = Character.toString(lacked);
        String html = "<h2 id=\"x\">A " + c + c + " B <code>" + c + "</code> &amp; C</h2><p>" + c + "</p>";
        assertThat(HtmlPane.fallBackInHeadings(html, serif)).isEqualTo(
                "<h2 id=\"x\">A <span style=\"font-family: Dialog\">" + c + c + "</span> B <code>" + c + "</code> &amp; C</h2><p>" + c + "</p>");
        assertThat(HtmlPane.fallBackInHeadings("<h2>All here</h2>", serif)).isEqualTo("<h2>All here</h2>");
    }
}
