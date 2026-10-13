package dev.marregui.comando.ui.dialogs.sessions;

/** A conversation Claude Code stored on disk, as the import dialog lists it. */
public record StoredConversation(String sessionId, String cwd, String branch, String title, String lastPrompt, long modifiedMillis,
                                 long sizeBytes) {
}
