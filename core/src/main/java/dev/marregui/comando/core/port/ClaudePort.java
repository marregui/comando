package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.SessionSpec;

/** The Claude Code adapter. Every call returns at once; outcomes come back as intents. */
public interface ClaudePort {

    void start(SessionSpec spec);

    void stop(String name);

    default void send(String name, String text, String origin) {
        send(name, text, origin, "");
    }

    /**
     * Sends {@code text}; a non-empty {@code preface} goes in front of it to Claude and is not shown on the user block.
     */
    void send(String name, String text, String origin, String preface);

    void interrupt(String name);

    /** Drops the blocks of the session's transcript; the conversation, in Claude Code's files, is untouched. */
    void clearTranscript(String name);

    /**
     * Sends {@code /clear}, so a new conversation starts, and empties the transcript, which then shows {@code carried},
     * the summary the next prompt takes with it; the clear's own notes are not shown.
     */
    void clearConversation(String name, String carried);

    /** Fills the session's transcript from its stored conversation, without starting a process. */
    void loadHistory(SessionSpec spec);

    void forget(String name);

    /**
     * Shows the stored conversation of the sub-agent that the Agent call {@code toolUseId} of conversation
     * {@code claudeSessionId} started, as a lower view that follows the file while it grows.
     */
    void showSubagent(String name, String claudeSessionId, String toolUseId);
}
