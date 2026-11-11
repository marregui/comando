package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [NamedTheme] pairs a ground with a tint into the fourteen built-in themes the window offers.
/// - Fourteen themes: six dark, their six light twins, then the two of the console style, dark and without a twin;
///   each on its own ground with its own command, the default first.
/// - The theme chosen is the one the ground and tint make, or none.
/// - A ground and tint kept from before themes map to the closest theme.
class NamedThemeTest {

    /// There are fourteen themes on fourteen grounds with distinct command ids, the first graphite with treasure; of
    /// the twelve plain ones six are dark and six light, each light one the twin of one dark one, in the dark ones'
    /// order, paper with ochre first; the two of the console style follow them, dark, with no twin either way.
    @Test
    void fourteenThemesSixDarkTheirSixLightTwinsThenTwoConsoleOnes() {
        assertThat(NamedTheme.values()).hasSize(14);
        assertThat(Arrays.stream(NamedTheme.values()).map(t -> t.ground).distinct()).as("one theme per ground").hasSize(14);
        final List<NamedTheme> plain = Arrays.stream(NamedTheme.values()).filter(t -> t.style == Style.PLAIN).toList();
        final List<NamedTheme> dark = plain.stream().filter(t -> !t.ground.light).toList();
        final List<NamedTheme> light = plain.stream().filter(t -> t.ground.light).toList();
        assertThat(dark).hasSize(6).allMatch(t -> t.twin.isEmpty() && !t.tint.light);
        assertThat(light).hasSize(6).allMatch(t -> t.tint.light).first().isEqualTo(NamedTheme.PAPER_OCHRE);
        assertThat(light).extracting(t -> t.twin.orElseThrow()).as("each dark theme's twin, in their order")
                .containsExactlyElementsOf(dark.stream().map(t -> t.id).toList());
        assertThat(NamedTheme.values()[0]).as("the window's default")
                .matches(t -> t.ground == Ground.GRAPHITE && t.tint == Tint.TREASURE);
        assertThat(Arrays.stream(NamedTheme.values()).map(NamedTheme::commandId).distinct()).hasSize(14);
        assertThat(Arrays.asList(NamedTheme.values()).subList(12, 14)).as("after the twelve")
                .containsExactly(NamedTheme.CONSOLE_AMBER, NamedTheme.CONSOLE_CYAN)
                .allMatch(t -> t.style == Style.CONSOLE && !t.ground.light && !t.tint.light && t.twin.isEmpty()
                        && NamedTheme.twinOf(NamedTheme.builtIn(), t).isEmpty());
        assertThat(NamedTheme.CONSOLE_AMBER.commandId()).isEqualTo("theme.console_amber");
    }

    /// [NamedTheme#chosen] is the default theme at start, empty for a pair no theme makes, and the matching theme once
    /// both fit.
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

    /// [NamedTheme#closest] returns an exact pair, else the theme of the ground chosen, else the theme of the tint
    /// alone, when the ground has no theme or none was chosen.
    @Test
    void aGroundAndTintKeptFromBeforeThemesMapToTheClosestTheme() {
        assertThat(NamedTheme.closest(Optional.of(Ground.MIDNIGHT), Tint.ELECTRIC_BLUE)).as("a pair").isEqualTo(NamedTheme.MIDNIGHT_BLUE);
        assertThat(NamedTheme.closest(Optional.of(Ground.EVERGREEN), Tint.SILVER)).as("the ground chosen wins")
                .isEqualTo(NamedTheme.EVERGREEN_FOREST);
        assertThat(NamedTheme.closest(Optional.empty(), Tint.CHAMPAGNE)).as("a tint alone").isEqualTo(NamedTheme.ESPRESSO_CHAMPAGNE);
        final Ground onyx = new Ground("ONYX", "Onyx", new Color(0x0A0A0B));
        assertThat(NamedTheme.closest(Optional.of(onyx), Tint.SILVER)).as("a ground no theme has, then the tint")
                .isEqualTo(NamedTheme.SLATE_SILVER);
        assertThat(NamedTheme.closest(Optional.empty(), Tint.TREASURE)).isEqualTo(NamedTheme.GRAPHITE_GOLD);
    }
}
