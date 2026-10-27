package dev.marregui.comando.ui.kit.icon;

import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SpinnerIconTest {

    @Test
    void theWheelTurnsOneBarPerStepAndComesRound() {
        SpinnerIcon icon = new SpinnerIcon(16);
        int start = icon.head();
        icon.advance();
        assertThat(icon.head()).isEqualTo(start - 1);
        for (int i = 1; i < 8; i++) {
            icon.advance();
        }
        assertThat(icon.head()).as("eight steps make one revolution").isEqualTo(start);
    }

    @Test
    void everyStepPaintsInsideTheIconAndDiffersFromTheLast() {
        SpinnerIcon icon = new SpinnerIcon(16);
        assertThat(icon.getIconWidth()).isEqualTo(16);
        assertThat(icon.getIconHeight()).isEqualTo(16);
        BufferedImage before = paint(icon);
        icon.advance();
        BufferedImage after = paint(icon);
        assertThat(pixels(after)).isNotEqualTo(pixels(before));
        // nothing is drawn outside the 16x16 square at the offset
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                boolean inside = x >= 4 && x < 20 && y >= 4 && y < 20;
                if (!inside) {
                    assertThat(after.getRGB(x, y) >>> 24).as("alpha at " + x + "," + y).isZero();
                }
            }
        }
    }

    @Test
    void theHeadIsTheTintChosenAndTheTailFadesIntoTheGroundInEveryTheme() throws Exception {
        try {
            SpinnerIcon icon = new SpinnerIcon(32);
            for (NamedTheme t : NamedTheme.values()) {
                Theme.ground(t.ground);
                Theme.tint(t.tint);
                assertThat(Theme.spinnerShade(0)).as("the head in " + t).isEqualTo(t.tint.body);
                assertThat(distance(Theme.spinnerShade(7), t.ground.color))
                        .as("the tail nearer the ground than the head, in " + t)
                        .isLessThan(distance(Theme.spinnerShade(0), t.ground.color));
                BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                icon.paintIcon(null, g, 4, 4);
                g.dispose();
                assertThat(count(img, t.tint.body)).as("the head painted in " + t).isPositive();
                assertThat(IntStream.range(0, 8).mapToObj(Theme::spinnerShade).filter(c -> c.getBlue() == 0xFF && c.getRed() < 0x40))
                        .as("no bar in the old blue, in " + t).isEmpty();
            }
        } finally {
            Theme.ground(Ground.GRAPHITE);
            Theme.tint(Tint.TREASURE);
        }
    }

    @Test
    void theHourglassIsDrawnInTheSvgsGoldSoItPaintsInTheTint() throws Exception {
        String svg = Files.readString(Path.of("src/main/resources/icons/hourglass.svg"));
        assertThat(svg).contains(String.format("#%06X", Icons.SVG_GOLD)).doesNotContain("#548AF7");
    }

    private static int distance(Color a, Color b) {
        int r = a.getRed() - b.getRed();
        int g = a.getGreen() - b.getGreen();
        int bl = a.getBlue() - b.getBlue();
        return r * r + g * g + bl * bl;
    }

    private static int count(BufferedImage img, Color c) {
        int n = 0;
        for (int p : pixels(img)) {
            if ((p >>> 24) == 0xFF && (p & 0xFFFFFF) == (c.getRGB() & 0xFFFFFF)) {
                n++;
            }
        }
        return n;
    }

    private static BufferedImage paint(SpinnerIcon icon) {
        BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(null, g, 4, 4);
        g.dispose();
        return img;
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }
}
