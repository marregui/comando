package dev.marregui.comando.ui.widgets.plot;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [PlotCanvas] prepares series for drawing and places time ticks.
/// - preparing sorts each series by x and bounds only the finite points
/// - a single point gets room around it
/// - time ticks fall on clock steps, and sub-second ranges get distinct labels
/// - a point far outside the view keeps its side of the plot
class PlotCanvasTest {

    private static final double T0 = 1_791_800_000_000d; // 2026-10-12, a whole hour in UTC

    /// Each series is sorted by x with a NaN x last, a series sharing the x array sorts the same way, and a point
    /// without a finite x counts for no bound; one time point gets ±30 s and ±1 around it.
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

    /// Ticks over an hour starting off a step are ten minutes apart and each a whole multiple of the step.
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

    /// An 800 ms range gets 100 ms ticks whose labels differ and carry milliseconds.
    @Test
    void subSecondRangesGetDistinctLabels() {
        double[] t = PlotCanvas.timeTicks(T0, T0 + 800, 8);
        assertThat(t[1] - t[0]).isEqualTo(100d);
        assertThat(PlotCanvas.time(t[0], 800)).isNotEqualTo(PlotCanvas.time(t[1], 800)).endsWith(".000");
    }

    /// A view zoomed to a flat stretch has its y range padded to ±1e-9, so the next point past its edge, one step up
    /// (to 7) or down (to 3), lies billions of pixels away: its pixel y stays above the plot's top or below its bottom,
    /// where an unclamped narrowing to int wraps it to the other side.
    @Test
    void aPointFarOutsideAFlatViewKeepsItsSide() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final double[] x = {0, 1, 2, 3, 4, 5, 6, 7, 8};
            final PlotCanvas canvas = new PlotCanvas();
            canvas.setData(PlotCanvas.prepare(new ViewSpec.Plot("x", "y", false, List.of(
                    new ViewSpec.Series("up", x, new double[]{5, 5, 5, 5, 5, 5, 5, 5, 7}),
                    new ViewSpec.Series("down", x.clone(), new double[]{5, 5, 5, 5, 5, 5, 5, 5, 3})))));
            canvas.zoomTo(2, 7.5);
            final Rectangle area = new Rectangle(70, 26, 710, 432);
            assertThat(canvas.py(5, area)).as("the flat stretch is inside the plot")
                    .isBetween(area.y, area.y + area.height);
            assertThat(canvas.py(7, area)).as("a step up is above the top").isLessThan(area.y);
            assertThat(canvas.py(3, area)).as("a step down is below the bottom").isGreaterThan(area.y + area.height);
        });
    }
}
