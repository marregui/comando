package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.RepaintManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [Gauge], the console style's instrument: a caption, a track filled to a percent, an optional tick.
/// - the percent and the tick are kept within 0 to 100
/// - the fill is as long as the share of the track's 60 pixels, the tick in the column of its percent
/// - it paints only the theme's colours, under a dark theme and a light one
/// - it asks for a repaint only when what it shows changes
/// - assistive technology reads its caption and its percent
@ExtendWith(OnEdt.class)
class GaugeTest {

    /// A percent below 0 or above 100 shows as 0 or 100; so does a tick.
    @Test
    void thePercentAndTheTickAreKeptWithinAHundred() {
        final Gauge gauge = new Gauge("C WEEK");
        gauge.show(-5, Theme.DANGER);
        assertThat(gauge.percent()).isZero();
        gauge.show(140, Theme.DANGER);
        assertThat(gauge.percent()).isEqualTo(100);
        assertThat(gauge.fill()).isSameAs(Theme.DANGER);
        gauge.tick(OptionalInt.of(130));
        assertThat(gauge.tick()).hasValue(100);
        gauge.tick(OptionalInt.of(-1));
        assertThat(gauge.tick()).hasValue(0);
        gauge.tick(OptionalInt.empty());
        assertThat(gauge.tick()).isEmpty();
    }

    /// On the track's middle row the fill covers no pixel at 0, 30 at 50 and all 60 at 100.
    @Test
    void theFillIsAsLongAsItsShareOfTheTrack() {
        final Ground ground = Theme.groundChosen();
        final Tint tint = Theme.tint();
        try {
            Theme.choose(NamedTheme.CONSOLE_AMBER.ground, NamedTheme.CONSOLE_AMBER.tint);
            final Gauge gauge = new Gauge("C SESSION");
            for (final int percent : new int[]{0, 50, 100}) {
                gauge.show(percent, Theme.DANGER);
                final BufferedImage image = paint(gauge);
                int filled = 0;
                for (int x = 0; x < image.getWidth(); x++) {
                    if (image.getRGB(x, image.getHeight() / 2) == Theme.DANGER.getRGB()) {
                        filled++;
                    }
                }
                assertThat(filled).as("at %d%%", percent).isEqualTo(percent * Gauge.TRACK_W / 100);
            }
        } finally {
            Theme.choose(ground, tint);
        }
    }

    /// A tick at 40 is one column, 24 pixels into the track, as high as the track and a pixel more on each side.
    @Test
    void theTickStandsInTheColumnOfItsPercent() {
        final Ground ground = Theme.groundChosen();
        final Tint tint = Theme.tint();
        try {
            Theme.choose(NamedTheme.CONSOLE_AMBER.ground, NamedTheme.CONSOLE_AMBER.tint);
            final Gauge gauge = new Gauge("CTX");
            gauge.tick(OptionalInt.of(40));
            final BufferedImage image = paint(gauge);
            final List<Integer> columns = new ArrayList<>();
            int rows = 0;
            for (int x = trackX(gauge); x < image.getWidth(); x++) {
                int high = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    if (image.getRGB(x, y) == Theme.MUTED_STRONG.getRGB()) {
                        high++;
                    }
                }
                if (high > 0) {
                    columns.add(x - trackX(gauge));
                    rows = high;
                }
            }
            assertThat(columns).containsExactly(24);
            assertThat(rows).isEqualTo(Gauge.TICK_H);
        } finally {
            Theme.choose(ground, tint);
        }
    }

    /// Over the ground, a gauge half full in the tint with a tick paints the ground, the card border, the tint and the
    /// strong muted colour on its track, and on its caption the muted colour or its smoothing toward the ground, on a
    /// dark theme and on a light one.
    @Test
    void itPaintsOnlyTheThemesColoursOnADarkThemeAndALightOne() {
        final Ground ground = Theme.groundChosen();
        final Tint tint = Theme.tint();
        try {
            for (final NamedTheme theme : List.of(NamedTheme.CONSOLE_AMBER, NamedTheme.PAPER_OCHRE)) {
                Theme.choose(theme.ground, theme.tint);
                final Gauge gauge = new Gauge("C WEEK");
                gauge.show(50, Theme.tint().body);
                gauge.tick(OptionalInt.of(80));
                final BufferedImage image = paint(gauge);
                final Set<Integer> track = Set.of(Theme.ground().getRGB(), Theme.CARD_BORDER.getRGB(),
                        Theme.tint().body.getRGB(), Theme.MUTED_STRONG.getRGB());
                int caption = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        final int rgb = image.getRGB(x, y);
                        if (x >= trackX(gauge)) {
                            assertThat(track).as("%s at %d,%d", theme.id, x, y).contains(rgb);
                        } else {
                            assertThat(between(Theme.ground(), Theme.MUTED, new Color(rgb)))
                                    .as("%s caption at %d,%d: %08x", theme.id, x, y, rgb).isTrue();
                            caption += rgb == Theme.ground().getRGB() ? 0 : 1;
                        }
                    }
                }
                assertThat(caption).as("%s: the caption is drawn", theme.id).isPositive();
            }
        } finally {
            Theme.choose(ground, tint);
        }
    }

    /// Showing the same percent in the same colour, or the same tick, asks for no repaint; a new value of either does.
    @Test
    void itAsksForARepaintOnlyWhenWhatItShowsChanges() {
        final Gauge gauge = new Gauge("C SESSION");
        final List<JComponent> dirty = new ArrayList<>();
        final RepaintManager before = RepaintManager.currentManager(gauge);
        RepaintManager.setCurrentManager(new RepaintManager() {
            @Override
            public void addDirtyRegion(final JComponent c, final int x, final int y, final int w, final int h) {
                dirty.add(c);
            }
        });
        try {
            gauge.show(50, Theme.DANGER);
            gauge.show(50, Theme.DANGER);
            assertThat(dirty).as("one change").hasSize(1);
            gauge.tick(OptionalInt.of(40));
            gauge.tick(OptionalInt.of(40));
            assertThat(dirty).as("and the tick").hasSize(2);
            gauge.show(50, Theme.ATTENTION);
            gauge.show(51, Theme.ATTENTION);
            gauge.tick(OptionalInt.empty());
            assertThat(dirty).as("another colour, percent and no tick").hasSize(5).containsOnly(gauge);
        } finally {
            RepaintManager.setCurrentManager(before);
        }
    }

    /// The accessible name is the caption, the role a progress bar and the value the percent shown.
    @Test
    void assistiveTechnologyReadsItsCaptionAndItsPercent() {
        final Gauge gauge = new Gauge("CTX");
        gauge.show(42, Theme.THINKING);
        final AccessibleContext a = gauge.getAccessibleContext();
        assertThat(a.getAccessibleName()).isEqualTo("CTX");
        assertThat(a.getAccessibleRole()).isEqualTo(AccessibleRole.PROGRESS_BAR);
        assertThat(a.getAccessibleValue().getCurrentAccessibleValue()).isEqualTo(42);
        assertThat(a.getAccessibleValue().setCurrentAccessibleValue(7)).as("read only").isFalse();
    }

    /// The gauge at its preferred size, painted over the theme's ground.
    private static BufferedImage paint(final Gauge gauge) {
        final Dimension size = gauge.getPreferredSize();
        gauge.setSize(size);
        final BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setColor(Theme.ground());
            g.fillRect(0, 0, size.width, size.height);
            gauge.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /// Where the track starts: after the caption and the space that parts them.
    private static int trackX(final Gauge gauge) {
        return gauge.getFontMetrics(gauge.getFont()).stringWidth(gauge.caption()) + Theme.SPACE_XS;
    }

    /// Whether each channel of `c` lies between those of `a` and `b`: a smoothed edge of `b` drawn over `a`.
    private static boolean between(final Color a, final Color b, final Color c) {
        return within(a.getRed(), b.getRed(), c.getRed()) && within(a.getGreen(), b.getGreen(), c.getGreen())
                && within(a.getBlue(), b.getBlue(), c.getBlue());
    }

    private static boolean within(final int a, final int b, final int c) {
        return c >= Math.min(a, b) - 1 && c <= Math.max(a, b) + 1;
    }
}
