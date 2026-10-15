package dev.marregui.comando.ui.kit.icon;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

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
