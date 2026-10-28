package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.result.ColumnKind;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeResult;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ResultViews] turns a SQL result into a plot or a table view; [LivePlots] keeps such a plot live.
/// - A time column is the x axis and numeric columns are series, nulls as NaN.
/// - A result with no numeric or time column, or no result, cannot be plotted.
/// - A table keeps the formatted cells.
/// - A live plot reruns and replots its own query until its tab closes, and stops when the user runs another.
class ResultViewsTest {

    private static FakeResult result(long id) {
        return new FakeResult(id, List.of("ts", "name", "price", "qty"),
                List.of(ColumnKind.TIMESTAMP, ColumnKind.TEXT, ColumnKind.DOUBLE, ColumnKind.LONG),
                List.of(Arrays.asList(1_000_000L, "a", 1.5, 3L), Arrays.asList(2_000_000L, "b", null, 4L)));
    }

    /// [ResultViews#plot] uses the timestamp column as a time x axis in milliseconds, plots the double and long columns as
    /// series, and turns a null into NaN.
    @Test
    void timeIsXAndNumericColumnsAreSeries() {
        ViewSpec.Plot p = ResultViews.plot(result(1));
        assertThat(p.timeX()).isTrue();
        assertThat(p.xLabel()).isEqualTo("ts");
        assertThat(p.series()).extracting(ViewSpec.Series::name).containsExactly("price", "qty");
        assertThat(p.series().getFirst().x()).containsExactly(1000.0, 2000.0);
        assertThat(Double.isNaN(p.series().getFirst().y()[1])).isTrue();
    }

    /// A text-only result fails naming `no numeric or time column`, and a null result fails naming `no rows`.
    @Test
    void textOnlyResultsCannotBePlotted() {
        FakeResult text = new FakeResult(1, List.of("s"), List.of(ColumnKind.TEXT), List.of(List.of("x")));
        assertThatThrownBy(() -> ResultViews.plot(text)).hasMessageContaining("no numeric or time column");
        assertThatThrownBy(() -> ResultViews.plot(null)).hasMessageContaining("no rows");
    }

    /// [ResultViews#table] keeps the column names and formats a timestamp in microseconds and a null as `null`.
    @Test
    void tableKeepsFormattedCells() {
        ViewSpec.Table t = ResultViews.table(result(1));
        assertThat(t.columns()).containsExactly("ts", "name", "price", "qty");
        assertThat(t.rows().get(1)).containsExactly("1970-01-01T00:00:02.000000Z", "b", "null", "4");
    }

    /// A completed run of the plot's query shows the view under its title; a run of another query while the tab is open is
    /// not plotted, and once the tab closes the live plot stops, never running anything but SQL.
    @Test
    void livePlotRerunsAndReplotsItsQueryUntilItsTabCloses() {
        RecordingSink sink = new RecordingSink();
        ResultRegistry results = new ResultRegistry();
        LivePlots live = new LivePlots(sink, results);
        AppState s = States.twoPanels();
        live.start(States.P1, "SELECT * FROM t", "live · one", 60);

        results.put(result(5));
        AppState done = s.withRun(SqlRun.queued(5L, States.P1, "local", "SELECT * FROM t").completed(2, -1, false, 1, 1, 2));
        live.onState(done);
        Intent.ShowView shown = sink.of(Intent.ShowView.class).getLast();
        assertThat(shown.title()).isEqualTo("live · one");

        // the plot's tab is open; a run of another query in the panel is not plotted
        List<ViewState> views = new ArrayList<>(List.of(new ViewState(9L, "live · one", shown.spec(), Placement.LOWER, false, "", 1L)));
        int before = sink.of(Intent.ShowView.class).size();
        live.onState(s.withViews(views).withRun(SqlRun.queued(6L, States.P1, "local", "SELECT 1").completed(1, -1, false, 1, 1, 2)));
        assertThat(sink.of(Intent.ShowView.class)).hasSize(before);
        assertThat(live.size()).isEqualTo(1);

        // the tab is closed: the live plot stops
        live.onState(done.withViews(List.of()));
        assertThat(live.size()).isZero();
        assertThat(sink.of(Intent.RunText.class)).noneMatch(r -> r.target() != Target.SQL);
    }

    /// A tick after its own query completed sends RefreshSql for it; after the user runs another statement in the panel
    /// the next tick sends nothing, the plot stops and a notice says another statement ran.
    @Test
    void aLivePlotRefreshesItsOwnQueryAndStopsWhenTheUserRunsAnother() {
        RecordingSink sink = new RecordingSink();
        LivePlots live = new LivePlots(sink, new ResultRegistry());
        AppState s = States.twoPanels();
        live.start(States.P1, "SELECT * FROM t", "live · one", 60);

        live.onState(s.withRun(SqlRun.queued(5L, States.P1, "local", "SELECT * FROM t").completed(2, -1, false, 1, 1, 2)));
        live.tickAll();
        assertThat(sink.of(Intent.RefreshSql.class)).containsExactly(new Intent.RefreshSql(States.P1, "SELECT * FROM t"));

        live.onState(s.withRun(SqlRun.queued(6L, States.P1, "local", "SELECT 1").completed(1, -1, false, 1, 1, 2)));
        live.tickAll();
        assertThat(sink.of(Intent.RefreshSql.class)).hasSize(1);
        assertThat(live.size()).isZero();
        assertThat(sink.of(Intent.Notify.class).getLast().text()).contains("ran another statement");
    }
}
