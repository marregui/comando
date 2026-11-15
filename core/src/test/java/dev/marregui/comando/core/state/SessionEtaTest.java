package dev.marregui.comando.core.state;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.SessionEta.Run.Source;

import static org.assertj.core.api.Assertions.assertThat;

/// [SessionEta] and the run [SessionState#withTurns] keeps in it.
/// - the turn history holds the last 20 lengths; the default deadline is their upper median, at least a minute
/// - a busy stretch that starts begins a default run, unless the declared task still runs
/// - one that ends records its length and ends a default run, never a task's
class SessionEtaTest {

    private static final long MINUTE_MILLIS = 60_000L;

    private static SessionState session() {
        return SessionState.closed(SessionSpec.fresh("s", "/tmp"));
    }

    /// No history: five minutes. Quick turns: never below a minute. Four turns: the upper of the two middle ones.
    @Test
    void theDefaultIsTheUpperMedianOfTheHistoryAndAtLeastAMinute() {
        assertThat(SessionEta.NONE.defaultTurnMillis()).isEqualTo(SessionEta.DEFAULT_TURN_MILLIS);
        assertThat(SessionEta.NONE.withTurn(1_000L).withTurn(2_000L).defaultTurnMillis()).isEqualTo(MINUTE_MILLIS);
        final SessionEta four = SessionEta.NONE.withTurn(4 * MINUTE_MILLIS).withTurn(MINUTE_MILLIS).withTurn(3 * MINUTE_MILLIS)
                .withTurn(2 * MINUTE_MILLIS);
        assertThat(four.defaultTurnMillis()).isEqualTo(3 * MINUTE_MILLIS);
    }

    /// The 21st turn drops the first.
    @Test
    void theHistoryKeepsTheLastTwenty() {
        SessionEta eta = SessionEta.NONE;
        for (long i = 1; i <= SessionEta.TURN_HISTORY + 1; i++) {
            eta = eta.withTurn(i);
        }
        assertThat(eta.turnMillis()).hasSize(SessionEta.TURN_HISTORY).first().isEqualTo(2L);
    }

    /// A turn from 1 000 to 61 000 runs to the default and ends with the turn, its 60 s in the history; a second
    /// prompt queued in the stretch does not restart it.
    @Test
    void aBusyStretchStartsAndEndsADefaultRun() {
        SessionState x = session().withTurns(1, 1_000L);
        final SessionEta.Run run = new SessionEta.Run(1_000L, 1_000L + SessionEta.DEFAULT_TURN_MILLIS, Source.DEFAULT, -1, 0L, 0L);
        assertThat(x.eta().run()).isEqualTo(run);
        x = x.withTurns(2, 30_000L);
        assertThat(x.eta().run()).as("a queued prompt is the same stretch").isEqualTo(run);
        x = x.withTurns(0, 61_000L);
        assertThat(x.eta().run()).isEqualTo(run.endedAt(61_000L));
        assertThat(x.eta().turnMillis()).containsExactly(60_000L);
    }

    /// A running task's run survives the stretch's end and the next start; an ended one gives way to a default run.
    @Test
    void aRunningTaskSpansTurns() {
        final SessionEta.Run task = new SessionEta.Run(500L, 500L + 30 * MINUTE_MILLIS, Source.BOX, 10, 600L, 0L);
        final SessionEta declared = new SessionEta("", 0L, "", 0L, 30, 500L, 600L, task, List.of());
        SessionState x = session().withEta(declared).withTurns(1, 1_000L);
        assertThat(x.eta().run()).isEqualTo(task);
        x = x.withTurns(0, 2_000L);
        assertThat(x.eta().run()).isEqualTo(task);
        assertThat(x.eta().turnMillis()).containsExactly(1_000L);

        final SessionState ended = session().withEta(declared.withRun(task.endedAt(800L))).withTurns(1, 1_000L);
        assertThat(ended.eta().run().source()).isEqualTo(Source.DEFAULT);
        assertThat(ended.eta().run().startedMillis()).isEqualTo(1_000L);
    }

    /// An intent log written before the run existed reads `run` and `turnMillis` as absent, null: they become no run
    /// and no turns, so replaying it does not fail.
    @Test
    void anOldEstimateWithoutARunReadsAsNoRunAndNoTurns() {
        final SessionEta old = new SessionEta("build", 1L, "shipped", 2L, 30, 3L, 4L, null, null);
        assertThat(old.run()).isEqualTo(SessionEta.Run.NONE);
        assertThat(old.turnMillis()).isEmpty();
    }
}
