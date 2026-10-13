package dev.marregui.comando.core.model;

/** How the next start of a session reaches Claude Code. */
public enum StartMode {
    /** A new conversation with a fresh session id ({@code --session-id}). */
    NEW,
    /** Continue the stored conversation ({@code --resume <id>}). */
    RESUME,
    /** Continue from a copy of the stored conversation ({@code --resume <id> --fork-session}). */
    FORK
}
