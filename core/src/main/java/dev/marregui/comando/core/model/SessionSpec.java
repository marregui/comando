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
 * @param checkpoint      the prompt the Checkpoint button sends ("/reflect"); empty hides the button
 * @param startMode       how the next start reaches the conversation
 * @param carried         what the Checkpoint button carried out of the conversation it cleared: the checkpoint's
 *                        summary, which goes in front of the next prompt; empty when nothing waits
 * @param createdBy       who created the session: empty for the user, else the session or client that called
 *                        {@code session_new} (record 121)
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
        StartMode startMode,
        String checkpoint,
        String carried,
        String createdBy) {

    /** The checkpoint command of a session that never set one: what the Checkpoint button sends. */
    public static final String DEFAULT_CHECKPOINT = "/reflect";
    /** Claude Code's command that compacts a conversation, sent by the compact buttons and the palette. */
    public static final String COMPACT_COMMAND = "/compact";
    /** Claude Code's command that clears a conversation; a new one starts. */
    public static final String CLEAR_COMMAND = "/clear";
    /** The model families comando offers, in the palette, a session row's chips and the session dialog. */
    public static final List<String> MODELS = List.of("fable", "opus", "sonnet", "haiku");
    /** The efforts comando offers, in the same places. */
    public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    public SessionSpec {
        // a sessions.json written before the field existed has none: the default, not a hidden button
        checkpoint = checkpoint == null ? DEFAULT_CHECKPOINT : checkpoint.strip();
        // nor a carried summary: nothing waits
        carried = carried == null ? "" : carried;
        // nor a creator: the user made it
        createdBy = createdBy == null ? "" : createdBy.strip();
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
        if (!Double.isFinite(budgetUsd) || budgetUsd < 0.0) {
            // NaN or Infinity would pass a sign check and leave the session without its spending cap
            throw new IllegalArgumentException("budgetUsd must be a finite amount, 0 or more [budgetUsd=" + budgetUsd + ']');
        }
        if (startMode != StartMode.NEW && claudeSessionId.isEmpty()) {
            throw new IllegalArgumentException("startMode " + startMode + " needs a claudeSessionId");
        }
    }

    /** A session the user made: no creator recorded. */
    public SessionSpec(String name, String workDir, String model, String effort, String permissionMode, List<String> addDirs, double budgetUsd,
                       String claudeSessionId, StartMode startMode, String checkpoint, String carried) {
        this(name, workDir, model, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode, checkpoint, carried, "");
    }

    public static SessionSpec fresh(String name, String workDir) {
        return new SessionSpec(name, workDir, "", "", "", List.of(), 0.0, "", StartMode.NEW, DEFAULT_CHECKPOINT, "");
    }

    /**
     * The same session on a new conversation: the next start creates one; the old conversation stays on disk. A carried
     * summary still waits for the next prompt.
     */
    public SessionSpec restarted() {
        return new SessionSpec(name, workDir, model, effort, permissionMode, addDirs, budgetUsd, "", StartMode.NEW, checkpoint, carried, createdBy);
    }

    /**
     * A conversation imported from Claude Code's files. It may still be open in a terminal, so its first
     * start forks it: two processes never write one conversation. The fork's own id is resumed after that.
     */
    public static SessionSpec imported(String name, String workDir, String claudeSessionId) {
        return new SessionSpec(name, workDir, "", "", "", List.of(), 0.0, claudeSessionId, StartMode.FORK, DEFAULT_CHECKPOINT, "");
    }

    public SessionSpec withName(String newName) {
        return new SessionSpec(newName, workDir, model, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode, checkpoint, carried, createdBy);
    }

    /** After Claude Code reports its session id, later starts resume that conversation. */
    public SessionSpec resumable(String sessionId) {
        return new SessionSpec(name, workDir, model, effort, permissionMode, addDirs, budgetUsd, sessionId, StartMode.RESUME, checkpoint, carried, createdBy);
    }

    public SessionSpec withCheckpoint(String newCheckpoint) {
        return new SessionSpec(name, workDir, model, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode, newCheckpoint, carried, createdBy);
    }

    /** The checkpoint's summary that goes in front of the next prompt; empty once it has gone. */
    public SessionSpec withCarried(String newCarried) {
        return new SessionSpec(name, workDir, model, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode, checkpoint, newCarried, createdBy);
    }

    public SessionSpec withModel(String newModel) {
        return new SessionSpec(name, workDir, newModel, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode, checkpoint, carried, createdBy);
    }

    public SessionSpec withEffort(String newEffort) {
        return new SessionSpec(name, workDir, model, newEffort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode, checkpoint, carried, createdBy);
    }

    /** The same session, recorded as created by {@code creator} (a session or client name; empty for the user). */
    public SessionSpec withCreatedBy(String creator) {
        return new SessionSpec(name, workDir, model, effort, permissionMode, addDirs, budgetUsd, claudeSessionId, startMode, checkpoint, carried, creator);
    }
}
