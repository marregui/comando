package dev.marregui.comando.ui.kit.theme;

import org.junit.jupiter.api.Test;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/** The surfaces follow the ground chosen, even where they were set once (record 103). */
class OnGroundTest {

    @Test
    void onGraphiteTheSurfacesKeepTheirColours() {
        assertThat(Theme.hex(Theme.CARD)).isEqualTo("#2d2d2d");
        assertThat(Theme.hex(Theme.USER_BUBBLE)).isEqualTo("#2e3a4a");
    }

    @Test
    void aBorderAndABackgroundMadeOnceTakeAnotherGround() {
        Border border = BorderFactory.createMatteBorder(2, 0, 0, 0, Theme.CARD_BORDER);
        JPanel panel = new JPanel();
        panel.setBackground(Theme.CARD);
        panel.setSize(10, 10);
        try {
            int onGraphite = paintedTop(border, panel);
            Color graphiteCard = panel.getBackground();
            int graphiteRgb = graphiteCard.getRGB();
            Theme.ground(Ground.MIDNIGHT);
            assertThat(paintedTop(border, panel)).as("the border painted on midnight").isNotEqualTo(onGraphite)
                    .isEqualTo(Tint.over(Ground.MIDNIGHT.color, Color.WHITE, 0.177f).getRGB());
            assertThat(panel.getBackground().getRGB()).as("the background").isNotEqualTo(graphiteRgb)
                    .isEqualTo(Tint.over(Ground.MIDNIGHT.color, Color.WHITE, 0.115f).getRGB());
            assertThat(Theme.CARD).isEqualTo(new Color(Theme.CARD.getRGB())).hasSameHashCodeAs(new Color(Theme.CARD.getRGB()));
        } finally {
            Theme.ground(Ground.GRAPHITE);
        }
    }

    @Test
    void everySurfaceStandsOffEveryGround() {
        try {
            for (Ground g : Ground.values()) {
                Theme.ground(g);
                for (Color c : new Color[]{Theme.CARD, Theme.CARD_BORDER, Theme.USER_BUBBLE, Theme.INLINE_CODE_GROUND}) {
                    assertThat(Oklch.distance(c, g.color)).as(Theme.hex(c) + " on " + g).isGreaterThan(0.04);
                }
                assertThat(Oklch.distance(Theme.CARD_BORDER, Theme.CARD)).as("a card's border on " + g).isGreaterThan(0.03);
            }
        } finally {
            Theme.ground(Ground.GRAPHITE);
        }
    }

    private static int paintedTop(Border border, JPanel panel) {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        border.paintBorder(panel, g, 0, 0, 10, 10);
        g.dispose();
        return img.getRGB(5, 0);
    }
}
