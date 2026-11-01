package dev.marregui.comando.core.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reflection] records when session context crosses the checkpoint threshold, a share of the session's context
/// window, and when another crossing is armed.
///
/// - The first threshold crossing makes a fresh session due.
/// - A checkpoint requires a turn that ends below the threshold before another due state.
/// - An unknown context window, or a threshold of 0, leaves the state unchanged.
class ReflectionTest {

    private static final long WINDOW = 1_000_000L;
    private static final int PERCENT = Reflection.DEFAULT_PERCENT;

    /// [Reflection#afterTurn] marks a fresh million-token window due at 400,000 tokens, the default 40 %, and keeps it
    /// due after compaction to 50,000 tokens, because an outstanding checkpoint survives compaction.
    @Test
    void dueAtTheFirstCrossingAndThroughACompaction() {
        Reflection r = Reflection.FRESH.afterTurn(399_999L, WINDOW, PERCENT);
        assertThat(r.due()).isFalse();
        r = r.afterTurn(400_000L, WINDOW, PERCENT);
        assertThat(r.due()).isTrue();
        assertThat(r.afterTurn(50_000L, WINDOW, PERCENT).due()).as("compacted, still not reflected").isTrue();
    }

    /// [Reflection#checkpointed] disarms until a turn ends below the threshold, at 40,000 tokens here, and the context
    /// later crosses 400,000 tokens again, preventing consecutive checkpoints near the boundary; a checkpoint sent below
    /// the threshold waits the same way, as the automatic checkpoint does.
    @Test
    void aCheckpointWaitsForATurnBelowTheThresholdAndACrossingAgain() {
        Reflection r = Reflection.checkpointed();
        assertThat(r).isEqualTo(new Reflection(false, false));
        assertThat(r.afterTurn(460_000L, WINDOW, PERCENT).due()).isFalse();
        r = r.afterTurn(40_000L, WINDOW, PERCENT);
        assertThat(r.armed()).isTrue();
        assertThat(r.afterTurn(400_000L, WINDOW, PERCENT).due()).isTrue();
    }

    /// The threshold is the share given: at 60 % a session falls due at 600,000 tokens of a million, not at 599,999.
    @Test
    void theThresholdIsTheShareOfTheWindowGiven() {
        assertThat(Reflection.FRESH.afterTurn(599_999L, WINDOW, 60).due()).isFalse();
        assertThat(Reflection.FRESH.afterTurn(600_000L, WINDOW, 60).due()).isTrue();
        assertThat(Reflection.above(600_000L, WINDOW, 60)).isTrue();
    }

    /// [Reflection] returns the existing state when the window is zero, since a threshold cannot be calculated, and
    /// when the threshold is 0, which makes no session due.
    @Test
    void anUnknownWindowOrNoThresholdChangesNothing() {
        assertThat(Reflection.FRESH.afterTurn(900_000L, 0L, PERCENT)).isEqualTo(Reflection.FRESH);
        assertThat(Reflection.FRESH.afterTurn(900_000L, WINDOW, 0)).isEqualTo(Reflection.FRESH);
        assertThat(Reflection.above(900_000L, 0L, PERCENT)).isFalse();
    }
}
