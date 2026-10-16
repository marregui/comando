package dev.marregui.comando.core.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectionTest {

    private static final long WINDOW = 1_000_000L;

    @Test
    void dueAtTheFirstCrossingAndThroughACompaction() {
        Reflection r = Reflection.FRESH.afterTurn(599_999L, WINDOW);
        assertThat(r.due()).isFalse();
        r = r.afterTurn(600_000L, WINDOW);
        assertThat(r.due()).isTrue();
        assertThat(r.afterTurn(50_000L, WINDOW).due()).as("compacted, still not reflected").isTrue();
    }

    @Test
    void aCheckpointPastTheThresholdWaitsForTheContextToDropAndCrossAgain() {
        Reflection r = Reflection.checkpointed(650_000L, WINDOW);
        assertThat(r).isEqualTo(new Reflection(false, false));
        assertThat(r.afterTurn(660_000L, WINDOW).due()).isFalse();
        r = r.afterTurn(40_000L, WINDOW);
        assertThat(r.armed()).isTrue();
        assertThat(r.afterTurn(600_000L, WINDOW).due()).isTrue();
    }

    @Test
    void anUnknownWindowChangesNothing() {
        assertThat(Reflection.FRESH.afterTurn(900_000L, 0L)).isEqualTo(Reflection.FRESH);
        assertThat(Reflection.checkpointed(900_000L, 0L)).isEqualTo(Reflection.FRESH);
    }
}
