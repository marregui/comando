package dev.marregui.comando.ui.kit.icon;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The icon sets shipped beside Classic, as files: `icons/sets/<id>/` for every [IconSet] but Classic.
/// - Every set draws the same icons, each one Classic also has, so a switch changes every icon it draws.
/// - Every icon of every set paints visible pixels at 16 px, the Monospace set's text included.
class IconSetResourcesTest {

    private static final Path ICONS = Path.of("src/main/resources/icons");

    /// Each set other than Classic holds the same names, at least the 105 icons in use that a set draws, and each of
    /// them is also a Classic icon.
    @Test
    void everySetDrawsTheSameIconsEachOneClassicHas() throws IOException {
        final Set<String> first = names(IconSet.HAIRLINE);
        assertThat(first).hasSizeGreaterThanOrEqualTo(105);
        for (final IconSet set : IconSet.values()) {
            if (set != IconSet.CLASSIC) {
                assertThat(names(set)).as(set.id()).isEqualTo(first);
            }
        }
        for (final String name : first) {
            assertThat(ICONS.resolve(name + ".svg")).as("Classic has " + name).exists();
        }
    }

    /// Every SVG of every set, loaded as the application loads icons and painted at 16 px, leaves pixels that are not
    /// transparent.
    @Test
    void everyIconOfEverySetPaintsVisiblePixels() throws IOException {
        for (final IconSet set : IconSet.values()) {
            if (set == IconSet.CLASSIC) {
                continue;
            }
            for (final String name : names(set)) {
                final FlatSVGIcon icon = new FlatSVGIcon(set.path(name), 16, 16);
                final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                final Graphics2D g = image.createGraphics();
                try {
                    icon.paintIcon(null, g, 0, 0);
                } finally {
                    g.dispose();
                }
                assertThat(opaquePixels(image)).as(set.id() + "/" + name).isPositive();
            }
        }
    }

    private static Set<String> names(final IconSet set) throws IOException {
        final Path root = ICONS.resolve("sets").resolve(set.id());
        try (final Stream<Path> files = Files.walk(root)) {
            final List<String> svgs = files.filter(p -> p.toString().endsWith(".svg"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .map(n -> n.substring(0, n.length() - ".svg".length()))
                    .toList();
            return new TreeSet<>(svgs);
        }
    }

    private static int opaquePixels(final BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
