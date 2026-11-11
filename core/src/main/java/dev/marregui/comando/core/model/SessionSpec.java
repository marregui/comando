package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * Configuration of one Claude Code session. Empty strings mean "the CLI's configured default". How Claude asks before a
 * tool, and which folders it may reach, is not a session's: the kit's {@code settings.json} rules hold for every session
 * (record 7), so a session an agent creates has its creator's permissions by construction.
 *
 * @param name            unique name, the key panels bind to; also passed as {@code --name}
 * @param workDir         working directory of the CLI process
 * @param model           model alias or id ({@code --model})
 * @param effort          effort level ({@code --effort})
 * @param budgetUsd       spend cap per process ({@code --max-budget-usd}); 0 means none
 * @param claudeSessionId Claude Code's session id once known; empty for a session never started
 * @param checkpoint      the prompt the Checkpoint button sends ("/reflect"); empty hides the button
 * @param startMode       how the next start reaches the conversation
 * @param carried         what the Checkpoint button carried out of the conversation it cleared: the checkpoint's
 *                        summary, which goes in front of the next prompt; empty when nothing waits
 * @param createdBy       who created the session: empty for the user, else the session or client that called
 *                        {@code session_new} (record 121)
 * @param family          the root of the session's creation family, recorded when the user removed that root: the
 *                        removed session's name, so the sessions it made, and theirs, stay one family once their
 *                        chain of creators no longer reaches it (record 121, rule 1); empty while the chain does, and
 *                        for a session no removal cut off. Kept by every edit; only the reducer sets it
 * @param unreadMillis    when the session finished work the user has not looked at (record 136); 0 for none, which is
 *                        also what a {@code sessions.json} without the field reads as. Kept by every edit; only the
 *                        reducer sets it
 */
public record SessionSpec(
        String name,
        String workDir,
        String model,
        String effort,
        double budgetUsd,
        String claudeSessionId,
        StartMode startMode,
        String checkpoint,
        String carried,
        String createdBy,
        String family,
        long unreadMillis) {

    /** The checkpoint command of a session that never set one: what the Checkpoint button sends. */
    public static final String DEFAULT_CHECKPOINT = "/reflect";
    /**
     * The argument the automatic checkpoint adds to the checkpoint command ({@code /reflect --auto}), so the command can
     * tell it from one the user started: it asks the user nothing and leaves its decisions
     * in its scratchpad task entry.
     */
    public static final String AUTO_ARGUMENT = "--auto";
    /** Claude Code's command that compacts a conversation, sent by the compact buttons and the palette. */
    public static final String COMPACT_COMMAND = "/compact";
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
        // nor a family: no removal cut it off
        family = family == null ? "" : family.strip();
        ConnectionSpec.requireName(name);
        Objects.requireNonNull(workDir, "workDir");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(effort, "effort");
        Objects.requireNonNull(claudeSessionId, "claudeSessionId");
        Objects.requireNonNull(startMode, "startMode");
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

    /** A session with nothing to read. */
    public SessionSpec(
            final String name,
            final String workDir,
            final String model,
            final String effort,
            final double budgetUsd,
            final String claudeSessionId,
            final StartMode startMode,
            final String checkpoint,
            final String carried,
            final String createdBy,
            final String family
    ) {
        this(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint, carried, createdBy,
                family, 0L);
    }

    /** A session no removal cut off from its creation family. */
    public SessionSpec(
            final String name,
            final String workDir,
            final String model,
            final String effort,
            final double budgetUsd,
            final String claudeSessionId,
            final StartMode startMode,
            final String checkpoint,
            final String carried,
            final String createdBy
    ) {
        this(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint, carried, createdBy, "");
    }

    /** A session the user made: no creator recorded. */
    public SessionSpec(
            final String name,
            final String workDir,
            final String model,
            final String effort,
            final double budgetUsd,
            final String claudeSessionId,
            final StartMode startMode,
            final String checkpoint,
            final String carried
    ) {
        this(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint, carried, "");
    }

    public static SessionSpec fresh(final String name, final String workDir) {
        return new SessionSpec(name, workDir, "", "", 0.0, "", StartMode.NEW, DEFAULT_CHECKPOINT, "");
    }

    /**
     * The same session on a new conversation: the next start creates one; the old conversation stays on disk. A carried
     * summary still waits for the next prompt.
     */
    public SessionSpec restarted() {
        return new SessionSpec(name, workDir, model, effort, budgetUsd, "", StartMode.NEW, checkpoint, carried,
                createdBy, family, unreadMillis);
    }

    /**
     * A conversation imported from Claude Code's files. It may still be open in a terminal, so its first
     * start forks it: two processes never write one conversation. The fork's own id is resumed after that.
     */
    public static SessionSpec imported(final String name, final String workDir, final String claudeSessionId) {
        return new SessionSpec(name, workDir, "", "", 0.0, claudeSessionId, StartMode.FORK, DEFAULT_CHECKPOINT, "");
    }

    public SessionSpec withName(final String newName) {
        return new SessionSpec(newName, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint,
                carried, createdBy, family, unreadMillis);
    }

    /** After Claude Code reports its session id, later starts resume that conversation. */
    public SessionSpec resumable(final String sessionId) {
        return new SessionSpec(name, workDir, model, effort, budgetUsd, sessionId, StartMode.RESUME, checkpoint,
                carried, createdBy, family, unreadMillis);
    }

    public SessionSpec withCheckpoint(final String newCheckpoint) {
        return new SessionSpec(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, newCheckpoint,
                carried, createdBy, family, unreadMillis);
    }

    /** The checkpoint's summary that goes in front of the next prompt; empty once it has gone. */
    public SessionSpec withCarried(final String newCarried) {
        return new SessionSpec(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint,
                newCarried, createdBy, family, unreadMillis);
    }

    public SessionSpec withModel(final String newModel) {
        return new SessionSpec(name, workDir, newModel, effort, budgetUsd, claudeSessionId, startMode, checkpoint,
                carried, createdBy, family, unreadMillis);
    }

    public SessionSpec withEffort(final String newEffort) {
        return new SessionSpec(name, workDir, model, newEffort, budgetUsd, claudeSessionId, startMode, checkpoint,
                carried, createdBy, family, unreadMillis);
    }

    /** The same session, recorded as created by {@code creator} (a session or client name; empty for the user). */
    public SessionSpec withCreatedBy(final String creator) {
        return new SessionSpec(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint, carried,
                creator, family, unreadMillis);
    }

    /** The same session, recorded as of the creation family whose root was {@code root}, the removed session's name. */
    public SessionSpec withFamily(final String root) {
        return new SessionSpec(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint, carried,
                createdBy, root, unreadMillis);
    }

    /** Whether the session finished work the user has not looked at. */
    public boolean unread() {
        return unreadMillis > 0;
    }

    /** The same session, with its work finished at {@code atMillis} for the user to read; 0 when nothing waits. */
    public SessionSpec withUnread(final long atMillis) {
        return new SessionSpec(name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint, carried,
                createdBy, family, atMillis);
    }
}
