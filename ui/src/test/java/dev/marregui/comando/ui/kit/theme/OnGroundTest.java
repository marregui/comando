package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.border.Border;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Theme]'s surfaces follow the ground chosen, even where they were set once (record 103).
/// - On graphite the surfaces keep their colours.
/// - A border and a background made once repaint in another ground.
/// - Every surface stands off every ground, and a card's border off the card.
class OnGroundTest {

    /// On the default ground the card is `#2d2d2d` and the user bubble `#2e3a4a`.
    @Test
    void onGraphiteTheSurfacesKeepTheirColours() {
        assertThat(Theme.hex(Theme.CARD)).isEqualTo("#2d2d2d");
        assertThat(Theme.hex(Theme.USER_BUBBLE)).isEqualTo("#2e3a4a");
    }

    /// A matte border and a panel background created from the card colours before a ground change paint the midnight
    /// mixes afterwards, and the dynamic colour still equals a plain [Color] of its current RGB.
    @Test
    void aBorderAndABackgroundMadeOnceTakeAnotherGround() {
        final Border border = BorderFactory.createMatteBorder(2, 0, 0, 0, Theme.CARD_BORDER);
        final JPanel panel = new JPanel();
        panel.setBackground(Theme.CARD);
        panel.setSize(10, 10);
        try {
            final int onGraphite = paintedTop(border, panel);
            final Color graphiteCard = panel.getBackground();
            final int graphiteRgb = graphiteCard.getRGB();
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

    /// On each ground the card, its border, the user bubble and the inline code ground differ from the ground by more
    /// than 0.04 in Oklch, and the border from the card by more than 0.03.
    @Test
    void everySurfaceStandsOffEveryGround() {
        try {
            for (final Ground g : Ground.values()) {
                Theme.ground(g);
                for (final Color c : new Color[]{Theme.CARD, Theme.CARD_BORDER, Theme.USER_BUBBLE, Theme.INLINE_CODE_GROUND}) {
                    assertThat(Oklch.distance(c, g.color)).as(Theme.hex(c) + " on " + g).isGreaterThan(0.04);
                }
                assertThat(Oklch.distance(Theme.CARD_BORDER, Theme.CARD)).as("a card's border on " + g).isGreaterThan(0.03);
            }
        } finally {
            Theme.ground(Ground.GRAPHITE);
        }
    }

    private static int paintedTop(final Border border, final JPanel panel) {
        final BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        border.paintBorder(panel, g, 0, 0, 10, 10);
        g.dispose();
        return img.getRGB(5, 0);
    }
}
