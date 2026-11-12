package dev.marregui.comando.ui.kit.icon;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;

import com.formdev.flatlaf.FlatLaf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [IconSet] names the icon families and [Icons] changes their paint while keeping the instances components hold.
class IconSetTest {

    @AfterEach
    void chooseClassic() {
        Icons.choose(IconSet.CLASSIC);
    }

    /// Every persisted id reads back to its family and every family has the path it ships at.
    @Test
    void idsRoundTripAndPathsNameTheirResources() {
        for (final IconSet set : IconSet.values()) {
            assertThat(IconSet.withId(set.id())).contains(set);
            final String expected = set == IconSet.CLASSIC
                    ? "icons/session.svg"
                    : "icons/sets/" + set.id() + "/session.svg";
            assertThat(set.path("session")).isEqualTo(expected);
        }
        assertThat(IconSet.withId("gone")).isEmpty();
    }

    /// A family without an icon uses Classic's SVG, while an icon held before a choice stays the same object and paints
    /// the selected family's SVG afterwards.
    @Test
    void iconsFallBackAndChangeTheirPaintWithoutChangingIdentity() {
        assertThat(Icons.of(IconSet.HAIRLINE, "missing-name")).isSameAs(Icons.of(IconSet.CLASSIC, "missing-name"));
        final Icon icon = Icons.get("session");
        final BufferedImage classic = painted(icon);
        Icons.choose(IconSet.HAIRLINE);
        assertThat(Icons.get("session")).isSameAs(icon);
        assertThat(pixels(painted(icon))).as("Hairline's session paints other pixels").isNotEqualTo(pixels(classic));
    }

    /// An icon's disabled form paints other pixels than the icon itself, and it follows the set chosen after it was
    /// taken.
    @Test
    void theDisabledFormIsGreyedAndFollowsTheSetChosenLater() {
        final Icon icon = Icons.get("session");
        final Icon disabled = ((FlatLaf.DisabledIconProvider) icon).getDisabledIcon();
        assertThat(pixels(painted(disabled))).as("greyed").isNotEqualTo(pixels(painted(icon)));
        final int[] classicGrey = pixels(painted(disabled));
        Icons.choose(IconSet.HAIRLINE);
        assertThat(pixels(painted(disabled))).as("follows the set").isNotEqualTo(classicGrey);
    }

    private static int[] pixels(final BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static BufferedImage painted(final Icon icon) {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
