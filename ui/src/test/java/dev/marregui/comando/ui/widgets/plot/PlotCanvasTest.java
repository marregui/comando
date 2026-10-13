package dev.marregui.comando.ui.widgets.plot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlotCanvasTest {

    private static final double T0 = 1_791_800_000_000d; // 2026-10-12, a whole hour in UTC

    @Test
    void timeTicksFallOnClockSteps() {
        double[] t = PlotCanvas.timeTicks(T0 + 1_234, T0 + 3_600_000, 8);
        assertThat(t).isNotEmpty();
        double step = t[1] - t[0];
        assertThat(step).isEqualTo(600_000d);
        for (double v : t) {
            assertThat(v % step).isZero();
        }
    }

    @Test
    void subSecondRangesGetDistinctLabels() {
        double[] t = PlotCanvas.timeTicks(T0, T0 + 800, 8);
        assertThat(t[1] - t[0]).isEqualTo(100d);
        assertThat(PlotCanvas.time(t[0], 800)).isNotEqualTo(PlotCanvas.time(t[1], 800)).endsWith(".000");
    }
}
