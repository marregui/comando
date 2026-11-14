package dev.marregui.comando.ui.kit.icon;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RGBImageFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.Icon;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.GrayFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [SvgIcon] paints an SVG from the classpath at a fixed size, at the scale it is painted at, its colours through a
/// filter read at each paint.
/// - At scale 1 a 16 point icon fills 16 by 16 pixels in the filtered colour and nothing outside them.
/// - At scale 2 it is drawn anew at 32 by 32 pixels, not the scale-1 picture enlarged.
/// - A tint chosen after a paint shows at the next paint of the same icon.
/// - A path with no SVG reports it, keeps its size and paints red.
/// - The disabled twin greys the filtered colour as FlatLaf greys a disabled icon.
/// - [BlendIcon] moves the SVG's colour towards its own as its share grows.
/// - Every SVG of the icon set is found and paints visible pixels at 16 points.
class SvgIconTest {

    private static final Path ICONS = Path.of("src/main/resources/icons");
    /// The new-file dot: a circle of radius 3.2 around (8, 8), in the SVG gold the tint replaces.
    private static final String DOT = "icons/new-file.svg";

    @AfterEach
    void defaultTint() {
        Theme.tint(Tint.TREASURE);
    }

    /// The new-file dot, 16 points, painted at offset 4 in a 24 pixel image at scale 1, is 16 by 16, paints its centre
    /// in the tint's body and leaves every pixel outside its square transparent.
    @Test
    void atScaleOneASixteenPointIconPaintsSixteenPixelsInTheFilteredColour() {
        Theme.tint(Tint.CHAMPAGNE);
        final SvgIcon icon = new SvgIcon(DOT, 16, 16, Icons::gilded);
        assertThat(icon.getIconWidth()).isEqualTo(16);
        assertThat(icon.getIconHeight()).isEqualTo(16);
        final BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(null, g, 4, 4);
        } finally {
            g.dispose();
        }
        assertThat(image.getRGB(12, 12)).as("the dot's centre").isEqualTo(Tint.CHAMPAGNE.body.getRGB());
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                if (x < 4 || x >= 20 || y < 4 || y >= 20) {
                    assertThat(image.getRGB(x, y) >>> 24).as("alpha at " + x + "," + y).isZero();
                }
            }
        }
    }

    /// An icon painted at scale 1 and then at scale 2 paints at scale 2 exactly what an icon painted only at scale 2
    /// paints, the dot's centre in the tint's body, and the dot spans about twice the pixels it spans at scale 1.
    @Test
    void atScaleTwoTheIconIsDrawnAnewAtTwiceThePixels() {
        final SvgIcon icon = new SvgIcon(DOT, 16, 16, Icons::gilded);
        final BufferedImage one = paint(icon, 1);
        final BufferedImage two = paint(icon, 2);
        final BufferedImage fresh = paint(new SvgIcon(DOT, 16, 16, Icons::gilded), 2);
        assertThat(two.getWidth()).isEqualTo(32);
        assertThat(pixels(two)).as("the scale-2 picture, not the scale-1 one enlarged").isEqualTo(pixels(fresh));
        assertThat(two.getRGB(16, 16)).isEqualTo(Tint.TREASURE.body.getRGB());
        final int spanOne = span(one);
        assertThat(spanOne).isPositive();
        assertThat(span(two)).as("the dot's width in pixels").isBetween(2 * spanOne - 2, 2 * spanOne + 2);
    }

    /// An icon painted on a scale-1 and a scale-2 screen in turn keeps one picture per scale, so neither screen's
    /// repaint draws the SVG again.
    @Test
    void anIconKeepsOnePicturePerScale() {
        final SvgIcon icon = new SvgIcon(DOT, 16, 16, Icons::gilded);
        paint(icon, 1);
        paint(icon, 2);
        paint(icon, 1);
        assertThat(icon.pictures()).isEqualTo(2);
    }

    /// One icon painted under TREASURE and then, after SILVER is chosen, painted again at the same scale shows
    /// SILVER's body at its centre: the filter is read at the paint, not when the picture was first drawn.
    @Test
    void aTintChosenAfterAPaintShowsAtTheNextPaint() {
        final SvgIcon icon = new SvgIcon(DOT, 16, 16, Icons::gilded);
        assertThat(paint(icon, 2).getRGB(16, 16)).isEqualTo(Tint.TREASURE.body.getRGB());
        Theme.tint(Tint.SILVER);
        assertThat(paint(icon, 2).getRGB(16, 16)).isEqualTo(Tint.SILVER.body.getRGB());
    }

    /// A path with no SVG behind it is not found, keeps the size it was given and paints a square in the theme's
    /// danger colour; a path with one is found.
    @Test
    void aMissingIconReportsNotFoundKeepsItsSizeAndPaintsTheDangerColour() {
        final SvgIcon missing = new SvgIcon("icons/no-such-icon.svg", 16, 16, Icons::gilded);
        assertThat(missing.hasFound()).isFalse();
        assertThat(missing.getIconWidth()).isEqualTo(16);
        assertThat(missing.getIconHeight()).isEqualTo(16);
        assertThat(paint(missing, 1).getRGB(8, 8)).isEqualTo(Theme.DANGER.getRGB());
        assertThat(new SvgIcon(DOT, 16, 16, Icons::gilded).hasFound()).isTrue();
    }

    /// The disabled twin of the dot paints its centre in the grey FlatLaf's disabled filter makes of the tint's body,
    /// a grey (red, green and blue equal) that differs from the enabled colour; the twin's own twin is itself.
    @Test
    void theDisabledTwinGreysTheFilteredColourAsFlatLafDoes() {
        final SvgIcon icon = new SvgIcon(DOT, 16, 16, Icons::gilded);
        final Icon disabled = icon.getDisabledIcon();
        final RGBImageFilter grey = UIManager.get("Component.grayFilter") instanceof final RGBImageFilter f
                ? f
                : GrayFilter.createDisabledIconFilter(FlatLaf.isLafDark());
        final int expected = grey.filterRGB(0, 0, Tint.TREASURE.body.getRGB());
        final Color centre = new Color(paint(disabled, 1).getRGB(8, 8), true);
        assertThat(centre.getRGB()).isEqualTo(expected);
        assertThat(centre.getRed()).isEqualTo(centre.getGreen()).isEqualTo(centre.getBlue());
        assertThat(centre.getRGB()).isNotEqualTo(Tint.TREASURE.body.getRGB());
        assertThat(((SvgIcon) disabled).getDisabledIcon()).isSameAs(disabled);
        assertThat(disabled.getIconWidth()).isEqualTo(16);
    }

    /// The checkpoint bookmark (grey `#6E6E6E`) painted by a [BlendIcon] towards pure blue is its grey at share 0, blue
    /// at share 1 and their mix at share 0.5, each read at the paint; disabled at share 1 it is FlatLaf's grey of blue.
    @Test
    void aBlendIconMovesTheSvgsColourTowardsItsOwnAsItsShareGrows() {
        final Color blue = new Color(0x0000FF);
        final BlendIcon icon = new BlendIcon("checkpoint", blue);
        assertThat(paint(icon, 1).getRGB(8, 6)).as("share 0").isEqualTo(new Color(0x6E6E6E).getRGB());
        icon.share(1.0);
        assertThat(paint(icon, 1).getRGB(8, 6)).as("share 1").isEqualTo(blue.getRGB());
        icon.share(0.5);
        assertThat(paint(icon, 1).getRGB(8, 6)).as("share 0.5")
                .isEqualTo(Tint.over(new Color(0x6E6E6E), blue, 0.5f).getRGB());
        icon.share(1.0);
        final Color centre = new Color(paint(icon.getDisabledIcon(), 1).getRGB(8, 6), true);
        assertThat(centre.getRed()).as("disabled, a grey").isEqualTo(centre.getGreen()).isEqualTo(centre.getBlue());
    }

    /// Every SVG under `icons/`, the sidebar's included, loaded as [Icons] loads them and painted at 16 points, is
    /// found and leaves pixels that are not transparent.
    @Test
    void everyIconOfTheSetIsFoundAndPaintsVisiblePixels() throws IOException {
        final List<Path> svgs;
        try (Stream<Path> files = Files.walk(ICONS)) {
            svgs = files.filter(p -> p.toString().endsWith(".svg")).sorted().toList();
        }
        assertThat(svgs).hasSizeGreaterThanOrEqualTo(100);
        for (final Path svg : svgs) {
            final String path = "icons/" + ICONS.relativize(svg).toString().replace('\\', '/');
            final SvgIcon icon = new SvgIcon(path, 16, 16, Icons::gilded);
            assertThat(icon.hasFound()).as(path).isTrue();
            assertThat(opaquePixels(paint(icon, 1))).as(path).isPositive();
        }
    }

    /// `icon` painted at the origin of an image of its size times `scale`, through a graphics so scaled.
    private static BufferedImage paint(final Icon icon, final int scale) {
        final BufferedImage image = new BufferedImage(icon.getIconWidth() * scale, icon.getIconHeight() * scale,
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.scale(scale, scale);
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int[] pixels(final BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static long opaquePixels(final BufferedImage image) {
        return Arrays.stream(pixels(image)).filter(argb -> argb >>> 24 != 0).count();
    }

    /// The number of columns that hold a pixel that is not transparent.
    private static int span(final BufferedImage image) {
        int columns = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) >>> 24 != 0) {
                    columns++;
                    break;
                }
            }
        }
        return columns;
    }
}
