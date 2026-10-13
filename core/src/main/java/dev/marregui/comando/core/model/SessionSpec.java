package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * Configuration of one Claude Code session. Empty strings mean "the CLI's configured default".
 *
 * @param name            unique name, the key panels bind to; also passed as {@code --name}
 * @param workDir         working directory of the CLI process
 * @param model           model alias or id ({@code --model})
 * @param effort          effort level ({@code --effort})
 * @param permissionMode  permission mode ({@code --permission-mode})
 * @param addDirs         extra working directories ({@code --add-dir})
 * @param budgetUsd       spend cap per process ({@code --max-budget-usd}); 0 means none
 * @param claudeSessionId Claude Code's session id once known; empty for a session never started
 * @param startMode       how the next start reaches the conversation
 */
public record SessionSpec(
        String name,
        String workDir,
        String model,
        String effort,
        String permissionMode,
        List<String> addDirs,
        double budgetUsd,
        String claudeSessionId,
        StartMode startMode) {

    public SessionSpec {
        ConnectionSpec.requireName(name);
        Objects.requireNonNull(workDir, "workDir");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(effort, "effort");
        Objects.requireNonNull(permissionMode, "permissionMode");
        Objects.requireNonNull(claudeSessionId, "claudeSessionId");
        Objects.requireNonNull(startMode, "startMode");
        addDirs = List.copyOf(addDirs);
        if (workDir.isBlank()) {
            throw new IllegalArgumentException("workDir is empty");
        }
        if (budgetUsd < 0.0) {
            throw new IllegalArgumentException("budgetUsd is negative [budgetUsd=" + budgetUsd + ']');
        }
        if (startMode != StartMode.NEW && claudeSessionId.isEmpty()) {
            throw new IllegalArgumentException("startMode " + startMode + " needs a claudeSessionId");
        }
    }

    public static SessionSpec fresh(String name, String workDir) {
        return new SessionSpec(name, workDir, "", "", "", List.of(), 0.0, "", StartMode.NEW);
    }

    /**
     * A conversation imported from Claude Code's files. It may still be open in a terminal, so its first
     * start forks it: two processes never write one conversation. The fork's own id is resumed after that.
     */
    public static SessionSpec imported(String name, String workDir, String claudeSessionId) {
        return new SessionSpec(name, workDir, "", "", "", List.of(), 0.0, claudeSessionId, StartMode.FORK);
    }

    public SessionSpec withName(String newName) {
        return new SessionSpec(newName, workDir, model, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode);
    }

    /** After Claude Code reports its session id, later starts resume that conversation. */
    public SessionSpec resumable(String sessionId) {
        return new SessionSpec(name, workDir, model, effort, permissionMode, addDirs, budgetUsd, sessionId, StartMode.RESUME);
    }

    public SessionSpec withModel(String newModel) {
        return new SessionSpec(name, workDir, newModel, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode);
    }

    public SessionSpec withEffort(String newEffort) {
        return new SessionSpec(name, workDir, model, newEffort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode);
    }
}
