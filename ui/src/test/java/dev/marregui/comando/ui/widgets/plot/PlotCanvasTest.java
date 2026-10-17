package dev.marregui.comando.ui.widgets.plot;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlotCanvasTest {

    private static final double T0 = 1_791_800_000_000d; // 2026-10-12, a whole hour in UTC

    @Test
    void preparingSortsEachSeriesByXAndFindsTheBoundsOfFinitePoints() {
        double[] x = {3, 1, 2, Double.NaN};
        PlotCanvas.Prepared p = PlotCanvas.prepare(new ViewSpec.Plot("x", "y", false, List.of(
                new ViewSpec.Series("a", x, new double[]{30, 10, 20, 99}),
                new ViewSpec.Series("b", x.clone(), new double[]{-3, -1, -2, 0}))));
        assertThat(p.series().get(0).x()).containsExactly(1, 2, 3, Double.NaN);
        assertThat(p.series().get(0).y()).containsExactly(10, 20, 30, 99);
        assertThat(p.series().get(1).y()).as("a series sharing the x is sorted the same way").containsExactly(-1, -2, -3, 0);
        assertThat(List.of(p.x0(), p.x1(), p.y0(), p.y1())).as("a point without a finite x counts for no bound")
                .containsExactly(1.0, 3.0, -3.0, 30.0);
        PlotCanvas.Prepared one = PlotCanvas.prepare(new ViewSpec.Plot("t", "y", true, List.of(new ViewSpec.Series("s", new double[]{T0}, new double[]{5}))));
        assertThat(List.of(one.x0(), one.x1(), one.y0(), one.y1())).as("one point gets room around it")
                .containsExactly(T0 - 30_000, T0 + 30_000, 4.0, 6.0);
    }

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
