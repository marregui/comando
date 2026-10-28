package dev.marregui.comando.core.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reflection] records when session context crosses the checkpoint threshold and when another crossing is armed.
///
/// - The first threshold crossing makes a fresh session due.
/// - A checkpoint requires a drop below the threshold before another due state.
/// - An unknown context window leaves the state unchanged.
class ReflectionTest {

    private static final long WINDOW = 1_000_000L;

    /// [Reflection#afterTurn] marks a fresh million-token window due at 600,000 tokens and keeps it due after
    /// compaction to 50,000 tokens, because an outstanding checkpoint survives compaction.
    @Test
    void dueAtTheFirstCrossingAndThroughACompaction() {
        Reflection r = Reflection.FRESH.afterTurn(599_999L, WINDOW);
        assertThat(r.due()).isFalse();
        r = r.afterTurn(600_000L, WINDOW);
        assertThat(r.due()).isTrue();
        assertThat(r.afterTurn(50_000L, WINDOW).due()).as("compacted, still not reflected").isTrue();
    }

    /// [Reflection#checkpointed] disarms a checkpoint sent above 60 percent until context falls to 40,000 tokens and
    /// later crosses 600,000 tokens again, preventing consecutive checkpoints near the boundary.
    @Test
    void aCheckpointPastTheThresholdWaitsForTheContextToDropAndCrossAgain() {
        Reflection r = Reflection.checkpointed(650_000L, WINDOW);
        assertThat(r).isEqualTo(new Reflection(false, false));
        assertThat(r.afterTurn(660_000L, WINDOW).due()).isFalse();
        r = r.afterTurn(40_000L, WINDOW);
        assertThat(r.armed()).isTrue();
        assertThat(r.afterTurn(600_000L, WINDOW).due()).isTrue();
    }

    /// [Reflection] returns the existing state when the window is zero, since a threshold cannot be calculated.
    @Test
    void anUnknownWindowChangesNothing() {
        assertThat(Reflection.FRESH.afterTurn(900_000L, 0L)).isEqualTo(Reflection.FRESH);
        assertThat(Reflection.checkpointed(900_000L, 0L)).isEqualTo(Reflection.FRESH);
    }
}
