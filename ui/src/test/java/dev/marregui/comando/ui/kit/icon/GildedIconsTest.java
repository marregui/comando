package dev.marregui.comando.ui.kit.icon;

import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

/// [Icons#gilded] repaints the SVG icons' gold in the chosen [Tint] and their amber as attention.
/// - The SVG gold paints as the tint's body, keeping its alpha.
/// - The SVG amber paints as [Theme#ATTENTION], gold even under silver.
/// - No other colour changes.
/// - An unknown tint name is TREASURE, and each tint's ramp runs highlight, body, shade.
class GildedIconsTest {

    @AfterEach
    void defaultTint() {
        Theme.tint(Tint.TREASURE);
    }

    /// Under CHAMPAGNE, SILVER and TREASURE the SVG gold maps to the tint's body with its alpha kept, the amber maps to
    /// attention (TREASURE's body under SILVER), and a grey is returned unchanged.
    @Test
    void theSvgsGoldPaintsAsTheTintChosenTheirAmberAsAttentionAndNoOtherColourChanges() {
        Theme.tint(Tint.CHAMPAGNE);
        assertThat(Icons.gilded(new Color(Icons.SVG_GOLD))).isEqualTo(Tint.CHAMPAGNE.body);
        assertThat(Icons.gilded(new Color(0xF0, 0xBA, 0x4C, 64)).getAlpha()).as("its alpha kept").isEqualTo(64);
        assertThat(Icons.gilded(new Color(Icons.SVG_AMBER))).as("the folders' and the warning's amber").isEqualTo(Theme.ATTENTION);
        Theme.tint(Tint.SILVER);
        assertThat(Icons.gilded(new Color(Icons.SVG_GOLD))).as("a new file's dot in silver").isEqualTo(Tint.SILVER.body);
        assertThat(Icons.gilded(new Color(Icons.SVG_AMBER))).as("the warning stays gold").isEqualTo(Tint.TREASURE.body);
        assertThat(Icons.gilded(new Color(0x8C8C8C))).isEqualTo(new Color(0x8C8C8C));
        Theme.tint(Tint.TREASURE);
        assertThat(Icons.gilded(new Color(Icons.SVG_GOLD))).isEqualTo(Tint.TREASURE.body);
    }

    /// [Tint#named] falls back to TREASURE for a name no tint has, and every tint's [Tint#at] gives highlight at 0,
    /// body at 0.45 and shade at 1.
    @Test
    void aNameNoTintHasIsTreasureAndTheRampRunsFromHighlightThroughBodyToShade() {
        assertThat(Tint.named("COPPER")).isEqualTo(Tint.TREASURE);
        assertThat(Tint.named("CHAMPAGNE")).isEqualTo(Tint.CHAMPAGNE);
        for (Tint g : Tint.values()) {
            assertThat(g.at(0f)).isEqualTo(g.highlight);
            assertThat(g.at(0.45f)).isEqualTo(g.body);
            assertThat(g.at(1f)).isEqualTo(g.shade);
        }
    }
}
