package dev.marregui.comando.mcp;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

/** Waits for a session's turns to end: {@code session_read} with a wait, and an answer to another instance. */
final class Turns {

    private static final long POLL_MILLIS = 200;

    private Turns() {
    }

    /**
     * Waits until the session has blocks after {@code after}, none of them still streaming or running, and its turns
     * are over (a turn end last, or the session no longer running); false at the deadline or on an interrupt. A
     * session that is gone, or a transcript shorter than the cursor (cleared since), returns at once: no block will
     * come past a cursor that no longer applies.
     */
    static boolean awaitTurnEnd(final Supplier<AppState> state, final String session, final Transcript t, final int after, final Duration within) {
        final long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            final Optional<SessionState> x = state.get().session(session);
            if (x.isEmpty()) {
                return true;
            }
            final int size = t.size();
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
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean settled(final Transcript t, final int after, final int size) {
        if (size <= after) {
            return false;
        }
        for (int i = after; i < size; i++) {
            final Block b = t.get(i);
            if (b == null || !TranscriptText.finished(b)) {
                return false;
            }
        }
        return true;
    }
}
