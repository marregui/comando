package dev.marregui.comando.ui.shell;

import java.util.List;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.testkit.FakeResult;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// [LivePlots] reruns a panel's query on a period to keep its plot live.
/// - A live plot whose query fails stops, with one notice about its panel.
/// - A refresh with no rows keeps the plot live; a result with nothing to plot stops it, with a notice.
/// - A refresh period beyond a day is a day.
class LivePlotsTest {

    /// A query that failed once fails every period: on a state whose run for the panel failed, the plot is removed and
    /// one WARN notice says it stopped, with the panel as its subject.
    @Test
    void aLivePlotWhoseQueryFailsStops() throws Exception {
        final RecordingSink sink = new RecordingSink();
        SwingUtilities.invokeAndWait(() -> {
            final LivePlots plots = new LivePlots(sink, new ResultRegistry());
            plots.start(States.P1, "select * from gone", "live · one", 60);
            final AppState s = States.twoPanels().withRun(SqlRun.queued(5, States.P1, "local", "select * from gone").started(1)
                    .failed("ERROR: table does not exist", 2));
            plots.onState(s);
            assertThat(plots.size()).isZero();
            plots.stopAll();
        });
        final Intent.Notify n = sink.of(Intent.Notify.class).getFirst();
        assertThat(n.level()).isEqualTo(Notice.Level.WARN);
        assertThat(n.text()).startsWith("Live plot live · one stopped: its query failed");
        assertThat(n.subject()).isEqualTo(Notice.panel(States.P1.value()));
    }

    /// A refresh with no rows leaves the plot as it was, with no notice, and the refresh going; the next run with rows
    /// plots. A result with no numeric column besides x stops the plot with a notice that says so.
    @Test
    void aRefreshWithNoRowsKeepsThePlotLiveAndAResultWithNothingToPlotStopsIt() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final ResultRegistry results = new ResultRegistry();
        final String sql = "select ts, v from m";
        results.put(new FakeResult(5, List.of("ts", "v"), List.of(ColumnKind.TIMESTAMP, ColumnKind.DOUBLE), List.of()));
        results.put(new FakeResult(6, List.of("ts", "v"), List.of(ColumnKind.TIMESTAMP, ColumnKind.DOUBLE),
                List.of(List.<Object>of(1_000L, 1.5))));
        results.put(new FakeResult(7, List.of("ts", "name"), List.of(ColumnKind.TIMESTAMP, ColumnKind.TEXT),
                List.of(List.<Object>of(1_000L, "a"))));
        final AppState base = States.twoPanels();
        SwingUtilities.invokeAndWait(() -> {
            final LivePlots plots = new LivePlots(sink, results);
            plots.start(States.P1, sql, "live · one", 60);
            plots.onState(base.withRun(done(5, sql)));
            assertThat(plots.size()).as("no rows: still live").isOne();
            plots.onState(base.withRun(done(6, sql)));
            assertThat(plots.size()).isOne();
            plots.stopAll();
            final LivePlots names = new LivePlots(sink, results);
            names.start(States.P1, sql, "live · one", 60);
            names.onState(base.withRun(done(7, sql)));
            assertThat(names.size()).as("nothing to plot: stopped").isZero();
        });
        assertThat(sink.of(Intent.ShowView.class)).as("the run with rows plots").hasSize(1);
        assertThat(sink.of(Intent.Notify.class)).singleElement().satisfies(n -> {
            assertThat(n.level()).isEqualTo(Notice.Level.WARN);
            assertThat(n.text()).isEqualTo("Live plot live · one stopped: The result has no numeric column besides x");
        });
    }

    private static SqlRun done(final long requestId, final String sql) {
        return SqlRun.queued(requestId, States.P1, "local", sql).started(1).completed(1, 0, false, 1, 1, 2);
    }

    /// A period of 3 000 000 s is clamped to [LivePlots#MAX_SECONDS].
    @Test
    void aRefreshPeriodBeyondADayIsADay() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final LivePlots plots = new LivePlots(new RecordingSink(), new ResultRegistry());
            plots.start(States.P1, "select 1", "live · one", 3_000_000);
            assertThat(plots.periodMillis(States.P1)).isEqualTo(LivePlots.MAX_SECONDS * 1000);
            plots.stopAll();
        });
    }
}
