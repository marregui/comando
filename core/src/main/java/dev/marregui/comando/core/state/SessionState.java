package dev.marregui.comando.core.state;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;

import java.util.Objects;

/**
 * A configured session and its live status.
 *
 * @param pendingTurns   prompts sent and not yet answered by a {@code result}
 * @param costUsd        spend reported by Claude Code for this conversation
 * @param contextTokens  input tokens of the last turn (prompt plus cache), the context size
 * @param contextWindow  the model's context window reported with the last turn, 0 when unknown
 * @param activity       what the session is doing now ("requesting", "compacting", a tool name), empty when idle
 * @param branch         git branch of the working directory, empty when unknown
 */
public record SessionState(
        SessionSpec spec,
        Status status,
        String error,
        SessionInfo info,
        int pendingTurns,
        double costUsd,
        long contextTokens,
        long contextWindow,
        long lastActivityMillis,
        String activity,
        String branch) {

    public SessionState {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(branch, "branch");
        if (pendingTurns < 0) {
            throw new IllegalArgumentException("pendingTurns is negative");
        }
    }

    public static SessionState closed(SessionSpec spec) {
        return new SessionState(spec, Status.CLOSED, "", SessionInfo.NONE, 0, 0.0, 0L, 0L, 0L, "", "");
    }

    public String name() {
        return spec.name();
    }

    public boolean isBusy() {
        return pendingTurns > 0;
    }

    public SessionState withSpec(SessionSpec newSpec) {
        return new SessionState(newSpec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity, branch);
    }

    public SessionState withStatus(Status newStatus, String newError) {
        return new SessionState(spec, newStatus, newError, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity, branch);
    }

    public SessionState withInfo(SessionInfo newInfo, long at) {
        return new SessionState(spec, status, error, newInfo, pendingTurns, costUsd, contextTokens, contextWindow, at, activity, branch);
    }

    public SessionState withTurns(int pending, long at) {
        return new SessionState(spec, status, error, info, pending, costUsd, contextTokens, contextWindow, at, activity, branch);
    }

    public SessionState withUsage(double cost, long tokens, long window, long at) {
        return new SessionState(spec, status, error, info, pendingTurns, cost, tokens, window, at, activity, branch);
    }

    public SessionState withActivity(String newActivity, long at) {
        return new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, at, newActivity, branch);
    }

    public SessionState withBranch(String newBranch) {
        return new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity, newBranch);
    }
}
