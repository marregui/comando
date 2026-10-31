package dev.marregui.comando.ui.kit.theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// Themes are JSON files (record 126): the six built in, and the user's, read from a folder at start.
/// - The built-in files give the six dark themes their colours as before, paper and ochre its light ones, and each
///   reads above every floor.
/// - A user's file is offered after the built-in ones, with a ground and tint of its own, in the order of the names.
/// - A file that does not parse, names a bad id, mode or colour, repeats an id, or reads below a floor, dark or light,
///   is left out, with one line naming the file and why.
class ThemeFilesTest {

    @TempDir
    Path folder;

    /// The built-in files give the colours the enums had, the labels in the swatch's order, paper and ochre last and
    /// light, and every built-in theme reads above every floor of [ThemeRules].
    @Test
    void theBuiltInFilesGiveTheThemesAsBefore() {
        assertThat(NamedTheme.builtIn()).extracting(t -> t.id).containsExactly("graphite_gold", "espresso_champagne",
                "aubergine_gold", "midnight_blue", "evergreen_forest", "slate_silver", "paper_ochre", "cream_bronze", "lilac_gold",
                "ice_navy", "mint_forest", "fog_slate");
        assertThat(NamedTheme.builtIn()).filteredOn(t -> t.ground.light).hasSize(6).first().isEqualTo(NamedTheme.PAPER_OCHRE);
        assertThat(NamedTheme.PAPER_OCHRE.twin).contains("graphite_gold");
        assertThat(NamedTheme.ICE_NAVY.twin).contains("midnight_blue");
        assertThat(Theme.hex(Ground.PAPER.color)).isEqualTo("#f7f3ea");
        assertThat(Theme.hex(Tint.OCHRE.body)).isEqualTo("#5e4210");
        assertThat(NamedTheme.GRAPHITE_GOLD.label).isEqualTo("Graphite and gold");
        assertThat(Theme.hex(Ground.GRAPHITE.color)).isEqualTo("#121212");
        assertThat(Theme.hex(Ground.MIDNIGHT.color)).isEqualTo("#0d1220");
        assertThat(Ground.MIDNIGHT.label).isEqualTo("Midnight ink");
        assertThat(Theme.hex(Tint.TREASURE.highlight) + Theme.hex(Tint.TREASURE.body) + Theme.hex(Tint.TREASURE.shade))
                .isEqualTo("#ffedaa#e7c55a#9e8230");
        assertThat(Theme.hex(Tint.ELECTRIC_BLUE.body)).isEqualTo("#63b5ff");
        assertThat(NamedTheme.GRAPHITE_GOLD.commandId()).as("the commands keep their ids").isEqualTo("theme.graphite_gold");
        for (final NamedTheme t : NamedTheme.builtIn()) {
            assertThat(ThemeRules.problems(t.ground, t.tint)).as(t.id).isEmpty();
            assertThat(t.file).isEmpty();
        }
    }

    /// Two good files are read in the order of their names, after the built-in themes once offered, each with its own
    /// ground and tint, named by its id; labels left out are the theme's; a file not ending in `.json` is not read.
    @Test
    void aUsersFilesAreOfferedAfterTheBuiltInOnes() throws IOException {
        write("b.json", theme("ink_rose", "Ink and rose", "#14101A", "#FFE3EC", "#F2A7C0", "#B5627F"));
        Files.writeString(folder.resolve("a.json"), """
                {"id": "pine", "label": "Pine", "ground": {"color": "#0C1511"},
                 "tint": {"highlight": "#CFF5DC", "body": "#62CC94", "shade": "#339966"}}
                """);
        write("c.json", theme("parchment", "Parchment", "#F4EFE4", "#3D2900", "#5E4210", "#8A6524").replace("\"dark\"", "\"light\""));
        Files.writeString(folder.resolve("notes.txt"), "not a theme");
        final ThemeFiles.Read read = ThemeFiles.read(folder);
        assertThat(read.problems()).isEmpty();
        assertThat(read.themes()).extracting(t -> t.id).containsExactly("pine", "ink_rose", "parchment");
        assertThat(read.themes().get(2).ground.light).as("a light file's ground and tint are light").isTrue();
        assertThat(read.themes().get(2).tint.light).isTrue();
        assertThat(read.themes().getFirst().ground.light).isFalse();
        final NamedTheme pine = read.themes().getFirst();
        assertThat(pine.ground.label).as("the ground's label left out").isEqualTo("Pine");
        assertThat(pine.tint.label).isEqualTo("Pine");
        assertThat(pine.ground).as("a ground of its own, though its colour is a built-in one's").isNotSameAs(Ground.EVERGREEN);
        assertThat(pine.ground.name()).isEqualTo("PINE");
        assertThat(pine.file).contains(folder.resolve("a.json"));
        assertThat(pine.commandId()).isEqualTo("theme.pine");
        try {
            NamedTheme.offer(read.themes());
            assertThat(NamedTheme.values()).hasSize(15);
            assertThat(NamedTheme.values()[12]).isSameAs(pine);
            assertThat(NamedTheme.withId("ink_rose")).contains(read.themes().get(1));
            assertThat(Ground.find("PINE")).contains(pine.ground);
            assertThat(Tint.named("INK_ROSE")).isSameAs(read.themes().get(1).tint);
            assertThat(NamedTheme.of(pine.ground, pine.tint)).contains(pine);
        } finally {
            NamedTheme.offer(List.of());
        }
        assertThat(NamedTheme.values()).hasSize(12);
        assertThat(Ground.find("PINE")).isEmpty();
    }

    /// Each broken file is left out with one line that names it and its problem; a good one beside them is still read.
    @Test
    void aBrokenFileIsLeftOutAndNamed() throws IOException {
        Files.writeString(folder.resolve("1-json.json"), "{\"id\": ");
        write("2-id.json", theme("Bad Id", "Bad", "#121212", "#FFEDAA", "#E7C55A", "#9E8230"));
        write("3-colour.json", theme("colour", "Colour", "#12121", "#FFEDAA", "#E7C55A", "#9E8230"));
        write("4-builtin.json", theme("graphite_gold", "Again", "#121212", "#FFEDAA", "#E7C55A", "#9E8230"));
        write("5-light.json", theme("paper", "Paper", "#F7F3EA", "#5A4512", "#7A5C14", "#9E7A2A").replace("\"dark\"", "\"light\""));
        write("6-dim.json", theme("dim", "Dim", "#121212", "#5A5A5A", "#4A4A4A", "#3A3A3A"));
        write("7-good.json", theme("good", "Good", "#121212", "#FFEDAA", "#E7C55A", "#9E8230"));
        write("8-again.json", theme("good", "Good again", "#121212", "#FFEDAA", "#E7C55A", "#9E8230"));
        Files.writeString(folder.resolve("9-field.json"), "{\"id\": \"nofield\", \"label\": \"No tint\", \"ground\": {\"color\": \"#121212\"}}");
        final ThemeFiles.Read read = ThemeFiles.read(folder);
        assertThat(read.themes()).extracting(t -> t.id).containsExactly("good");
        assertThat(read.problems()).hasSize(8);
        assertThat(read.problems().get(0)).startsWith("1-json.json: not JSON");
        assertThat(read.problems().get(1)).isEqualTo("2-id.json: \"id\" must be lower case letters, digits and underscores: Bad Id");
        assertThat(read.problems().get(2)).isEqualTo("3-colour.json: \"ground.color\" must be a colour written #RRGGBB");
        assertThat(read.problems().get(3)).isEqualTo("4-builtin.json: the id graphite_gold is another theme's");
        assertThat(read.problems().get(4)).as("a light theme's tint too pale for its ground")
                .startsWith("5-light.json: the tint's body on the ground reads at 5.6:1, below 7.5:1");
        assertThat(read.problems().get(5)).startsWith("6-dim.json: the tint's body on the ground reads at ").contains("below 7.5:1")
                .contains("more)");
        assertThat(read.problems().get(6)).isEqualTo("8-again.json: the id good is another theme's");
        assertThat(read.problems().get(7)).isEqualTo("9-field.json: \"tint\" must be an object");
    }

    /// No folder, or a folder that is not there, offers nothing and reports nothing.
    @Test
    void noFolderOffersNothing() {
        assertThat(ThemeFiles.read(null)).isEqualTo(new ThemeFiles.Read(List.of(), List.of()));
        assertThat(ThemeFiles.read(folder.resolve("missing"))).isEqualTo(new ThemeFiles.Read(List.of(), List.of()));
    }

    private void write(final String name, final String json) throws IOException {
        Files.writeString(folder.resolve(name), json);
    }

    private static String theme(final String id, final String label, final String ground, final String highlight, final String body, final String shade) {
        return """
                {"id": "%s", "label": "%s", "mode": "dark", "ground": {"label": "%s ground", "color": "%s"},
                 "tint": {"label": "%s tint", "highlight": "%s", "body": "%s", "shade": "%s"}}
                """.formatted(id, label, label, ground, label, highlight, body, shade);
    }
}
