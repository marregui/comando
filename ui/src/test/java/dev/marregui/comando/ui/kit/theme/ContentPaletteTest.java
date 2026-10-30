package dev.marregui.comando.ui.kit.theme;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// [ContentPalette] derives the content colours (record 101) for every ground in every tint: legible, apart from each
/// other, apart from the tint.
/// - [Oklch] round-trips and cuts chroma, not lightness or hue, out of gamut.
/// - The hue wheel turns at most 30° to put the tint between two category hues.
/// - Syntax, selection, diffs, inline code and terminal colours keep their text legible on every ground.
/// - Each category stands apart from the others, the keyword and the ink, near its customary hue and away from the
///   tint.
/// - The golds share one palette but for their keywords; plot series start at the tint and stay apart.
/// - The palette in use follows the tint and ground chosen.
class ContentPaletteTest {

    private static final String[] CATEGORIES = {"annotation", "number", "string", "regex", "type", "variable"};
    private static final double[] CUSTOM = {0, 60, 120, 180, 240, 300};

    /// An in-gamut colour returns its lightness, chroma and hue; an out-of-gamut one keeps its lightness within 0.02
    /// and its hue within 6°, giving up chroma.
    @Test
    void oklchRoundTripsAndCutsChromaNotHue() {
        double[] lch = Oklch.lch(Oklch.of(0.7, 0.1, 150));
        assertThat(lch[0]).isCloseTo(0.7, within(0.01));
        assertThat(lch[1]).isCloseTo(0.1, within(0.01));
        assertThat(lch[2]).isCloseTo(150, within(2.0));
        double[] cut = Oklch.lch(Oklch.of(0.9, 0.4, 265));
        assertThat(cut[0]).as("lightness kept out of gamut").isCloseTo(0.9, within(0.02));
        assertThat(Oklch.hueGap(cut[2], 265)).as("hue kept out of gamut").isLessThan(6);
    }

    /// [ContentPalette#turn] is the signed shift, −30° to 30°, that moves the nearest midpoint of the customary wheel
    /// (30°, 90°, … 330°) onto the tint's hue: 2 at 92, −3 at 87, −22 at 248, 0 at 30 and −1 at 29.
    @Test
    void theWheelTurnsAtMostThirtyDegreesToPutTheTintBetweenTwoHues() {
        assertThat(ContentPalette.turn(92)).isEqualTo(2);
        assertThat(ContentPalette.turn(87)).isEqualTo(-3);
        assertThat(ContentPalette.turn(248)).isEqualTo(-22);
        assertThat(ContentPalette.turn(30)).isZero();
        assertThat(ContentPalette.turn(29)).isEqualTo(-1);
    }

    /// For every tint and ground, ink, keywords, comments and categories pass their WCAG contrast floors, and each
    /// category is apart in Oklch from the keyword, the ink and every other category.
    @Test
    void syntaxIsLegibleOnEveryGroundAndEachCategoryStandsApart() {
        for (Tint t : Tint.values()) {
            for (Ground g : Ground.values()) {
                ContentPalette p = ContentPalette.of(t, g);
                String at = t + " on " + g;
                assertThat(contrast(p.ink, g.color)).as("ink, " + at).isGreaterThan(12);
                assertThat(contrast(p.keyword, g.color)).as("keywords, " + at).isGreaterThan(7.5);
                assertThat(contrast(p.comment, g.color)).as("comments, " + at).isGreaterThan(4.5);
                assertThat(contrast(p.commentTag, g.color)).as("comment tags, " + at).isGreaterThan(4.5);
                Color[] cats = p.categories();
                for (int i = 0; i < cats.length; i++) {
                    assertThat(contrast(cats[i], g.color)).as(CATEGORIES[i] + ", " + at).isGreaterThan(6.5);
                    assertThat(Oklch.distance(cats[i], p.keyword)).as(CATEGORIES[i] + " apart from keywords, " + at)
                            .isGreaterThan(0.06);
                    assertThat(Oklch.distance(cats[i], p.ink)).as(CATEGORIES[i] + " apart from the ink, " + at)
                            .isGreaterThan(0.08);
                    for (int j = i + 1; j < cats.length; j++) {
                        assertThat(Oklch.distance(cats[i], cats[j])).as(CATEGORIES[i] + " vs " + CATEGORIES[j] + ", " + at)
                                .isGreaterThan(0.09);
                    }
                }
            }
        }
    }

    /// On graphite each category is within 32° of its customary hue and, for a chromatic tint, at least 28° from it.
    @Test
    void everyCategoryStaysNearItsCustomaryHueAndAwayFromTheTints() {
        for (Tint t : Tint.values()) {
            ContentPalette p = ContentPalette.of(t, Ground.GRAPHITE);
            double[] tint = Oklch.lch(t.body);
            Color[] cats = p.categories();
            for (int i = 0; i < cats.length; i++) {
                double hue = Oklch.lch(cats[i])[2];
                assertThat(Oklch.hueGap(hue, CUSTOM[i])).as(CATEGORIES[i] + " in " + t).isLessThanOrEqualTo(32);
                if (tint[1] >= 0.04) {
                    assertThat(Oklch.hueGap(hue, tint[2])).as(CATEGORIES[i] + " away from " + t).isGreaterThanOrEqualTo(28);
                }
            }
        }
    }

    /// YELLOW and CHAMPAGNE give the same category colours as TREASURE to within 0.02.
    @Test
    void theGoldsShareOnePaletteButForTheirKeywords() {
        ContentPalette treasure = ContentPalette.of(Tint.TREASURE, Ground.GRAPHITE);
        for (Tint gold : new Tint[]{Tint.YELLOW, Tint.CHAMPAGNE}) {
            Color[] a = treasure.categories();
            Color[] b = ContentPalette.of(gold, Ground.GRAPHITE).categories();
            for (int i = 0; i < a.length; i++) {
                assertThat(Oklch.distance(a[i], b[i])).as(CATEGORIES[i] + " in " + gold).isLessThan(0.02);
            }
        }
    }

    /// Ink stays legible on the selection and on added and removed lines and words, inline code on its ground, line
    /// numbers are quiet but visible (2–4.5), and added and removed are apart, on every tint and ground.
    @Test
    void theEditorsSelectionDiffsAndCodeKeepTheirTextLegible() {
        for (Tint t : Tint.values()) {
            for (Ground g : Ground.values()) {
                ContentPalette p = ContentPalette.of(t, g);
                String at = t + " on " + g;
                assertThat(contrast(p.ink, p.selection)).as("selected text, " + at).isGreaterThan(4.5);
                assertThat(contrast(p.ink, p.added)).as("an added line, " + at).isGreaterThan(7);
                assertThat(contrast(p.ink, p.removed)).as("a removed line, " + at).isGreaterThan(7);
                assertThat(contrast(p.ink, p.addedWord)).as("an added word, " + at).isGreaterThan(4.5);
                assertThat(contrast(p.ink, p.removedWord)).as("a removed word, " + at).isGreaterThan(4.5);
                assertThat(contrast(p.code, p.codeInlineGround)).as("inline code, " + at).isGreaterThan(7);
                assertThat(contrast(p.lineNumber, g.color)).as("line numbers, quiet but there, " + at).isBetween(2.0, 4.5);
                assertThat(Oklch.distance(p.added, p.removed)).as("added and removed apart, " + at).isGreaterThan(0.05);
            }
        }
    }

    /// The six ANSI colours and their bright forms pass their contrast floors and stay within 6° of their named hue on
    /// every ground, white and bright black read, and a tint does not change red.
    @Test
    void theTerminalsColoursReadOnEveryGroundAndKeepTheirNames() {
        String[] names = {"red", "green", "yellow", "blue", "magenta", "cyan"};
        double[] hues = {25, 145, 95, 255, 330, 200};
        for (Ground g : Ground.values()) {
            ContentPalette p = ContentPalette.of(Tint.TREASURE, g);
            for (int i = 0; i < names.length; i++) {
                assertThat(contrast(p.ansi(1 + i), g.color)).as(names[i] + " on " + g).isGreaterThan(5);
                assertThat(contrast(p.ansi(9 + i), g.color)).as("bright " + names[i] + " on " + g).isGreaterThan(8);
                assertThat(Oklch.hueGap(Oklch.lch(p.ansi(1 + i))[2], hues[i])).as(names[i] + "'s hue").isLessThan(6);
            }
            assertThat(contrast(p.ansi(7), g.color)).as("white on " + g).isGreaterThan(8);
            assertThat(contrast(p.ansi(8), g.color)).as("bright black on " + g).isGreaterThan(3);
        }
        assertThat(ContentPalette.of(Tint.SILVER, Ground.SLATE).ansi(1)).as("a tint leaves red red")
                .isEqualTo(ContentPalette.of(Tint.TREASURE, Ground.SLATE).ansi(1));
    }

    /// Series 0 is the tint's body, the series repeat after their count, and each reads on graphite and stands apart.
    @Test
    void plotSeriesStartAtTheTintAndStayApart() {
        for (Tint t : Tint.values()) {
            ContentPalette p = ContentPalette.of(t, Ground.GRAPHITE);
            assertThat(p.series(0)).isEqualTo(t.body);
            assertThat(p.series(p.seriesCount())).as("they repeat").isEqualTo(p.series(0));
            for (int i = 0; i < p.seriesCount(); i++) {
                assertThat(contrast(p.series(i), Ground.GRAPHITE.color)).as("series " + i + " in " + t).isGreaterThan(5);
                for (int j = i + 1; j < p.seriesCount(); j++) {
                    assertThat(Oklch.distance(p.series(i), p.series(j))).as("series " + i + " vs " + j + " in " + t)
                            .isGreaterThan(0.07);
                }
            }
        }
    }

    /// [Theme#content] is the cached palette of the tint and ground chosen, keywords in the tint and code ground per
    /// ground.
    @Test
    void thePaletteInUseFollowsTheTintAndTheGroundChosen() {
        try {
            Theme.tint(Tint.ELECTRIC_BLUE);
            Theme.ground(Ground.MIDNIGHT);
            assertThat(Theme.content()).isSameAs(ContentPalette.of(Tint.ELECTRIC_BLUE, Ground.MIDNIGHT));
            assertThat(Theme.content().keyword).isEqualTo(Tint.ELECTRIC_BLUE.body);
            assertThat(Theme.content().codeGround).isNotEqualTo(ContentPalette.of(Tint.ELECTRIC_BLUE, Ground.GRAPHITE).codeGround);
        } finally {
            Theme.tint(Tint.TREASURE);
            Theme.ground(Ground.GRAPHITE);
        }
    }

    /** WCAG's contrast ratio of {@code a} over {@code b}. */
    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
