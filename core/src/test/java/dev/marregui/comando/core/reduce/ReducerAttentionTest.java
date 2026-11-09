package dev.marregui.comando.core.reduce;

import java.util.List;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Attention;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static dev.marregui.comando.testkit.States.P1;
import static dev.marregui.comando.testkit.States.P2;
import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] keeps [SessionSpec#unread] (record 136): finished work the user has not looked at.
/// - a turn that ends while the focused panel is another session's marks the session, and the mark is saved
/// - a turn that ends on the focused panel's session marks nothing
/// - a new turn clears the mark, its outcome superseded; so does focus, by any intent that leaves it there
/// - a hand-off turn marks nothing; the return of its work does, and so does a hand-off that failed
/// - a session that dies at work marks; a result after the report does not
/// - an edit and a rename keep the mark
class ReducerAttentionTest {

    private final FakeClock clock = new FakeClock();
    private Reducer reducer = new Reducer(clock);
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(final Intent intent) {
        final Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    private SessionState work() {
        return state.session("work").orElseThrow();
    }

    private boolean unread() {
        return work().spec().unread();
    }

    /** A turn of {@code work} runs, the focus on the panel with no session. */
    private void turnRunsElsewhere() {
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        apply(new Intent.FocusPanel(P2));
    }

    private void turnEnds() {
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 10_000L, 200_000L, false, ""));
    }

    private void assertSavedAs(final boolean unread) {
        assertThat(effects).filteredOn(Effect.SaveSessions.class::isInstance)
                .singleElement(InstanceOfAssertFactories.type(Effect.SaveSessions.class))
                .satisfies(e -> assertThat(e.specs()).filteredOn(x -> x.name().equals("work"))
                        .extracting(SessionSpec::unread).containsExactly(unread));
    }

    /// The turn ends with the focus elsewhere: the session is marked, and the mark goes to `sessions.json`.
    @Test
    void aTurnThatEndsWhileAnotherPanelIsFocusedMarksTheSessionAndSavesIt() {
        turnRunsElsewhere();
        assertThat(unread()).as("a turn at work has no outcome yet").isFalse();
        turnEnds();
        assertThat(unread()).isTrue();
        assertThat(Attention.of(state, work(), clock.millis())).isEqualTo(Attention.TO_READ);
        assertSavedAs(true);
    }

    /// The user watches the turn end: nothing to read later, and nothing to save for it.
    @Test
    void aTurnThatEndsOnTheFocusedPanelsSessionMarksNothing() {
        apply(new Intent.RunText(P1, "go", Target.CLAUDE));
        apply(new Intent.SessionStarted("work"));
        turnEnds();
        assertThat(unread()).isFalse();
        assertThat(effects).noneMatch(Effect.SaveSessions.class::isInstance);
    }

    /// A new turn supersedes the outcome, whoever started it, and the file says so.
    @Test
    void aNewTurnClearsTheMark() {
        turnRunsElsewhere();
        turnEnds();
        apply(new Intent.SessionTurnRunning("work"));
        assertThat(unread()).isFalse();
        assertSavedAs(false);
    }

    /// Focusing the session's panel clears the mark and saves that.
    @Test
    void focusingThePanelClearsTheMark() {
        turnRunsElsewhere();
        turnEnds();
        apply(new Intent.FocusPanel(P1));
        assertThat(unread()).isFalse();
        assertSavedAs(false);
    }

    /// Opening the session from its row leaves the focus on its panel: the mark goes the same way.
    @Test
    void openingTheSessionClearsTheMark() {
        turnRunsElsewhere();
        turnEnds();
        apply(new Intent.OpenSession("work", P2, new PanelId("p3")));
        assertThat(state.focusedPanel().session()).isEqualTo("work");
        assertThat(unread()).isFalse();
    }

    /// The turn that hands work off is no outcome. The return marks the session at once, its mark held back for the
    /// reading turn, whose start clears it and whose end sets it; the result after that report finds it read.
    @Test
    void aHandOffMarksNothingAndTheReadingTurnsEndDoes() {
        final BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        turnRunsElsewhere();
        apply(new Intent.SessionTasksChanged("work", List.of(agent)));
        turnEnds();
        assertThat(unread()).as("handing work off finishes nothing").isFalse();

        clock.advance(60_000);
        apply(new Intent.SessionTasksChanged("work", List.of()));
        assertThat(unread()).isTrue();
        assertThat(Attention.of(state, work(), clock.millis() + 1_000)).as("its reading turn is to come")
                .isEqualTo(Attention.WORKING);
        assertThat(Attention.of(state, work(), clock.millis() + Attention.REPORT_GRACE_MILLIS))
                .as("no reading turn came").isEqualTo(Attention.TO_READ);

        apply(new Intent.SessionTurnRunning("work"));
        assertThat(unread()).isFalse();
        turnEnds();
        assertThat(unread()).isTrue();

        apply(new Intent.FocusPanel(P1));
        apply(new Intent.FocusPanel(P2));
        turnEnds();
        assertThat(unread()).as("the extra result after the report").isFalse();
    }

    /// Work handed off two turns ago is an outcome when it returns: the later turn was read, no reading turn starts.
    @Test
    void olderWorkThatReturnsAfterALaterTurnWasReadMarksTheSession() {
        final BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        turnRunsElsewhere();
        apply(new Intent.SessionTasksChanged("work", List.of(agent)));
        turnEnds();
        turnRunsElsewhere();
        turnEnds();
        apply(new Intent.FocusPanel(P1));
        apply(new Intent.FocusPanel(P2));
        assertThat(unread()).as("the later turn was read").isFalse();

        clock.advance(60_000);
        apply(new Intent.SessionTasksChanged("work", List.of()));

        assertThat(work().spec().unreadMillis()).isEqualTo(clock.millis());
        assertThat(Attention.of(state, work(), clock.millis() + Attention.REPORT_GRACE_MILLIS))
                .isEqualTo(Attention.TO_READ);
    }

    /// The mark says when the work finished, and a later finish while it stands moves it, in `sessions.json` too.
    @Test
    void theMarkKeepsWhenTheWorkFinished() {
        turnRunsElsewhere();
        turnEnds();
        assertThat(work().spec().unreadMillis()).isEqualTo(clock.millis());

        clock.advance(5_000);
        apply(new Intent.SessionTasksChanged("work",
                List.of(new BackgroundTask("a1", "local_agent", "review agent", 0L))));
        apply(new Intent.SessionTasksChanged("work", List.of()));

        assertThat(work().spec().unreadMillis()).isEqualTo(clock.millis());
        assertSavedAs(true);
    }

    /// The automatic checkpoint is one outcome: `/reflect` and `/compact` mark nothing, the carry-on's end does.
    @Test
    void theCheckpointChainMarksOnceAtTheCarryOnsEnd() {
        reducer = new Reducer(clock, () -> 40);
        turnRunsElsewhere();
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 400_000L, 1_000_000L, false, "", "at it"));
        assertThat(unread()).as("the checkpoint starts").isFalse();
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 420_000L, 1_000_000L, false, "", "## Reflected"));
        assertThat(unread()).as("then the compaction").isFalse();
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 420_000L, 1_000_000L, false, "", ""));
        assertThat(unread()).as("then the carry-on").isFalse();

        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 420_000L, 1_000_000L, false, "", "carried on"));

        assertThat(unread()).isTrue();
        assertSavedAs(true);
    }

    /// A hand-off turn that fails is an outcome though its work runs on.
    @Test
    void aHandOffTurnThatFailsMarksTheSession() {
        final BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        turnRunsElsewhere();
        apply(new Intent.SessionTasksChanged("work", List.of(agent)));
        apply(new Intent.SessionTurnEnded("work", 0, 0.1, 10_000L, 200_000L, true, "rate limited"));
        assertThat(work().lastTurnError()).isEqualTo("rate limited");
        assertThat(unread()).isTrue();
    }

    /// A session that dies at work leaves an outcome to read; one that stops idle leaves none.
    @Test
    void aSessionThatDiesAtWorkIsMarked() {
        turnRunsElsewhere();
        apply(new Intent.SessionExited("work", 1, "boom"));
        assertThat(unread()).isTrue();

        apply(new Intent.FocusPanel(P1));
        apply(new Intent.FocusPanel(P2));
        apply(new Intent.SessionExited("work", 0, ""));
        assertThat(unread()).isFalse();
    }

    /// An edit keeps the mark whatever spec the dialog sends, a rename carries it, and neither shows as a change that
    /// waits for the next start.
    @Test
    void anEditAndARenameKeepTheMark() {
        turnRunsElsewhere();
        turnEnds();
        final SessionSpec edited = work().spec().withUnread(0L);
        apply(new Intent.SaveSession("work", edited));
        assertThat(unread()).isTrue();
        assertThat(state.notice().text()).doesNotContain("next start");

        apply(new Intent.SessionExited("work", 0, ""));
        apply(new Intent.SaveSession("work", SessionSpec.fresh("play", "/tmp")));
        assertThat(state.session("play").orElseThrow().spec().unread()).isTrue();
    }
}
