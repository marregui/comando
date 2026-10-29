package dev.marregui.comando.core.state;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A configured session and its live status.
 *
 * @param pendingTurns      prompts sent and not yet answered by a {@code result}
 * @param costUsd           spend Claude Code reports for this process
 * @param contextTokens     the context size: input tokens, cache included, of the latest model call or turn
 * @param contextWindow     the model's context window as last reported, 0 when unknown
 * @param activity          what the session is doing now ("requesting", "compacting", a tool name), empty when idle
 * @param branch            git branch of the working directory, empty when unknown
 * @param turnStartedMillis when the running turn began (the first prompt of a busy stretch), 0 when idle
 * @param lastTurnError     why the last turn failed, empty when it finished, was interrupted, or a new turn has started
 * @param tasks             background work the session started and Claude Code reports still open, oldest first
 * @param returnedMillis    when the last background task ended while no turn ran, 0 otherwise: Claude Code starts a
 *                          turn of its own to read the result, and until it does the work is not finished
 * @param handedOff         the last turn added background work that is still open: that turn's outcome is the work's,
 *                          still to come
 * @param startedMillis     when the session's process started, 0 while it does not run
 * @param reflection        whether the session is due its checkpoint
 * @param checkpointClears  the Checkpoint button's command runs: when its turn ends, the conversation is cleared and
 *                          the turn's last answer, its summary, is carried into the next prompt
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
        String branch,
        long turnStartedMillis,
        String lastTurnError,
        List<BackgroundTask> tasks,
        long returnedMillis,
        boolean handedOff,
        long startedMillis,
        Reflection reflection,
        boolean checkpointClears) {

    public SessionState {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(lastTurnError, "lastTurnError");
        Objects.requireNonNull(reflection, "reflection");
        tasks = List.copyOf(tasks);
        if (pendingTurns < 0) {
            throw new IllegalArgumentException("pendingTurns is negative");
        }
    }

    public static SessionState closed(SessionSpec spec) {
        return new SessionState(spec, Status.CLOSED, "", SessionInfo.NONE, 0, 0.0, 0L, 0L, 0L, "", "", 0L, "", List.of(), 0L, false, 0L, Reflection.FRESH, false);
    }

    public String name() {
        return spec.name();
    }

    public boolean isBusy() {
        return pendingTurns > 0;
    }

    /** No turn runs, but background work the session started has not ended: its answer is still to come. */
    public boolean isWaiting() {
        return pendingTurns == 0 && !tasks.isEmpty();
    }

    /**
     * Waiting, and the last turn is what started that work: its outcome is the work's. A later turn that ends while
     * older work runs on is an outcome of its own.
     */
    public boolean isAwaitingHandOff() {
        return isWaiting() && handedOff;
    }

    /** What the session waits on: the task's description when there is one, else how many tasks; empty when none. */
    public String waitingFor() {
        if (tasks.isEmpty()) {
            return "";
        }
        if (tasks.size() == 1) {
            String d = tasks.getFirst().description();
            return d.isBlank() ? "1 background task" : d;
        }
        return tasks.size() + " background tasks";
    }

    /**
     * The background work came back less than {@code graceMillis} ago and the turn that reads it has not started: the
     * work is not finished yet. Bounded, so a return that starts no turn does not keep the session going.
     */
    public boolean isReturning(long nowMillis, long graceMillis) {
        return returnedMillis > 0L && pendingTurns == 0 && nowMillis - returnedMillis < graceMillis;
    }

    /** When the oldest open background task started, 0 when none. */
    public long waitingSinceMillis() {
        return tasks.isEmpty() ? 0L : tasks.getFirst().startedMillis();
    }

    /**
     * The background tasks Claude Code reports open now, the whole set; a task already known keeps the time comando
     * first heard of it, a new one starts {@code now}. When the last one ends while no turn runs, the work has
     * returned.
     */
    public SessionState withTasks(List<BackgroundTask> open, long now) {
        List<BackgroundTask> next = new ArrayList<>(open.size());
        for (BackgroundTask t : open) {
            long started = tasks.stream().filter(k -> k.id().equals(t.id())).mapToLong(BackgroundTask::startedMillis).findFirst().orElse(now);
            next.add(new BackgroundTask(t.id(), t.kind(), t.description(), started));
        }
        if (next.equals(tasks)) {
            return this;
        }
        long returned = !tasks.isEmpty() && next.isEmpty() && !isBusy() ? now : next.isEmpty() ? returnedMillis : 0L;
        boolean added = next.stream().anyMatch(t -> tasks.stream().noneMatch(k -> k.id().equals(t.id())));
        boolean handed = !next.isEmpty() && (handedOff || added && isBusy());
        return new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity,
                branch, turnStartedMillis, lastTurnError, next, returned, handed, startedMillis, reflection, checkpointClears);
    }

    /** No background work: the session's process ended, and its tasks with it. */
    public SessionState withoutTasks() {
        return tasks.isEmpty() && returnedMillis == 0L && !handedOff ? this
                : new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity,
                branch, turnStartedMillis, lastTurnError, List.of(), 0L, false, startedMillis, reflection, checkpointClears);
    }

    public SessionState withSpec(SessionSpec newSpec) {
        return new SessionState(newSpec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity, branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, startedMillis, reflection, checkpointClears);
    }

    /** A session that stopped, or failed to start, no longer has a start time. */
    public SessionState withStatus(Status newStatus, String newError) {
        long started = newStatus == Status.CLOSED || newStatus == Status.FAILED ? 0L : startedMillis;
        return new SessionState(spec, newStatus, newError, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity, branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, started, reflection, checkpointClears);
    }

    /** The process started at {@code at}; a start reported again for a process already running keeps the first time. */
    public SessionState withStarted(long at) {
        return startedMillis > 0L ? this : new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity,
                branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, at, reflection, checkpointClears);
    }

    public SessionState withReflection(Reflection newReflection) {
        return newReflection.equals(reflection) ? this : new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis,
                activity, branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, startedMillis, newReflection, checkpointClears);
    }

    /**
     * Whether the end of the running turn clears the conversation and carries its summary; see
     * {@link #checkpointClears}.
     */
    public SessionState withCheckpointClears(boolean clears) {
        return clears == checkpointClears ? this : new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow,
                lastActivityMillis, activity, branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, startedMillis, reflection, clears);
    }

    /**
     * {@code newInfo} in place of the info, the last activity's time left as it was: the info only changed in detail.
     */
    public SessionState withInfo(SessionInfo newInfo) {
        return newInfo.equals(info) ? this : withInfo(newInfo, lastActivityMillis);
    }

    public SessionState withInfo(SessionInfo newInfo, long at) {
        return new SessionState(spec, status, error, newInfo, pendingTurns, costUsd, contextTokens, contextWindow, at, activity, branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, startedMillis, reflection, checkpointClears);
    }

    /**
     * The pending turn count; a busy stretch starts its clock when it begins and stops it when it ends, and a turn
     * that starts clears the last turn's failure.
     */
    public SessionState withTurns(int pending, long at) {
        boolean starts = pendingTurns == 0 && pending > 0;
        long started = pending == 0 ? 0L : starts ? at : turnStartedMillis;
        return new SessionState(spec, status, error, info, pending, costUsd, contextTokens, contextWindow, at, activity, branch, started,
                starts ? "" : lastTurnError, tasks, starts ? 0L : returnedMillis, !starts && handedOff, startedMillis, reflection, checkpointClears);
    }

    /** How the last turn ended: a message when it failed, empty when it finished or was interrupted. */
    public SessionState withLastTurnError(String message) {
        return new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity,
                branch, turnStartedMillis, message, tasks, returnedMillis, handedOff, startedMillis, reflection, checkpointClears);
    }

    public SessionState withUsage(double cost, long tokens, long window, long at) {
        return new SessionState(spec, status, error, info, pendingTurns, cost, tokens, window, at, activity, branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, startedMillis, reflection, checkpointClears);
    }

    /** The context as a model call or the stored conversation reports it; a value at 0 or below keeps the one known. */
    public SessionState withContext(long tokens, long window) {
        long t = tokens > 0 ? tokens : contextTokens;
        long w = window > 0 ? window : contextWindow;
        return t == contextTokens && w == contextWindow ? this
                : new SessionState(spec, status, error, info, pendingTurns, costUsd, t, w, lastActivityMillis, activity, branch, turnStartedMillis,
                lastTurnError, tasks, returnedMillis, handedOff, startedMillis, reflection, checkpointClears);
    }

    public SessionState withActivity(String newActivity, long at) {
        return new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, at, newActivity, branch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, startedMillis, reflection, checkpointClears);
    }

    public SessionState withBranch(String newBranch) {
        return new SessionState(spec, status, error, info, pendingTurns, costUsd, contextTokens, contextWindow, lastActivityMillis, activity, newBranch, turnStartedMillis, lastTurnError, tasks, returnedMillis, handedOff, startedMillis, reflection, checkpointClears);
    }
}
