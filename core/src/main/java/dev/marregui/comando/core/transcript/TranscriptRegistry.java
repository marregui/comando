package dev.marregui.comando.core.transcript;

import java.util.concurrent.ConcurrentHashMap;

/** Transcripts by session name; the state refers to them by that name. A sub-agent's is under {@link #subagentKey}. */
public final class TranscriptRegistry {

    /** Between a session's name and a sub-agent's id in a sub-agent's key: a character no session name holds. */
    private static final char SUBAGENT = '\u241F';

    /** The key of the transcript of the sub-agent {@code toolUseId} of {@code session} (record 109). */
    public static String subagentKey(String session, String toolUseId) {
        return session + SUBAGENT + "agent:" + toolUseId;
    }

    /** Whether {@code key} is one of {@code session}'s sub-agents' keys. */
    public static boolean isSubagentOf(String key, String session) {
        return key.startsWith(session + SUBAGENT);
    }

    private final ConcurrentHashMap<String, Transcript> transcripts = new ConcurrentHashMap<>();

    public Transcript of(String session) {
        return transcripts.computeIfAbsent(session, k -> new Transcript());
    }

    /** Forgets {@code session}'s transcript and its sub-agents'. */
    public void forget(String session) {
        transcripts.remove(session);
        transcripts.keySet().removeIf(k -> isSubagentOf(k, session));
    }
}
