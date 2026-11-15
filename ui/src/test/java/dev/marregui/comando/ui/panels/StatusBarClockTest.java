package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.PlanUsage;

import static org.assertj.core.api.Assertions.assertThat;

/// [StatusBar]'s clock: the time in UTC at the bar's right edge.
/// - it reads the time in UTC, whatever the machine's zone, with the local time in the tooltip
/// - it keeps its place and width while the text beside it grows, and as its own digits change
class StatusBarClockTest {

    /// 2026-11-15 11:24:05 UTC.
    private static final long AT_MILLIS = 1_763_205_845_000L;

    /// The text is UTC's time, whatever zone the machine is in.
    @Test
    void theClockReadsUtc() {
        assertThat(StatusBar.utcText(AT_MILLIS)).isEqualTo("11:24:05 UTC");
    }

    /// The clock's bounds in the bar stay the same after the usage label appears beside it and after its digits change,
    /// and sit at the bar's right edge.
    @Test
    void theClockKeepsItsPlaceWhileTheBarsTextGrows() throws Exception {
        final Rectangle[] bounds = new Rectangle[3];
        final String[] tip = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            final StatusBar bar = new StatusBar(_ -> { });
            bar.clock = () -> AT_MILLIS;
            bar.tick();
            bar.component().setSize(new Dimension(1200, 24));
            bar.component().doLayout();
            layoutAll(bar.component());
            final JLabel utc = bar.utcLabel();
            bounds[0] = SwingUtilities.convertRectangle(utc.getParent(), utc.getBounds(), bar.component());
            bar.planUsage(new PlanUsage(List.of(new PlanUsage.Window("Session", 5, "Oct 14 at 7:19pm (Europe/Madrid)"),
                    new PlanUsage.Window("Week", 43, "Oct 15 at 1:59pm (Europe/Madrid)")), AT_MILLIS, ""));
            layoutAll(bar.component());
            bounds[1] = SwingUtilities.convertRectangle(utc.getParent(), utc.getBounds(), bar.component());
            bar.clock = () -> AT_MILLIS + 1_000L * (60 * 37 + 51);
            bar.tick();
            layoutAll(bar.component());
            bounds[2] = SwingUtilities.convertRectangle(utc.getParent(), utc.getBounds(), bar.component());
            tip[0] = utc.getToolTipText();
            assertThat(utc.getText()).isEqualTo("12:01:56 UTC");
            assertThat(bar.usageLabel().isVisible()).as("the usage beside it is shown").isTrue();
        });
        assertThat(bounds[0].width).isPositive();
        assertThat(bounds[1]).as("after the usage label appears").isEqualTo(bounds[0]);
        assertThat(bounds[2]).as("after the digits change").isEqualTo(bounds[0]);
        assertThat(bounds[0].x + bounds[0].width).as("at the right edge, inside the padding").isGreaterThan(1200 - 40);
        assertThat(tip[0]).startsWith("UTC; local time ");
    }

    private static void layoutAll(final Container c) {
        c.doLayout();
        for (final Component k : c.getComponents()) {
            if (k instanceof Container x) {
                layoutAll(x);
            }
        }
    }
}
