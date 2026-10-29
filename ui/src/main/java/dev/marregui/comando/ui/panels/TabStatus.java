package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.ui.kit.format.Labels;

import java.util.Optional;

/**
 * What a panel's tab says about its work, so the user knows from the tab bar which panel to look at. One status per
 * tab, the first that applies: a session waiting for the user outranks everything, then an outcome not yet looked at,
 * then work in progress.
 */
enum TabStatus {
    /** The panel's session waits for an answer: an approval, a question, a plan. Stays until answered. */
    NEEDS_YOU,
    /** The last turn failed, or the session died, while the user looked elsewhere. Clears when the tab is looked at. */
    ERROR,
    /** A turn or query finished while the user looked elsewhere. Clears when the tab is looked at. */
    DONE,
    /** A turn runs, or a query. */
    WORKING,
    /** No turn runs, but background work the session started has not ended: a sub-agent, a shell command. */
    WAITING,
    /** A session is bound and has nothing going on. */
    IDLE,
    /** No session; the panel is text and perhaps a connection. */
    NONE;

    /** Whether the user should look at the tab. */
    boolean wantsAttention() {
        return this == NEEDS_YOU || this == ERROR;
    }

    /** Whether the title carries the mark. */
    boolean marked() {
        return this == NEEDS_YOU || this == ERROR || this == DONE;
    }

    /**
     * The status of {@code p}'s tab in {@code s}; {@code unread} says its last work ended while another tab was in
     * front and the user has not looked since.
     */
    static TabStatus of(PanelState p, AppState s, boolean unread) {
        Optional<SessionState> session = p.session().isEmpty() ? Optional.empty() : s.session(p.session());
        Optional<RunStatus> run = s.runOf(p.id()).map(SqlRun::status);
        if (session.isPresent() && s.pendingApprovals(session.get().name()) > 0) {
            return NEEDS_YOU;
        }
        if (unread && (session.map(TabStatus::failed).orElse(false) || run.map(r -> r == RunStatus.FAILED).orElse(false))) {
            return ERROR;
        }
        if (unread) {
            return DONE;
        }
        if (session.map(SessionState::isBusy).orElse(false) || run.map(RunStatus::isActive).orElse(false)) {
            return WORKING;
        }
        if (session.map(SessionState::isWaiting).orElse(false)) {
            return WAITING;
        }
        return session.isPresent() ? IDLE : NONE;
    }

    /**
     * How long after background work returns its reading turn may take to make its first model call before the work
     * counts as finished: a large context with a cold cache takes tens of seconds; a return that starts no turn is
     * rare.
     */
    static final long REPORT_GRACE_MILLIS = 60_000;

    /**
     * Whether work is going on at {@code nowMillis}, for the unread tracking: a change from true to false is an outcome
     * to look at. Waiting on the background work the last turn started is work going on, and so is the moment
     * background work returns until the turn that reads it starts (at most {@link #REPORT_GRACE_MILLIS}): the turn that
     * handed it off is not the outcome. A later turn that ends while older work runs on is, and so is a hand-off turn
     * that failed.
     */
    static boolean active(PanelState p, AppState s, long nowMillis) {
        boolean session = !p.session().isEmpty() && s.session(p.session()).map(x -> x.isBusy()
                || (x.isAwaitingHandOff() || x.isReturning(nowMillis, REPORT_GRACE_MILLIS)) && x.lastTurnError().isEmpty()).orElse(false);
        return session || s.runOf(p.id()).map(r -> r.status().isActive()).orElse(false);
    }

    private static boolean failed(SessionState ss) {
        return ss.status() == Status.FAILED || !ss.lastTurnError().isEmpty();
    }

    /** The tab's tooltip suffix, with the reason; empty for IDLE and NONE. */
    static String describe(PanelState p, AppState s, TabStatus status, long nowMillis) {
        Optional<SessionState> session = p.session().isEmpty() ? Optional.empty() : s.session(p.session());
        return switch (status) {
            case NEEDS_YOU -> {
                int n = s.pendingApprovals(session.orElseThrow().name());
                yield " · needs you: " + n + (n == 1 ? " request" : " requests");
            }
            case ERROR -> " · failed" + session.map(ss -> ss.status() == Status.FAILED ? ": " + ss.error() : ": " + ss.lastTurnError())
                    .filter(m -> m.length() > 2).orElse("");
            case DONE -> " · finished";
            case WORKING -> {
                String what = session.filter(SessionState::isBusy).map(TabStatus::working)
                        .orElse("running");
                long since = session.map(SessionState::turnStartedMillis).orElse(0L);
                yield " · " + what + (since > 0 ? " " + Labels.elapsed(nowMillis - since) : "");
            }
            case WAITING -> {
                SessionState ss = session.orElseThrow();
                long since = ss.waitingSinceMillis();
                yield " · " + waiting(ss) + (since > 0 ? " " + Labels.elapsed(nowMillis - since) : "");
            }
            case IDLE, NONE -> "";
        };
    }

    /** What a session in a turn does, for every place that shows it: its tool's activity, else "working". */
    static String working(SessionState ss) {
        return ss.activity().isEmpty() ? "working" : ss.activity();
    }

    /** What a waiting session waits on, for every place that shows it: "waiting on review agent". */
    static String waiting(SessionState ss) {
        return "waiting on " + ss.waitingFor();
    }
}
