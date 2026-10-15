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
     * The status of {@code p}'s tab in {@code s}; {@code unread} says its last work ended while another tab was in front
     * and the user has not looked since.
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
        return session.isPresent() ? IDLE : NONE;
    }

    /** Whether work is going on, for the unread tracking: a change from true to false is an outcome to look at. */
    static boolean active(PanelState p, AppState s) {
        boolean session = !p.session().isEmpty() && s.session(p.session()).map(SessionState::isBusy).orElse(false);
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
                String what = session.filter(SessionState::isBusy).map(ss -> ss.activity().isEmpty() ? "working" : ss.activity())
                        .orElse("running");
                long since = session.map(SessionState::turnStartedMillis).orElse(0L);
                yield " · " + what + (since > 0 ? " " + Labels.elapsed(nowMillis - since) : "");
            }
            case IDLE, NONE -> "";
        };
    }
}
