package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.SessionSpec;

/** The Claude Code adapter. Every call returns at once; outcomes come back as intents. */
public interface ClaudePort {

    void start(SessionSpec spec);

    void stop(String name);

    void send(String name, String text, String origin);

    void interrupt(String name);

    /** Drops the blocks of the session's transcript; the conversation, in Claude Code's files, is untouched. */
    void clearTranscript(String name);

    /** Fills the session's transcript from its stored conversation, without starting a process. */
    void loadHistory(SessionSpec spec);

    void forget(String name);
}
