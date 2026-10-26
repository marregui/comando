package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Gold;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.text.html.StyleSheet;
import java.awt.Font;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DarkPageTest {

    @AfterEach
    void defaultGold() {
        Theme.gold(Gold.TREASURE);
    }

    @Test
    void aPageReadsAsAnAnswerInTheGoldChosen() {
        StyleSheet dark = HtmlPane.styles();
        assertThat(dark.getForeground(dark.getRule("h2"))).isEqualTo(Gold.TREASURE.body);
        assertThat(dark.getForeground(dark.getRule("th"))).isEqualTo(Gold.TREASURE.highlight);
        assertThat(dark.getForeground(dark.getRule("body"))).isEqualTo(Theme.TRANSCRIPT_TEXT);
        assertThat(dark.getFont(dark.getRule("h1")).getFamily()).isEqualTo(Theme.serif().getFamily());
        Theme.gold(Gold.CHAMPAGNE);
        assertThat(HtmlPane.styles().getForeground(HtmlPane.styles().getRule("h2"))).isEqualTo(Gold.CHAMPAGNE.body);
    }

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
