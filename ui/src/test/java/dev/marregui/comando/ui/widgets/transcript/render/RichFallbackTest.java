package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.text.AttributedCharacterIterator;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// [Rich] draws a character its font lacks in the theme's fallback font.
/// - a lacked character is drawn in the fallback, in one run for adjacent ones, keeping its colour
/// - text the font draws whole adds no run
class RichFallbackTest {

    /// Two adjacent private-use characters the font cannot display form one [Theme#fallback] run with the text's
    /// colour; the text around them stays in the font. Skipped where the font draws every private-use character.
    @Test
    void aCharacterTheFontLacksIsDrawnInTheFallbackAndTheRestInTheFont() {
        final Font font = new Font(Font.SERIF, Font.BOLD, 1).deriveFont(20f);
        int lacked = -1;
        for (int cp = 0xE000; cp < 0xF8FF && lacked < 0; cp++) {
            if (!font.canDisplay(cp)) {
                lacked = cp;
            }
        }
        assumeTrue(lacked >= 0, "a font that draws every private-use character");
        final String text = "Result " + Character.toString(lacked) + Character.toString(lacked) + " done";
        final Rich r = new Rich(font, Color.WHITE).append(text, font, Color.YELLOW);
        final AttributedCharacterIterator it = r.attributed(0, r.length()).getIterator();
        assertThat(fontAt(it, 0)).isEqualTo(font);
        assertThat(fontAt(it, 7)).isEqualTo(Theme.fallback(font));
        assertThat(fontAt(it, 8)).as("one run for the two").isEqualTo(Theme.fallback(font));
        assertThat(fontAt(it, 10)).isEqualTo(font);
        assertThat(it.getAttribute(TextAttribute.FOREGROUND)).as("the colour stays").isEqualTo(Color.YELLOW);
    }

    /// Text the font displays whole is a single font run.
    @Test
    void textTheFontDrawsWholeAddsNoRun() {
        final Font font = new Font(Font.SERIF, Font.PLAIN, 1).deriveFont(14f);
        final Rich r = new Rich(font, Color.WHITE).append("plain words", font, Color.WHITE);
        final AttributedCharacterIterator it = r.attributed(0, r.length()).getIterator();
        assertThat(it.getRunLimit(TextAttribute.FONT)).isEqualTo(r.length());
    }

    private static Object fontAt(final AttributedCharacterIterator it, final int index) {
        it.setIndex(index);
        return it.getAttribute(TextAttribute.FONT);
    }
}
