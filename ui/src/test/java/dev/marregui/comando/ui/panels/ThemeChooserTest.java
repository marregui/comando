package dev.marregui.comando.ui.panels;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.ThemeFiles;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [ThemeChooser] shows one mode's themes in slots, a dark theme and its light twin in the same one, in a popover
/// under the swatch (record 128).
/// - The six dark themes and their six light twins share slots, in the dark themes' order; the two console themes
///   follow them in the dark mode, each on a slot of its own (record 151).
/// - A theme no twin pairs follows the pairs, in its mode.
/// - A twin named on either side pairs the two themes.
/// - A theme's tooltip is its head line over its description, escaped; a card's tooltip carries it (record 147).
/// - The toggle chooses the twin of the theme chosen, or the mode's first theme.
/// - The popover lies under the anchor, right-aligned with it, kept on the anchor's screen, above it when there is no
///   room below; on no screen it is left where it falls.
class ThemeChooserTest {

    /// Slot i of the light themes is the twin of slot i of the dark ones; the two console themes take slots 6 and 7
    /// of the dark mode, which the light mode leaves empty, and the toggle from either takes the light mode's first.
    @Test
    void aDarkThemeAndItsTwinShareASlot() {
        final List<NamedTheme> darkSlots = ThemeChooser.slots(NamedTheme.builtIn(), false);
        final List<NamedTheme> lightSlots = ThemeChooser.slots(NamedTheme.builtIn(), true);
        assertThat(darkSlots).extracting(t -> t.id).containsExactly("graphite_gold", "espresso_champagne", "aubergine_gold",
                "midnight_blue", "evergreen_forest", "slate_silver", "console_amber", "console_cyan");
        assertThat(lightSlots).extracting(t -> t.id).containsExactly("paper_ochre", "cream_bronze", "lilac_gold", "ice_navy",
                "mint_forest", "fog_slate");
        assertThat(ThemeChooser.flipped(NamedTheme.builtIn(), Optional.of(NamedTheme.CONSOLE_AMBER), true)).as("no twin")
                .contains(NamedTheme.PAPER_OCHRE);
        for (int i = 0; i < lightSlots.size(); i++) {
            assertThat(lightSlots.get(i).twin).as("slot " + i).contains(darkSlots.get(i).id);
        }
    }

    /// The pairs come first in both modes; a dark theme without its twin, and a light one whose twin is missing, follow.
    @Test
    void aThemeWithoutATwinFollowsThePairs() {
        final List<NamedTheme> some = List.of(NamedTheme.MIDNIGHT_BLUE, NamedTheme.GRAPHITE_GOLD, NamedTheme.FOG_SLATE, NamedTheme.PAPER_OCHRE);
        assertThat(ThemeChooser.slots(some, false)).containsExactly(NamedTheme.GRAPHITE_GOLD, NamedTheme.MIDNIGHT_BLUE);
        assertThat(ThemeChooser.slots(some, true)).containsExactly(NamedTheme.PAPER_OCHRE, NamedTheme.FOG_SLATE);
    }

    /// Under the anchor and right-aligned; pushed inside the screen at its left edge; above the anchor at the bottom of
    /// the screen; where it falls when the anchor is on no screen.
    @Test
    void thePopoverLiesUnderTheAnchorRightAligned() {
        final Rectangle screen = new Rectangle(0, 25, 1500, 900);
        final Dimension size = new Dimension(640, 500);
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
        final List<NamedTheme> all = NamedTheme.builtIn();
        assertThat(ThemeChooser.flipped(all, Optional.of(NamedTheme.MIDNIGHT_BLUE), true)).contains(NamedTheme.ICE_NAVY);
        assertThat(ThemeChooser.flipped(all, Optional.of(NamedTheme.FOG_SLATE), false)).contains(NamedTheme.SLATE_SILVER);
        assertThat(ThemeChooser.flipped(all, Optional.empty(), true)).as("no theme chosen").contains(NamedTheme.PAPER_OCHRE);
        final List<NamedTheme> some = List.of(NamedTheme.GRAPHITE_GOLD, NamedTheme.MIDNIGHT_BLUE, NamedTheme.PAPER_OCHRE);
        assertThat(ThemeChooser.flipped(some, Optional.of(NamedTheme.MIDNIGHT_BLUE), true)).as("a slot left empty")
                .contains(NamedTheme.PAPER_OCHRE);
        assertThat(ThemeChooser.flipped(List.of(NamedTheme.GRAPHITE_GOLD), Optional.of(NamedTheme.GRAPHITE_GOLD), true)).isEmpty();
    }

    /// A user's dark theme naming a light one as its twin, which names none, pairs with it as a light theme naming its
    /// dark twin does: the two share a slot, and the toggle turns one into the other.
    @Test
    void aTwinNamedOnTheDarkSidePairsTheThemesAsOnTheLightSide(@TempDir final Path folder) throws IOException {
        Files.writeString(folder.resolve("a-ink.json"), theme("ink", "dark", "#14101A", ""));
        Files.writeString(folder.resolve("b-pine.json"), theme("pine", "dark", "#0C1511", "parchment"));
        Files.writeString(folder.resolve("c-parchment.json"), theme("parchment", "light", "#F4EFE4", ""));
        final ThemeFiles.Read read = ThemeFiles.read(folder);
        assertThat(read.problems()).isEmpty();
        final List<NamedTheme> all = new ArrayList<>(NamedTheme.builtIn());
        all.addAll(read.themes());
        final NamedTheme pine = read.themes().get(1);
        final NamedTheme parchment = read.themes().get(2);
        final List<NamedTheme> dark = ThemeChooser.slots(all, false);
        final List<NamedTheme> light = ThemeChooser.slots(all, true);
        assertThat(dark.indexOf(pine)).as("pine and its twin in one slot").isEqualTo(light.indexOf(parchment)).isEqualTo(6);
        assertThat(ThemeChooser.flipped(all, Optional.of(pine), true)).contains(parchment);
        assertThat(ThemeChooser.flipped(all, Optional.of(parchment), false)).contains(pine);
    }

    /// The tooltip is the head over the description, both escaped so a user's markup shows as text; a theme with no
    /// description keeps the head alone, as plain text; a card's tooltip, and the theme button's for the theme chosen,
    /// built on the EDT, carry the description.
    @Test
    void aThemesTooltipIsItsHeadOverItsDescription(@TempDir final Path folder) throws IOException, InterruptedException,
            InvocationTargetException {
        assertThat(ThemeChooser.tip("Fog & slate", NamedTheme.FOG_SLATE))
                .isEqualTo("<html>Fog &amp; slate<br>Slate on fog: the most neutral light theme, cool and quiet."
                        + "</html>");
        Files.writeString(folder.resolve("a.json"), theme("ink", "dark", "#14101A", "")
                .replace("\"mode\"", "\"description\": \"<img src=x> & calm\", \"mode\""));
        Files.writeString(folder.resolve("b.json"), theme("pine", "dark", "#0C1511", ""));
        final ThemeFiles.Read read = ThemeFiles.read(folder);
        assertThat(read.problems()).isEmpty();
        assertThat(ThemeChooser.tip("Ink", read.themes().get(0)))
                .isEqualTo("<html>Ink<br>&lt;img src=x&gt; &amp; calm</html>");
        assertThat(ThemeChooser.tip("<html>Pine", read.themes().get(1))).as("no description: the head alone, as text")
                .isEqualTo("\u200B<html>Pine");
        final AtomicReference<String> card = new AtomicReference<>();
        SwingUtilities.invokeAndWait(
                () -> card.set(new ThemeChooser.Card(NamedTheme.ICE_NAVY, false).getToolTipText()));
        assertThat(card.get()).isEqualTo("<html>Ice and navy; click to choose it<br>"
                + "Navy on ice: formal and orderly, crisp in good light.</html>");
        final AtomicReference<String> button = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Theme.choose(Ground.MINT, Tint.FOREST);
                button.set(new ThemeButton().getToolTipText(null));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
        }
        assertThat(button.get()).as("the button, for the theme chosen")
                .isEqualTo("<html>Theme: Mint and forest; click for another<br>Forest on mint: fresh and calm.</html>");
    }

    private static String theme(final String id, final String mode, final String ground, final String twin) {
        final boolean light = mode.equals("light");
        return """
                {"id": "%s", "label": "%s", "mode": "%s", %s"ground": {"color": "%s"},
                 "tint": {"highlight": "%s", "body": "%s", "shade": "%s"}}
                """.formatted(id, id, mode, twin.isEmpty() ? "" : "\"twin\": \"" + twin + "\", ", ground,
                light ? "#3D2900" : "#CFF5DC", light ? "#2A1C00" : "#62CC94", light ? "#5E4210" : "#339966");
    }
}
