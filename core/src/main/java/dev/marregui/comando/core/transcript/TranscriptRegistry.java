package dev.marregui.comando.core.transcript;

import java.util.concurrent.ConcurrentHashMap;

/** Transcripts by session name; the state refers to them by that name. */
public final class TranscriptRegistry {

    private final ConcurrentHashMap<String, Transcript> transcripts = new ConcurrentHashMap<>();

    public Transcript of(String session) {
        return transcripts.computeIfAbsent(session, k -> new Transcript());
    }

    public void forget(String session) {
        transcripts.remove(session);
    }
}
