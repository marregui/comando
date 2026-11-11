package dev.marregui.comando.ui.kit.theme;

import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [BracketBorder]: a region's frame, read from the theme chosen at each paint and each layout.
/// - under a plain theme, dark or light, it takes no room and paints nothing
/// - under a console theme it takes 3 px a side and paints a hairline in the card border, with a 10 px bracket in the
///   tint's shade at each corner and nothing inside
@ExtendWith(OnEdt.class)
class BracketBorderTest {

    private static final int WIDTH = 60;
    private static final int HEIGHT = 40;

    /// Graphite and gold, a dark theme, and paper and ochre, a light one: no room on any side and not a pixel painted.
    @Test
    void underAPlainThemeTheFrameTakesNoRoomAndPaintsNothing() {
        for (final NamedTheme t : List.of(NamedTheme.GRAPHITE_GOLD, NamedTheme.PAPER_OCHRE)) {
            under(t, () -> {
                assertThat(Theme.style()).as(t.id).isEqualTo(Style.PLAIN);
                assertThat(new BracketBorder().getBorderInsets(new JPanel())).as(t.id)
                        .isEqualTo(new Insets(0, 0, 0, 0));
                final BufferedImage image = painted();
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        assertThat(image.getRGB(x, y)).as("%s at %d, %d", t.id, x, y).isZero();
                    }
                }
            });
        }
    }

    /// Both console themes: 3 px a side; every corner's two arms in the tint's shade for 10 px, the hairline on from
    /// there to the next bracket, and the inside left unpainted.
    @Test
    void underAConsoleThemeTheFrameIsAHairlineWithCornerBrackets() {
        for (final NamedTheme t : List.of(NamedTheme.CONSOLE_AMBER, NamedTheme.CONSOLE_CYAN)) {
            under(t, () -> {
                assertThat(Theme.style()).as(t.id).isEqualTo(Style.CONSOLE);
                assertThat(new BracketBorder().getBorderInsets(new JPanel())).as(t.id)
                        .isEqualTo(new Insets(3, 3, 3, 3));
                final BufferedImage image = painted();
                final int shade = t.tint.shade.getRGB();
                final int hairline = Theme.CARD_BORDER.getRGB();
                assertThat(shade).as("the bracket stands out of the hairline").isNotEqualTo(hairline);
                final int right = WIDTH - 1;
                final int bottom = HEIGHT - 1;
                for (int i = 0; i < BracketBorder.ARM; i++) {
                    assertThat(List.of(image.getRGB(i, 0), image.getRGB(0, i), image.getRGB(right - i, 0),
                            image.getRGB(right, i), image.getRGB(i, bottom), image.getRGB(0, bottom - i),
                            image.getRGB(right - i, bottom), image.getRGB(right, bottom - i)))
                            .as("%s, %d px along the arms", t.id, i).containsOnly(shade);
                }
                final int past = BracketBorder.ARM;
                assertThat(List.of(image.getRGB(past, 0), image.getRGB(0, past), image.getRGB(right - past, 0),
                        image.getRGB(right, past), image.getRGB(past, bottom), image.getRGB(0, bottom - past),
                        image.getRGB(right - past, bottom), image.getRGB(right, bottom - past),
                        image.getRGB(WIDTH / 2, 0), image.getRGB(0, HEIGHT / 2)))
                        .as("%s, the hairline past the arms", t.id).containsOnly(hairline);
                assertThat(List.of(image.getRGB(1, 1), image.getRGB(WIDTH / 2, HEIGHT / 2),
                        image.getRGB(right - 1, bottom - 1))).as("%s, inside", t.id).containsOnly(0);
            });
        }
    }

    /// Runs {@code check} with {@code t} chosen, then chooses again the theme found, so no other test inherits it.
    private static void under(final NamedTheme t, final Runnable check) {
        final Ground ground = Theme.groundChosen();
        final Tint tint = Theme.tint();
        Theme.choose(t.ground, t.tint);
        try {
            check.run();
        } finally {
            Theme.choose(ground, tint);
        }
    }

    private static BufferedImage painted() {
        final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            new BracketBorder().paintBorder(new JPanel(), g, 0, 0, WIDTH, HEIGHT);
        } finally {
            g.dispose();
        }
        return image;
    }
}
