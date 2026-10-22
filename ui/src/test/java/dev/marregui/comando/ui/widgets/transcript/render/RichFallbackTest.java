package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.text.AttributedCharacterIterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RichFallbackTest {

    @Test
    void aCharacterTheFontLacksIsDrawnInTheFallbackAndTheRestInTheFont() {
        Font font = new Font(Font.SERIF, Font.BOLD, 1).deriveFont(20f);
        int lacked = -1;
        for (int cp = 0xE000; cp < 0xF8FF && lacked < 0; cp++) {
            if (!font.canDisplay(cp)) {
                lacked = cp;
            }
        }
        assumeTrue(lacked >= 0, "a font that draws every private-use character");
        String text = "Result " + Character.toString(lacked) + Character.toString(lacked) + " done";
        Rich r = new Rich(font, Color.WHITE).append(text, font, Color.YELLOW);
        AttributedCharacterIterator it = r.attributed(0, r.length()).getIterator();
        assertThat(fontAt(it, 0)).isEqualTo(font);
        assertThat(fontAt(it, 7)).isEqualTo(Theme.fallback(font));
        assertThat(fontAt(it, 8)).as("one run for the two").isEqualTo(Theme.fallback(font));
        assertThat(fontAt(it, 10)).isEqualTo(font);
        assertThat(it.getAttribute(TextAttribute.FOREGROUND)).as("the colour stays").isEqualTo(Color.YELLOW);
    }

    @Test
    void textTheFontDrawsWholeAddsNoRun() {
        Font font = new Font(Font.SERIF, Font.PLAIN, 1).deriveFont(14f);
        Rich r = new Rich(font, Color.WHITE).append("plain words", font, Color.WHITE);
        AttributedCharacterIterator it = r.attributed(0, r.length()).getIterator();
        assertThat(it.getRunLimit(TextAttribute.FONT)).isEqualTo(r.length());
    }

    private static Object fontAt(AttributedCharacterIterator it, int index) {
        it.setIndex(index);
        return it.getAttribute(TextAttribute.FONT);
    }
}
