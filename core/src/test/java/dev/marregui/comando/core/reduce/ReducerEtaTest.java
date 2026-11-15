package dev.marregui.comando.core.reduce;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionEta;
import dev.marregui.comando.core.state.SessionEta.Run.Source;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] keeps a session's ETA, set by `session_eta`, and the run its session bar shows.
/// - each part is due its minutes after the intent, by the reducer's clock, which also stamps when it was set
/// - a part with no text or no minutes is cleared, and a later estimate replaces the earlier one whole
/// - the timebox's time given reads as today's `HH:mm` in UTC, yesterday's when still to come, an instant, or now
/// - a time given that does not read is refused, and leaves the estimate; an unknown session leaves the state
/// - a turn runs to the median of the last 20 turns, a declared task from the user's go to its box or goal, across turns
/// - the percent is the session's: kept between reports, 100 ends the run, one out of range is refused
class ReducerEtaTest {

    /// 2026-11-15 12:00:00 UTC.
    private static final long NOON_MILLIS = 1_794_744_000_000L;
    private static final long MINUTE_MILLIS = 60_000L;

    private final FakeClock clock = new FakeClock(NOON_MILLIS);
    private final Reducer reducer = new Reducer(clock);

    /// `work` estimates 5 minutes for `build` and 20 for `shipped`, in a 30-minute box given at 11:50; a minute later,
    /// `test` in 3 with no goal text and no box; then 0 minutes for both, which clears them. An estimate for a session
    /// that does not exist changes nothing.
    @Test
    void anEstimateIsDueItsMinutesFromNowAndTheNextReplacesIt() {
        AppState s = reducer.apply(States.twoPanels(), new Intent.SessionEta("work", " build ", 5, "shipped", 20, 30, "11:50", -1)).state();
        final SessionEta.Run box = new SessionEta.Run(NOON_MILLIS - 10 * MINUTE_MILLIS, NOON_MILLIS + 20 * MINUTE_MILLIS, Source.BOX, -1, 0L, 0L);
        assertThat(s.session("work").orElseThrow().eta()).isEqualTo(new SessionEta("build", NOON_MILLIS + 5 * MINUTE_MILLIS,
                "shipped", NOON_MILLIS + 20 * MINUTE_MILLIS, 30, NOON_MILLIS - 10 * MINUTE_MILLIS, NOON_MILLIS, box, List.of()));

        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, new Intent.SessionEta("work", "test", 3, "", 20, 0, "", -1)).state();
        assertThat(s.session("work").orElseThrow().eta()).as("no goal text, no box: both cleared")
                .isEqualTo(new SessionEta("test", NOON_MILLIS + 4 * MINUTE_MILLIS, "", 0L, 0, 0L, NOON_MILLIS + MINUTE_MILLIS,
                        box.endedAt(NOON_MILLIS + MINUTE_MILLIS), List.of()));

        s = reducer.apply(s, new Intent.SessionEta("work", "test", 0, "", 0, 0, "", -1)).state();
        assertThat(s.session("work").orElseThrow().eta().stepAtMillis()).as("0 minutes clears").isZero();

        final AppState before = s;
        assertThat(reducer.apply(before, new Intent.SessionEta("nobody", "x", 1, "", 0, 0, "", -1)).state()).isEqualTo(before);
    }

    /// At 12:00 UTC: "11:50" is today's, "12:30" yesterday's, an instant is itself, empty is now; "noonish" is refused
    /// with the reason, and the estimate stays as it was.
    @Test
    void theTimeTheBoxWasGivenReadsAsTheLastSuchTimeInUtc() {
        assertThat(given("11:50")).isEqualTo(NOON_MILLIS - 10 * MINUTE_MILLIS);
        assertThat(given("12:30")).as("still to come today: yesterday's").isEqualTo(NOON_MILLIS + 30 * MINUTE_MILLIS - 86_400_000L);
        assertThat(given("2026-11-15T11:00:00Z")).isEqualTo(NOON_MILLIS - 60 * MINUTE_MILLIS);
        assertThat(given("")).isEqualTo(NOON_MILLIS);

        final Intent.SessionEta bad = new Intent.SessionEta("work", "build", 5, "", 0, 30, "noonish", -1);
        assertThat(Reducer.refusal(States.twoPanels(), bad)).hasValueSatisfying(r -> assertThat(r).contains("noonish"));
        assertThat(reducer.apply(States.twoPanels(), bad).state()).isEqualTo(States.twoPanels());
    }

    /// A turn with no task runs from its start to the default deadline, five minutes with no turn behind it, and ends
    /// with the turn, whose length joins the history; the next turn starts a run of its own.
    @Test
    void aTurnWithNoTaskRunsToTheDefaultAndEndsWithTheTurn() {
        AppState s = turnStarts(States.twoPanels());
        assertThat(eta(s).run()).isEqualTo(new SessionEta.Run(NOON_MILLIS, NOON_MILLIS + SessionEta.DEFAULT_TURN_MILLIS, Source.DEFAULT, -1, 0L, 0L));

        clock.advance(2 * MINUTE_MILLIS);
        s = turnEnds(s);
        assertThat(eta(s).run().endedMillis()).as("ended with the turn").isEqualTo(NOON_MILLIS + 2 * MINUTE_MILLIS);
        assertThat(eta(s).turnMillis()).containsExactly(2 * MINUTE_MILLIS);

        clock.advance(MINUTE_MILLIS);
        s = turnStarts(s);
        assertThat(eta(s).run()).as("a new run, due the median of the history")
                .isEqualTo(new SessionEta.Run(NOON_MILLIS + 3 * MINUTE_MILLIS, NOON_MILLIS + 5 * MINUTE_MILLIS, Source.DEFAULT, -1, 0L, 0L));
    }

    /// 21 turns of 1 to 21 minutes keep the last 20 (2 to 21), and the next turn is due their upper median, 12 minutes.
    @Test
    void theHistoryKeepsTheLastTwentyTurnsAndTheNextIsDueTheirMedian() {
        AppState s = States.twoPanels();
        for (int minutes = 1; minutes <= 21; minutes++) {
            s = turnStarts(s);
            clock.advance(minutes * MINUTE_MILLIS);
            s = turnEnds(s);
        }
        assertThat(eta(s).turnMillis()).hasSize(SessionEta.TURN_HISTORY).first().isEqualTo(2 * MINUTE_MILLIS);
        assertThat(eta(s).turnMillis().getLast()).isEqualTo(21 * MINUTE_MILLIS);

        final long startMillis = clock.millis();
        s = turnStarts(s);
        assertThat(eta(s).run().deadlineMillis() - startMillis).isEqualTo(12 * MINUTE_MILLIS);
    }

    /// A 30-minute box given at 11:50, declared in a turn at 12:00, runs from 11:50 to 12:20 and spans turns: the turn's
    /// end and the next turn's start leave it running, and the next estimate keeps the history.
    @Test
    void aTaskWithABoxRunsFromTheUsersGoAcrossTurns() {
        AppState s = turnStarts(States.twoPanels());
        s = reducer.apply(s, estimate("shipped", 20, 30, "11:50", -1)).state();
        final SessionEta.Run box = new SessionEta.Run(NOON_MILLIS - 10 * MINUTE_MILLIS, NOON_MILLIS + 20 * MINUTE_MILLIS, Source.BOX, -1, 0L, 0L);
        assertThat(eta(s).run()).isEqualTo(box);

        clock.advance(MINUTE_MILLIS);
        s = turnEnds(s);
        assertThat(eta(s).run()).as("a task's run outlives the turn").isEqualTo(box);
        assertThat(eta(s).turnMillis()).containsExactly(MINUTE_MILLIS);

        clock.advance(MINUTE_MILLIS);
        s = turnStarts(s);
        assertThat(eta(s).run()).as("the next turn keeps the task's run").isEqualTo(box);

        s = reducer.apply(s, estimate("shipped", 20, 30, "11:50", -1)).state();
        assertThat(eta(s).turnMillis()).as("an estimate keeps the history").containsExactly(MINUTE_MILLIS);
    }

    /// A goal with no box, declared three minutes into a turn, runs from the turn's start to the goal's ETA, also when the
    /// turn's own run has already ended (at 100 %); with no turn under way it runs from now.
    @Test
    void aTaskWithNoBoxRunsFromTheTurnsStartToTheGoal() {
        AppState s = turnStarts(States.twoPanels());
        clock.advance(3 * MINUTE_MILLIS);
        s = reducer.apply(s, estimate("shipped", 20, 0, "", -1)).state();
        assertThat(eta(s).run()).isEqualTo(new SessionEta.Run(NOON_MILLIS, NOON_MILLIS + 23 * MINUTE_MILLIS, Source.ESTIMATE, -1, 0L, 0L));

        final long turnMillis = clock.millis();
        AppState done = turnStarts(States.twoPanels());
        clock.advance(MINUTE_MILLIS);
        done = reducer.apply(done, estimate("", 0, 0, "", 100)).state();
        assertThat(eta(done).run().running()).as("the turn's run ended").isFalse();
        done = reducer.apply(done, estimate("shipped", 20, 0, "", -1)).state();
        assertThat(eta(done).run().startedMillis()).as("from the turn's start").isEqualTo(turnMillis);

        final AppState idle = reducer.apply(States.twoPanels(), estimate("shipped", 20, 0, "", -1)).state();
        assertThat(eta(idle).run()).isEqualTo(new SessionEta.Run(clock.millis(), clock.millis() + 20 * MINUTE_MILLIS, Source.ESTIMATE, -1, 0L, 0L));
    }

    /// The task reports 40 %; a later estimate without a percent keeps 40 % and when it was said, one with 70 % moves
    /// it, and 100 % ends the run.
    @Test
    void thePercentIsKeptAcrossReportsAndOneHundredEndsTheRun() {
        AppState s = reducer.apply(States.twoPanels(), estimate("shipped", 20, 0, "", 40)).state();
        assertThat(eta(s).run().percent()).isEqualTo(40);
        assertThat(eta(s).run().percentAtMillis()).isEqualTo(NOON_MILLIS);

        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, estimate("shipped", 15, 0, "", -1)).state();
        assertThat(eta(s).run().percent()).as("kept").isEqualTo(40);
        assertThat(eta(s).run().percentAtMillis()).as("with its time").isEqualTo(NOON_MILLIS);
        assertThat(eta(s).run().startedMillis()).as("the task's start stays").isEqualTo(NOON_MILLIS);

        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, estimate("shipped", 10, 0, "", 70)).state();
        assertThat(eta(s).run().percent()).isEqualTo(70);
        assertThat(eta(s).run().percentAtMillis()).isEqualTo(NOON_MILLIS + 2 * MINUTE_MILLIS);
        assertThat(eta(s).run().running()).isTrue();

        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, estimate("shipped", 10, 0, "", 100)).state();
        assertThat(eta(s).run().endedMillis()).isEqualTo(NOON_MILLIS + 3 * MINUTE_MILLIS);
        assertThat(eta(s).run().percent()).isEqualTo(100);
    }

    /// Clearing the goal and the box ends the task's run at once; a turn's own run is not a task's, so clearing leaves it
    /// running, takes its percent, and 100 % ends it.
    @Test
    void clearingTheTaskEndsItsRunButNotATurns() {
        AppState s = reducer.apply(States.twoPanels(), estimate("shipped", 20, 30, "", -1)).state();
        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, estimate("", 0, 0, "", -1)).state();
        assertThat(eta(s).run().endedMillis()).isEqualTo(NOON_MILLIS + MINUTE_MILLIS);
        assertThat(eta(s).run().source()).isEqualTo(Source.BOX);

        AppState turn = turnStarts(States.twoPanels());
        turn = reducer.apply(turn, estimate("", 0, 0, "", 50)).state();
        assertThat(eta(turn).run().running()).isTrue();
        assertThat(eta(turn).run().percent()).isEqualTo(50);
        turn = reducer.apply(turn, estimate("", 0, 0, "", 100)).state();
        assertThat(eta(turn).run().endedMillis()).isEqualTo(clock.millis());
    }

    /// A task never ended, `shipped` at 80 %, then a new go on another goal: the new task starts at the new turn's
    /// start with no percent, not the old task's start and report.
    @Test
    void aNewTaskStartsFreshAndTakesNoPercentFromTheLastOne() {
        AppState s = reducer.apply(States.twoPanels(), estimate("shipped", 20, 0, "", 80)).state();
        clock.advance(45 * MINUTE_MILLIS);
        s = turnStarts(s);
        s = reducer.apply(s, estimate("released", 30, 0, "", -1)).state();
        assertThat(eta(s).run().startedMillis()).as("the new turn's start").isEqualTo(NOON_MILLIS + 45 * MINUTE_MILLIS);
        assertThat(eta(s).run().percent()).as("no percent yet").isEqualTo(-1);

        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, estimate("", 0, 30, "", -1)).state();
        assertThat(eta(s).run().startedMillis()).as("a box with no time: given now, a new task")
                .isEqualTo(NOON_MILLIS + 46 * MINUTE_MILLIS);
    }

    /// A call with only a percent reports progress on the running task: the run, its goal and its box go on, and the
    /// next turn keeps the task's run.
    @Test
    void aProgressOnlyCallKeepsTheTaskRunning() {
        AppState s = reducer.apply(States.twoPanels(), estimate("shipped", 20, 30, "", -1)).state();
        clock.advance(MINUTE_MILLIS);
        s = reducer.apply(s, estimate("", 0, 0, "", 60)).state();
        assertThat(eta(s).run().running()).isTrue();
        assertThat(eta(s).run().percent()).isEqualTo(60);
        assertThat(eta(s).boxMinutes()).as("the box stays").isEqualTo(30);
        assertThat(eta(s).goal()).as("the goal stays").isEqualTo("shipped");
        s = turnStarts(s);
        assertThat(eta(s).run().source()).as("the next turn keeps the task").isEqualTo(Source.BOX);
    }

    /// A phase call repeating the 30-minute box with no time keeps the go's time, so the task's start stays at the go.
    @Test
    void aBoxRepeatedWithNoTimeKeepsTheGo() {
        AppState s = reducer.apply(States.twoPanels(), estimate("shipped", 20, 30, "11:50", -1)).state();
        clock.advance(5 * MINUTE_MILLIS);
        s = reducer.apply(s, estimate("shipped", 15, 30, "", 30)).state();
        assertThat(eta(s).run().startedMillis()).isEqualTo(NOON_MILLIS - 10 * MINUTE_MILLIS);
        assertThat(eta(s).boxGivenAtMillis()).isEqualTo(NOON_MILLIS - 10 * MINUTE_MILLIS);
        assertThat(eta(s).run().percent()).isEqualTo(30);
    }

    /// A percent of 101 or -2 is refused and leaves the state; -1 (none given), 0 and 100 are not.
    @Test
    void aPercentOutOfRangeIsRefused() {
        assertThat(Reducer.refusal(States.twoPanels(), estimate("shipped", 20, 0, "", 101))).isPresent();
        assertThat(Reducer.refusal(States.twoPanels(), estimate("shipped", 20, 0, "", -2))).isPresent();
        assertThat(reducer.apply(States.twoPanels(), estimate("shipped", 20, 0, "", 101)).state()).isEqualTo(States.twoPanels());
        for (final int ok : new int[] {-1, 0, 100}) {
            assertThat(Reducer.refusal(States.twoPanels(), estimate("shipped", 20, 0, "", ok))).as("percent=%d", ok).isEmpty();
        }
    }

    private static Intent.SessionEta estimate(final String goal, final int goalMinutes, final int boxMinutes, final String boxGiven,
                                              final int percent) {
        return new Intent.SessionEta("work", "", 0, goal, goalMinutes, boxMinutes, boxGiven, percent);
    }

    private AppState turnStarts(final AppState s) {
        return reducer.apply(s, new Intent.SessionTurnRunning("work")).state();
    }

    private AppState turnEnds(final AppState s) {
        return reducer.apply(s, new Intent.SessionTurnEnded("work", 0, -1, -1, 0, false, "")).state();
    }

    private static SessionEta eta(final AppState s) {
        return s.session("work").orElseThrow().eta();
    }

    private long given(final String text) {
        return reducer.apply(States.twoPanels(), new Intent.SessionEta("work", "", 0, "", 0, 30, text, -1)).state()
                .session("work").orElseThrow().eta().boxGivenAtMillis();
    }
}
