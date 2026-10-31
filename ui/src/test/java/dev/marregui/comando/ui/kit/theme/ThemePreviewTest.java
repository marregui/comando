package dev.marregui.comando.ui.kit.theme;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [ThemePreview] draws a theme in its own colours, whichever theme is chosen (record 128).
/// - Its ground fills the box, and the report's bar is in its tint.
/// - Its text is the theme's: dark ink on a light theme while a dark one is chosen.
class ThemePreviewTest {

    /// Paper and ochre drawn while Graphite and gold is chosen has paper's ground, an ochre bar and dark text; Graphite
    /// and gold has its own ground.
    @Test
    void aThemeIsDrawnInItsOwnColours() {
        assertThat(Theme.groundChosen()).isEqualTo(Ground.GRAPHITE);
        final BufferedImage paper = draw(NamedTheme.PAPER_OCHRE);
        assertThat(paper.getRGB(186, 6) & 0xFFFFFF).as("paper's ground").isEqualTo(Ground.PAPER.color.getRGB() & 0xFFFFFF);
        final int bar = paper.getRGB(9, 48) & 0xFFFFFF;
        assertThat(Oklch.distance(new java.awt.Color(bar), Tint.OCHRE.at(0.5f))).as("the bar in ochre").isLessThan(0.08);
        assertThat(darkest(paper)).as("dark text on paper").isLessThan(0.3);
        final BufferedImage graphite = draw(NamedTheme.GRAPHITE_GOLD);
        assertThat(graphite.getRGB(186, 6) & 0xFFFFFF).isEqualTo(Ground.GRAPHITE.color.getRGB() & 0xFFFFFF);
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
                min = Math.min(min, Oklch.lch(new java.awt.Color(img.getRGB(x, y)))[0]);
            }
        }
        return min;
    }
}
