package dev.marregui.comando.claude.codex;

import dev.marregui.comando.core.json.ObjectNode;

/**
 * One Codex session's view of its app-server traffic, as {@link CodexAdapter} feeds it: the {@code CodexMapper} turns
 * it into transcript blocks, intents and metrics events (ADR 0197). Called on the session's strand in the adapter, one
 * call at a time per session.
 */
public interface CodexEvents {

    /** A prompt the session sends, shown as the user's block before its turn starts. */
    void prompt(String text, String origin, boolean delivered);

    /** The thread {@code threadId} started or was resumed on {@code model} at {@code effort}. */
    void threadStarted(String threadId, String model, String effort);

    /** A server notification: {@code method} and its {@code params} as Codex sent them. */
    void notification(String method, ObjectNode params);

    /** A note comando shows in the transcript (a command Codex has no equivalent for, a failure). */
    void note(String text);

    /** The process ended: running tool blocks end, the session's turn ends with {@code error} when non-empty. */
    void exited(String error);
}
