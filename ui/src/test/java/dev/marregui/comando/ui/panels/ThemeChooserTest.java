package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.theme.NamedTheme;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [ThemeChooser] lays the themes out in rows of a dark theme and its light twin (record 128).
/// - The twelve built-in themes make six rows, each dark theme beside its twin, in the dark themes' order.
/// - A light theme no dark one claims, and a dark one with no twin, take a row with an empty side.
class ThemeChooserTest {

    /// Each built-in dark theme is on a row with the light theme whose twin it is.
    @Test
    void theBuiltInThemesMakeSixRowsOfTwins() {
        List<NamedTheme[]> rows = ThemeChooser.rows(NamedTheme.builtIn());
        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(r -> r[0].id + "|" + r[1].id).containsExactly("graphite_gold|paper_ochre",
                "espresso_champagne|cream_bronze", "aubergine_gold|lilac_gold", "midnight_blue|ice_navy",
                "evergreen_forest|mint_forest", "slate_silver|fog_slate");
    }

    /// Without its twin the dark theme's light side is empty, and a light theme whose twin is missing or taken goes
    /// below, its dark side empty, in the order given.
    @Test
    void aThemeWithoutATwinTakesARowOfItsOwn() {
        List<NamedTheme> some = List.of(NamedTheme.GRAPHITE_GOLD, NamedTheme.MIDNIGHT_BLUE, NamedTheme.PAPER_OCHRE, NamedTheme.FOG_SLATE);
        List<String> rows = ThemeChooser.rows(some).stream().map(r -> Arrays.stream(r).map(t -> t == null ? "-" : t.id).toList().toString()).toList();
        assertThat(rows).containsExactly("[graphite_gold, paper_ochre]", "[midnight_blue, -]", "[-, fog_slate]");
    }
}
