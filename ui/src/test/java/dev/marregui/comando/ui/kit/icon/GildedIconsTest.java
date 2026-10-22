package dev.marregui.comando.ui.kit.icon;

import dev.marregui.comando.ui.kit.theme.Gold;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

class GildedIconsTest {

    @AfterEach
    void defaultGold() {
        Theme.gold(Gold.TREASURE);
    }

    @Test
    void theSvgsGoldPaintsAsTheGoldChosenAndNoOtherColourChanges() {
        Theme.gold(Gold.CHAMPAGNE);
        assertThat(Icons.gilded(new Color(Icons.SVG_GOLD))).isEqualTo(Gold.CHAMPAGNE.body);
        assertThat(Icons.gilded(new Color(0xF0, 0xBA, 0x4C, 64)).getAlpha()).as("its alpha kept").isEqualTo(64);
        assertThat(Icons.gilded(new Color(Icons.SVG_AMBER))).as("the folders' and the warning's amber").isEqualTo(Gold.CHAMPAGNE.body);
        assertThat(Theme.warn()).as("a state asking to be looked at takes the gold chosen").isEqualTo(Gold.CHAMPAGNE.body);
        assertThat(Icons.gilded(new Color(0x8C8C8C))).isEqualTo(new Color(0x8C8C8C));
        Theme.gold(Gold.TREASURE);
        assertThat(Icons.gilded(new Color(Icons.SVG_GOLD))).isEqualTo(Gold.TREASURE.body);
    }

    @Test
    void aNameNoGoldHasIsTreasureAndTheRampRunsFromHighlightThroughBodyToShade() {
        assertThat(Gold.named("COPPER")).isEqualTo(Gold.TREASURE);
        assertThat(Gold.named("CHAMPAGNE")).isEqualTo(Gold.CHAMPAGNE);
        for (Gold g : Gold.values()) {
            assertThat(g.at(0f)).isEqualTo(g.highlight);
            assertThat(g.at(0.45f)).isEqualTo(g.body);
            assertThat(g.at(1f)).isEqualTo(g.shade);
        }
    }
}
