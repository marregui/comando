package dev.marregui.comando.core.state;

import java.util.List;
import java.util.Objects;

/**
 * What Claude Code reported about a running session: its {@code system/init} event, the base effort its settings give,
 * and the model and effort its messages run at, which the transcript it writes records (the stream does not).
 *
 * @param model          the model the session runs: the {@code system/init}'s, then the first message's of each turn,
 *                       so a {@code /model} typed in the prompt shows
 * @param effort the effort level Claude Code's settings give the {@code system/init}'s model, empty when they give none
 * @param reportedEffort the effort the latest turn started at, as its first message ran: the session's own setting, so
 *                       a {@code /effort} shows; empty before the first turn
 * @param turnModel      the model the running turn's latest message ran at, empty when no turn runs: a skill's
 *                       {@code model} lasts the rest of its turn
 * @param turnEffort     the effort the running turn's latest message ran at, empty when no turn runs: a skill's
 *                       {@code effort} (the {@code deep} skill's {@code high}) lasts the rest of its turn
 */
public record SessionInfo(String claudeSessionId, String model, String cwd, String cliVersion, String permissionMode, List<String> capabilities,
                          String effort, String reportedEffort, String turnModel, String turnEffort) {

    public static final SessionInfo NONE = new SessionInfo("", "", "", "", "", List.of(), "");

    /** What the event reported, with no base effort known. */
    public SessionInfo(String claudeSessionId, String model, String cwd, String cliVersion, String permissionMode, List<String> capabilities) {
        this(claudeSessionId, model, cwd, cliVersion, permissionMode, capabilities, "");
    }

    /** What the event reported and the settings' base effort; no message has run yet. */
    public SessionInfo(String claudeSessionId, String model, String cwd, String cliVersion, String permissionMode, List<String> capabilities,
                       String effort) {
        this(claudeSessionId, model, cwd, cliVersion, permissionMode, capabilities, effort, "", "", "");
    }

    public SessionInfo {
        effort = effort == null ? "" : effort;
        reportedEffort = reportedEffort == null ? "" : reportedEffort;
        turnModel = turnModel == null ? "" : turnModel;
        turnEffort = turnEffort == null ? "" : turnEffort;
        Objects.requireNonNull(claudeSessionId, "claudeSessionId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(cliVersion, "cliVersion");
        Objects.requireNonNull(permissionMode, "permissionMode");
        capabilities = List.copyOf(capabilities);
    }

    /**
     * A message of the running turn ran at {@code runModel} and {@code runEffort} (either empty when unknown); the
     * first of a turn also gives the session's own model and effort.
     */
    public SessionInfo ranAt(String runModel, String runEffort, boolean firstOfTurn) {
        String m = runModel == null ? "" : runModel;
        String e = runEffort == null ? "" : runEffort;
        return new SessionInfo(claudeSessionId, firstOfTurn && !m.isEmpty() ? m : model, cwd, cliVersion, permissionMode, capabilities,
                effort, firstOfTurn && !e.isEmpty() ? e : reportedEffort, m.isEmpty() ? turnModel : m, e.isEmpty() ? turnEffort : e);
    }

    /** The turn ended: what it ran at no longer holds; the next turn starts at the session's own setting. */
    public SessionInfo turnEnded() {
        return turnModel.isEmpty() && turnEffort.isEmpty() ? this
                : new SessionInfo(claudeSessionId, model, cwd, cliVersion, permissionMode, capabilities, effort, reportedEffort, "", "");
    }
}
