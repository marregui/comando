package dev.marregui.comando.core.state;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;

import static org.assertj.core.api.Assertions.assertThat;

/// [Attention] says what a session asks of the user, the first that applies.
/// - a waiting request outranks everything
/// - a turn, background work, and work just returned are working
/// - finished work not looked at is to read, and nothing else is
/// - a return shows its mark once its reading turn has had its grace; a failed hand-off's shows at once
class AttentionTest {

    private static SessionState open() {
        return SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
    }

    private static SessionState unread(final SessionState x) {
        return x.withSpec(x.spec().withUnread(1L));
    }

    private static AppState state(final SessionState x) {
        return AppState.initial(List.of(new PanelState(new PanelId("p"), "main", "", "")), List.of(), List.of(x));
    }

    private static Attention of(final SessionState x, final long nowMillis) {
        return Attention.of(state(x), x, nowMillis);
    }

    private static SessionState handedOff() {
        final BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        return open().withTurns(1, 1_000L).withTasks(List.of(agent), 1_500L).withTurns(0, 2_000L);
    }

    /// A request of the session makes it `NEEDS_YOU` whether it works or has something to read; another session's
    /// request does not.
    @Test
    void aWaitingRequestOutranksEverything() {
        final SessionState busy = unread(open()).withTurns(1, 1L);
        final Approval mine = new Approval(1L, "s", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L);
        final Approval theirs = new Approval(2L, "t", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L);
        assertThat(Attention.of(state(busy).withApprovals(List.of(mine)), busy, 2L)).isEqualTo(Attention.NEEDS_YOU);
        assertThat(Attention.of(state(busy).withApprovals(List.of(theirs)), busy, 2L)).isEqualTo(Attention.WORKING);
    }

    /// A running turn and background work are `WORKING`, a mark or not: the work is not over.
    @Test
    void aTurnAndBackgroundWorkAreWorking() {
        assertThat(of(open().withTurns(1, 1L), 2L)).isEqualTo(Attention.WORKING);
        assertThat(of(handedOff(), 3_000L)).isEqualTo(Attention.WORKING);
        assertThat(of(unread(handedOff()), 3_000L)).as("a later turn ended while older work runs on")
                .isEqualTo(Attention.WORKING);
    }

    /// With nothing going on, the mark decides: `TO_READ` with it, `IDLE` without.
    @Test
    void finishedWorkNotLookedAtIsToRead() {
        assertThat(of(unread(open()), 1L)).isEqualTo(Attention.TO_READ);
        assertThat(of(open(), 1L)).isEqualTo(Attention.IDLE);
    }

    /// Work that returned is `WORKING` through the grace, its mark held back; at its end the mark shows, with no state
    /// change to carry it.
    @Test
    void aReturnIsWorkingUntilItsGraceEnds() {
        final SessionState returned = unread(handedOff().withTasks(List.of(), 10_000L));
        final long end = 10_000L + Attention.REPORT_GRACE_MILLIS;
        assertThat(of(returned, end - 1)).isEqualTo(Attention.WORKING);
        assertThat(Attention.unread(returned, end - 1)).isFalse();
        assertThat(of(returned, end)).isEqualTo(Attention.TO_READ);
        assertThat(Attention.unread(returned, end)).isTrue();
    }

    /// A hand-off turn that failed is its own outcome: when its work returns nothing holds its mark back.
    @Test
    void aFailedHandOffIsToReadAsSoonAsItsWorkReturns() {
        final SessionState failed = unread(handedOff().withLastTurnError("rate limited").withTasks(List.of(), 10_000L));
        assertThat(of(failed, 10_001L)).isEqualTo(Attention.TO_READ);
        assertThat(Attention.unread(failed, 10_001L)).isTrue();
    }
}
