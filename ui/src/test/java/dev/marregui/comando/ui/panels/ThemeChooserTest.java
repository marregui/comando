package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.theme.NamedTheme;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// [ThemeChooser] shows one mode's themes in slots, a dark theme and its light twin in the same one, in a popover
/// under the swatch (record 128).
/// - The six dark themes and their six light twins share slots, in the dark themes' order.
/// - A theme no twin pairs follows the pairs, in its mode.
/// - The toggle chooses the twin of the theme chosen, or the mode's first theme.
/// - The popover lies under the anchor, right-aligned with it, kept on the anchor's screen, above it when there is no
///   room below; on no screen it is left where it falls.
class ThemeChooserTest {

    /// Slot i of the light themes is the twin of slot i of the dark ones.
    @Test
    void aDarkThemeAndItsTwinShareASlot() {
        List<NamedTheme> darkSlots = ThemeChooser.slots(NamedTheme.builtIn(), false);
        List<NamedTheme> lightSlots = ThemeChooser.slots(NamedTheme.builtIn(), true);
        assertThat(darkSlots).extracting(t -> t.id).containsExactly("graphite_gold", "espresso_champagne", "aubergine_gold",
                "midnight_blue", "evergreen_forest", "slate_silver");
        assertThat(lightSlots).extracting(t -> t.id).containsExactly("paper_ochre", "cream_bronze", "lilac_gold", "ice_navy",
                "mint_forest", "fog_slate");
        for (int i = 0; i < darkSlots.size(); i++) {
            assertThat(lightSlots.get(i).twin).as("slot " + i).contains(darkSlots.get(i).id);
        }
    }

    /// The pairs come first in both modes; a dark theme without its twin, and a light one whose twin is missing, follow.
    @Test
    void aThemeWithoutATwinFollowsThePairs() {
        List<NamedTheme> some = List.of(NamedTheme.MIDNIGHT_BLUE, NamedTheme.GRAPHITE_GOLD, NamedTheme.FOG_SLATE, NamedTheme.PAPER_OCHRE);
        assertThat(ThemeChooser.slots(some, false)).containsExactly(NamedTheme.GRAPHITE_GOLD, NamedTheme.MIDNIGHT_BLUE);
        assertThat(ThemeChooser.slots(some, true)).containsExactly(NamedTheme.PAPER_OCHRE, NamedTheme.FOG_SLATE);
    }

    /// Under the anchor and right-aligned; pushed inside the screen at its left edge; above the anchor at the bottom of
    /// the screen; where it falls when the anchor is on no screen.
    @Test
    void thePopoverLiesUnderTheAnchorRightAligned() {
        Rectangle screen = new Rectangle(0, 25, 1500, 900);
        Dimension size = new Dimension(640, 500);
        assertThat(ThemeChooser.place(new Rectangle(1400, 40, 20, 20), size, Optional.of(screen))).isEqualTo(new Point(780, 64));
        assertThat(ThemeChooser.place(new Rectangle(100, 40, 20, 20), size, Optional.of(screen))).as("kept on the screen")
                .isEqualTo(new Point(0, 64));
        assertThat(ThemeChooser.place(new Rectangle(1400, 800, 20, 20), size, Optional.of(screen))).as("above, no room below")
                .isEqualTo(new Point(780, 296));
        assertThat(ThemeChooser.place(new Rectangle(-4000, 40, 20, 20), size, Optional.empty())).as("on no screen")
                .isEqualTo(new Point(-4620, 64));
    }

    /// The toggle's theme is the twin of the theme chosen, both ways; a theme in no slot of its mode, or a slot the
    /// other mode leaves empty, takes the other mode's first theme; a mode with no theme gives none.
    @Test
    void theToggleChoosesTheTwinOfTheThemeChosen() {
        List<NamedTheme> all = NamedTheme.builtIn();
        assertThat(ThemeChooser.flipped(all, Optional.of(NamedTheme.MIDNIGHT_BLUE), true)).contains(NamedTheme.ICE_NAVY);
        assertThat(ThemeChooser.flipped(all, Optional.of(NamedTheme.FOG_SLATE), false)).contains(NamedTheme.SLATE_SILVER);
        assertThat(ThemeChooser.flipped(all, Optional.empty(), true)).as("no theme chosen").contains(NamedTheme.PAPER_OCHRE);
        List<NamedTheme> some = List.of(NamedTheme.GRAPHITE_GOLD, NamedTheme.MIDNIGHT_BLUE, NamedTheme.PAPER_OCHRE);
        assertThat(ThemeChooser.flipped(some, Optional.of(NamedTheme.MIDNIGHT_BLUE), true)).as("a slot left empty")
                .contains(NamedTheme.PAPER_OCHRE);
        assertThat(ThemeChooser.flipped(List.of(NamedTheme.GRAPHITE_GOLD), Optional.of(NamedTheme.GRAPHITE_GOLD), true)).isEmpty();
    }
}
