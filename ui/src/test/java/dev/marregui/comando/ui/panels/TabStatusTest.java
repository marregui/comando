package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TabStatusTest {

    private static final PanelId P = new PanelId("p");
    private static final PanelState BOUND = new PanelState(P, "work", "", "s");
    private static final PanelState UNBOUND = new PanelState(P, "notes", "", "");

    private static AppState state(PanelState panel, SessionState session) {
        return AppState.initial(List.of(panel), List.of(), session == null ? List.of() : List.of(session));
    }

    private static SessionState open() {
        return SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
    }

    @Test
    void noSessionIsNoneAndAQuietSessionIsIdle() {
        assertThat(TabStatus.of(UNBOUND, state(UNBOUND, null), false)).isEqualTo(TabStatus.NONE);
        assertThat(TabStatus.of(BOUND, state(BOUND, open()), false)).isEqualTo(TabStatus.IDLE);
        assertThat(TabStatus.of(BOUND, state(BOUND, null), false)).as("a binding to a session that is gone").isEqualTo(TabStatus.NONE);
    }

    @Test
    void aRunningTurnWorksAndItsEndUnseenIsDone() {
        SessionState busy = open().withTurns(1, 1_000L).withActivity("Bash", 1_000L);
        assertThat(TabStatus.of(BOUND, state(BOUND, busy), false)).isEqualTo(TabStatus.WORKING);
        assertThat(TabStatus.describe(BOUND, state(BOUND, busy), TabStatus.WORKING, 13_000L)).isEqualTo(" · Bash 12 s");
        SessionState done = busy.withTurns(0, 2_000L);
        assertThat(TabStatus.of(BOUND, state(BOUND, done), true)).isEqualTo(TabStatus.DONE);
        assertThat(TabStatus.of(BOUND, state(BOUND, done), false)).as("looked at: nothing to say").isEqualTo(TabStatus.IDLE);
    }

    private static BackgroundTask task(String id, String description) {
        return new BackgroundTask(id, "local_agent", description, 0L);
    }

    @Test
    void backgroundWorkAfterTheTurnWaitsAndItsReturnIsNotYetAnOutcome() {
        SessionState waiting = open().withTurns(1, 1_000L).withTasks(List.of(task("a1", "review agent")), 1_000L).withTurns(0, 2_000L);
        assertThat(TabStatus.of(BOUND, state(BOUND, waiting), false)).isEqualTo(TabStatus.WAITING);
        assertThat(TabStatus.describe(BOUND, state(BOUND, waiting), TabStatus.WAITING, 91_000L)).isEqualTo(" · waiting on review agent 1 min 30 s");
        assertThat(TabStatus.active(BOUND, state(BOUND, waiting), 2_000L)).as("the turn that handed the work off is not the outcome").isTrue();
        assertThat(TabStatus.of(BOUND, state(BOUND, waiting.withTurns(1, 3_000L)), false)).as("a running turn outranks waiting")
                .isEqualTo(TabStatus.WORKING);

        // the work returns; Claude Code starts a turn to read it, which takes a moment
        SessionState returned = waiting.withTasks(List.of(), 10_000L);
        assertThat(TabStatus.active(BOUND, state(BOUND, returned), 11_000L)).as("returned, its reading turn not started yet").isTrue();
        assertThat(TabStatus.active(BOUND, state(BOUND, returned), 10_000L + TabStatus.REPORT_GRACE_MILLIS))
                .as("no reading turn within the grace: the work is finished").isFalse();
        SessionState reading = returned.withTurns(1, 11_500L);
        assertThat(TabStatus.active(BOUND, state(BOUND, reading), 11_500L)).isTrue();
        assertThat(reading.isReturning(11_500L, TabStatus.REPORT_GRACE_MILLIS)).as("the reading turn ends the return").isFalse();
        assertThat(TabStatus.active(BOUND, state(BOUND, reading.withTurns(0, 20_000L)), 20_000L)).isFalse();
    }

    @Test
    void aLaterTurnThatEndsWhileOlderWorkRunsOnIsAnOutcome() {
        SessionState handedOff = open().withTurns(1, 1_000L).withTasks(List.of(task("b1", "build")), 1_500L).withTurns(0, 2_000L);
        SessionState answered = handedOff.withTurns(1, 3_000L).withTurns(0, 4_000L);
        assertThat(TabStatus.active(BOUND, state(BOUND, answered), 4_000L)).as("the question's answer is the outcome").isFalse();
        assertThat(TabStatus.of(BOUND, state(BOUND, answered), true)).isEqualTo(TabStatus.DONE);
        assertThat(TabStatus.of(BOUND, state(BOUND, answered), false)).as("looked at: the build still runs").isEqualTo(TabStatus.WAITING);
    }

    @Test
    void aHandOffTurnThatFailsIsAnOutcomeWhileItsWorkRunsOn() {
        SessionState failed = open().withTurns(1, 1_000L).withTasks(List.of(task("a1", "review agent")), 1_500L).withTurns(0, 2_000L)
                .withLastTurnError("rate limited");
        assertThat(TabStatus.active(BOUND, state(BOUND, failed), 2_000L)).as("its failure is the outcome to look at").isFalse();
        assertThat(TabStatus.of(BOUND, state(BOUND, failed), true)).isEqualTo(TabStatus.ERROR);
        assertThat(TabStatus.of(BOUND, state(BOUND, failed), false)).as("looked at: the work still runs").isEqualTo(TabStatus.WAITING);
    }

    @Test
    void aFailedTurnOrADeadSessionUnseenIsAnError() {
        SessionState failed = open().withTurns(1, 1L).withTurns(0, 2L).withLastTurnError("rate limited");
        assertThat(TabStatus.of(BOUND, state(BOUND, failed), true)).isEqualTo(TabStatus.ERROR);
        assertThat(TabStatus.describe(BOUND, state(BOUND, failed), TabStatus.ERROR, 0L)).isEqualTo(" · failed: rate limited");
        assertThat(TabStatus.of(BOUND, state(BOUND, failed), false)).as("looked at: the header chip says the rest").isEqualTo(TabStatus.IDLE);
        SessionState dead = open().withStatus(Status.FAILED, "exit 1");
        assertThat(TabStatus.of(BOUND, state(BOUND, dead), true)).isEqualTo(TabStatus.ERROR);
        assertThat(TabStatus.describe(BOUND, state(BOUND, dead), TabStatus.ERROR, 0L)).isEqualTo(" · failed: exit 1");
    }

    @Test
    void aWaitingRequestOutranksEverythingWhetherOrNotTheTabWasLookedAt() {
        SessionState busy = open().withTurns(1, 1L);
        AppState asked = state(BOUND, busy).withApprovals(List.of(
                new Approval(1L, "s", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L),
                new Approval(2L, "s", ApprovalKind.QUESTION, "AskUserQuestion", "{}", "which?", 1L)));
        assertThat(TabStatus.of(BOUND, asked, false)).isEqualTo(TabStatus.NEEDS_YOU);
        assertThat(TabStatus.of(BOUND, asked, true)).isEqualTo(TabStatus.NEEDS_YOU);
        assertThat(TabStatus.describe(BOUND, asked, TabStatus.NEEDS_YOU, 0L)).isEqualTo(" · needs you: 2 requests");
        AppState other = state(BOUND, busy).withApprovals(List.of(new Approval(3L, "t", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        assertThat(TabStatus.of(BOUND, other, false)).as("another session's request is not this tab's").isEqualTo(TabStatus.WORKING);
    }

    @Test
    void marksAndAttention() {
        assertThat(TabStatus.NEEDS_YOU.marked()).isTrue();
        assertThat(TabStatus.ERROR.marked()).isTrue();
        assertThat(TabStatus.DONE.marked()).isTrue();
        assertThat(TabStatus.WORKING.marked()).isFalse();
        assertThat(TabStatus.NEEDS_YOU.wantsAttention()).isTrue();
        assertThat(TabStatus.ERROR.wantsAttention()).isTrue();
        assertThat(TabStatus.DONE.wantsAttention()).as("a finished turn does not bounce the Dock").isFalse();
    }
}
