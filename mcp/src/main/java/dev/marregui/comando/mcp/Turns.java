package dev.marregui.comando.mcp;

import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** Waiting for a session to finish its turns: {@code session_read} with a wait, and an instance answering another's question. */
final class Turns {

    private static final long POLL_MILLIS = 200;

    private Turns() {
    }

    /**
     * Waits until the session has blocks after {@code after}, none of them still streaming or running, and its turns
     * are over (a turn end last, or the session no longer running); false at the deadline or on an interrupt. A
     * transcript shorter than the cursor was cleared since: the wait returns at once, rather than wait for blocks past
     * a cursor that no longer applies.
     */
    static boolean awaitTurnEnd(Supplier<AppState> state, String session, Transcript t, int after, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            Optional<SessionState> x = state.get().session(session);
            if (x.isEmpty()) {
                return true;
            }
            int size = t.size();
            if (size < after) {
                return true;
            }
            if (!x.get().isBusy() && settled(t, after, size) && (t.get(size - 1) instanceof Block.TurnEnd || !x.get().status().isUsable())) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean settled(Transcript t, int after, int size) {
        if (size <= after) {
            return false;
        }
        for (int i = after; i < size; i++) {
            Block b = t.get(i);
            if (b == null || !TranscriptText.finished(b)) {
                return false;
            }
        }
        return true;
    }
}
