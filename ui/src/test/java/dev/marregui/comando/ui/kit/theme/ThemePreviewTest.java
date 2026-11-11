package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [ThemePreview] draws a theme in its own colours, whichever theme is chosen (record 128).
/// - Its ground fills the box, and the report's bar is in its tint.
/// - Its text is the theme's: dark ink on a light theme while a dark one is chosen.
/// - A console theme's card is square, with a bracket in its tint's shade at each corner (record 151).
class ThemePreviewTest {

    /// Paper and ochre drawn while Graphite and gold is chosen has paper's ground, an ochre bar and dark text; Graphite
    /// and gold has its own ground.
    @Test
    void aThemeIsDrawnInItsOwnColours() {
        assertThat(Theme.groundChosen()).isEqualTo(Ground.GRAPHITE);
        final BufferedImage paper = draw(NamedTheme.PAPER_OCHRE);
        assertThat(paper.getRGB(186, 6) & 0xFFFFFF).as("paper's ground").isEqualTo(Ground.PAPER.color.getRGB() & 0xFFFFFF);
        final int bar = paper.getRGB(9, 48) & 0xFFFFFF;
        assertThat(Oklch.distance(new Color(bar), Tint.OCHRE.at(0.5f))).as("the bar in ochre").isLessThan(0.08);
        assertThat(darkest(paper)).as("dark text on paper").isLessThan(0.3);
        final BufferedImage graphite = draw(NamedTheme.GRAPHITE_GOLD);
        assertThat(graphite.getRGB(186, 6) & 0xFFFFFF).isEqualTo(Ground.GRAPHITE.color.getRGB() & 0xFFFFFF);
    }

    /// Console amber's card fills its corners: each corner pixel and the bracket's arms are amber's shade, the pixel
    /// past an arm is its ground; a plain theme's corner is outside its rounded card.
    @Test
    void aConsoleThemesCardIsSquareWithCornerBrackets() {
        final BufferedImage amber = draw(NamedTheme.CONSOLE_AMBER);
        final int shade = NamedTheme.CONSOLE_AMBER.tint.shade.getRGB() & 0xFFFFFF;
        final int ground = NamedTheme.CONSOLE_AMBER.ground.color.getRGB() & 0xFFFFFF;
        final int last = ThemePreview.BRACKET - 1;
        for (final int[] corner : new int[][]{{0, 0}, {199, 0}, {0, 99}, {199, 99}}) {
            final int x = corner[0];
            final int y = corner[1];
            final int dx = x == 0 ? 1 : -1;
            final int dy = y == 0 ? 1 : -1;
            assertThat(amber.getRGB(x, y) & 0xFFFFFF).as("corner " + x + "," + y).isEqualTo(shade);
            assertThat(amber.getRGB(x + dx * last, y) & 0xFFFFFF).as("across, " + x + "," + y).isEqualTo(shade);
            assertThat(amber.getRGB(x, y + dy * last) & 0xFFFFFF).as("down, " + x + "," + y).isEqualTo(shade);
            assertThat(amber.getRGB(x + dx * ThemePreview.BRACKET, y) & 0xFFFFFF).as("past the arm, " + x + "," + y)
                    .isEqualTo(ground);
        }
        assertThat(draw(NamedTheme.GRAPHITE_GOLD).getRGB(0, 0) & 0xFFFFFF).as("a plain card's rounded corner")
                .isNotEqualTo(Ground.GRAPHITE.color.getRGB() & 0xFFFFFF);
    }

    private static BufferedImage draw(final NamedTheme t) {
        final BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        ThemePreview.paint(g, t, 0, 0, 200, 100, new Font(Font.SANS_SERIF, Font.PLAIN, 12), new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.dispose();
        return img;
    }

    /** The lowest Oklch lightness of any pixel. */
    private static double darkest(final BufferedImage img) {
        double min = 1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                min = Math.min(min, Oklch.lch(new Color(img.getRGB(x, y)))[0]);
            }
        }
        return min;
    }
}
