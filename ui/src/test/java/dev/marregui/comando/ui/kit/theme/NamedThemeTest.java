package dev.marregui.comando.ui.kit.theme;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// [NamedTheme] pairs a ground with a tint into the six themes the window offers.
/// - Six themes, each on its own ground with its own command, the default first.
/// - The theme chosen is the one the ground and tint make, or none.
/// - A ground and tint kept from before themes map to the closest theme.
class NamedThemeTest {

    /// There are six themes on six grounds with distinct command ids, and the first is graphite with treasure.
    @Test
    void sixThemesEachOnItsOwnGroundTheDefaultFirst() {
        assertThat(NamedTheme.values()).hasSize(6);
        assertThat(Arrays.stream(NamedTheme.values()).map(t -> t.ground).distinct()).as("one theme per ground").hasSize(6);
        assertThat(NamedTheme.values()[0]).as("the window's default")
                .matches(t -> t.ground == Ground.GRAPHITE && t.tint == Tint.TREASURE);
        assertThat(Arrays.stream(NamedTheme.values()).map(NamedTheme::commandId).distinct()).hasSize(6);
    }

    /// [NamedTheme#chosen] is the default theme at start, empty for a pair no theme makes, and the matching theme once both
    /// fit.
    @Test
    void theThemeChosenIsTheOneTheGroundAndTintMake() {
        try {
            assertThat(NamedTheme.chosen()).contains(NamedTheme.GRAPHITE_GOLD);
            Theme.ground(Ground.MIDNIGHT);
            assertThat(NamedTheme.chosen()).as("a ground and tint no theme pairs").isEmpty();
            Theme.tint(Tint.ELECTRIC_BLUE);
            assertThat(NamedTheme.chosen()).contains(NamedTheme.MIDNIGHT_BLUE);
        } finally {
            Theme.tint(Tint.TREASURE);
            Theme.ground(Ground.GRAPHITE);
        }
    }

    /// [NamedTheme#closest] returns an exact pair, else the theme of the ground chosen, else the theme of the tint alone,
    /// when the ground has no theme or none was chosen.
    @Test
    void aGroundAndTintKeptFromBeforeThemesMapToTheClosestTheme() {
        assertThat(NamedTheme.closest(Optional.of(Ground.MIDNIGHT), Tint.ELECTRIC_BLUE)).as("a pair").isEqualTo(NamedTheme.MIDNIGHT_BLUE);
        assertThat(NamedTheme.closest(Optional.of(Ground.EVERGREEN), Tint.SILVER)).as("the ground chosen wins")
                .isEqualTo(NamedTheme.EVERGREEN_FOREST);
        assertThat(NamedTheme.closest(Optional.empty(), Tint.CHAMPAGNE)).as("a tint alone").isEqualTo(NamedTheme.ESPRESSO_CHAMPAGNE);
        assertThat(NamedTheme.closest(Optional.of(Ground.ONYX), Tint.SILVER)).as("a ground no theme has, then the tint")
                .isEqualTo(NamedTheme.SLATE_SILVER);
        assertThat(NamedTheme.closest(Optional.empty(), Tint.TREASURE)).isEqualTo(NamedTheme.GRAPHITE_GOLD);
    }
}
