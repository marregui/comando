package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

class LivePlotsTest {

    /** A query that failed once fails every period: the plot stops with one notice about its panel. */
    @Test
    void aLivePlotWhoseQueryFailsStops() throws Exception {
        RecordingSink sink = new RecordingSink();
        SwingUtilities.invokeAndWait(() -> {
            LivePlots plots = new LivePlots(sink, new ResultRegistry());
            plots.start(States.P1, "select * from gone", "live · one", 60);
            AppState s = States.twoPanels().withRun(SqlRun.queued(5, States.P1, "local", "select * from gone").started(1)
                    .failed("ERROR: table does not exist", 2));
            plots.onState(s);
            assertThat(plots.size()).isZero();
            plots.stopAll();
        });
        Intent.Notify n = sink.of(Intent.Notify.class).getFirst();
        assertThat(n.level()).isEqualTo(Notice.Level.WARN);
        assertThat(n.text()).startsWith("Live plot live · one stopped: its query failed");
        assertThat(n.subject()).isEqualTo(Notice.panel(States.P1.value()));
    }

    @Test
    void aRefreshPeriodBeyondADayIsADay() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LivePlots plots = new LivePlots(new RecordingSink(), new ResultRegistry());
            plots.start(States.P1, "select 1", "live · one", 3_000_000);
            assertThat(plots.periodMillis(States.P1)).isEqualTo(LivePlots.MAX_SECONDS * 1000);
            plots.stopAll();
        });
    }
}
