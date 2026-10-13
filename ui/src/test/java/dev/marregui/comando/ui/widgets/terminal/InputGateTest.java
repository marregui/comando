package dev.marregui.comando.ui.widgets.terminal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.marregui.comando.ui.widgets.terminal.InputGate.LIMIT_NANOS;
import static dev.marregui.comando.ui.widgets.terminal.InputGate.NO_OUTPUT;
import static dev.marregui.comando.ui.widgets.terminal.InputGate.SETTLE_NANOS;
import static org.assertj.core.api.Assertions.assertThat;

class InputGateTest {

    private static final long MS = 1_000_000L;

    private final List<String> written = new ArrayList<>();
    private final InputGate gate = new InputGate(written::add);

    @Test
    void textSentBeforeTheShellIsReadyIsWrittenAfterItInOrder() {
        long t0 = 1_000 * MS;
        gate.started(t0);

        assertThat(gate.send("select 1\n", t0 + 5 * MS, NO_OUTPUT)).isTrue();
        assertThat(gate.send("ls\n", t0 + 10 * MS, NO_OUTPUT)).isTrue();
        assertThat(written).as("nothing reaches a shell that has not drawn its prompt").isEmpty();

        long firstOutput = t0 + 80 * MS;
        assertThat(gate.poll(firstOutput + 50 * MS, firstOutput)).isTrue();
        assertThat(written).as("output still arriving: the rc files run").isEmpty();

        long lastOutput = t0 + 150 * MS;
        assertThat(gate.poll(lastOutput + SETTLE_NANOS - MS, lastOutput)).isTrue();
        assertThat(written).isEmpty();

        assertThat(gate.poll(lastOutput + SETTLE_NANOS, lastOutput)).isFalse();
        assertThat(written).containsExactly("select 1\n", "ls\n");

        assertThat(gate.send("pwd\n", lastOutput + SETTLE_NANOS + 10 * MS, lastOutput + SETTLE_NANOS + 5 * MS)).isFalse();
        assertThat(written).as("a ready shell takes text at once").containsExactly("select 1\n", "ls\n", "pwd\n");
    }

    @Test
    void clearedTextIsNeverWrittenToTheNextShell() {
        gate.started(0);
        gate.send("./gradlew clean\n", MS, NO_OUTPUT);
        assertThat(gate.waiting()).isEqualTo(1);
        gate.clear();
        assertThat(gate.waiting()).isZero();

        gate.started(10 * MS);
        assertThat(gate.poll(10 * MS + LIMIT_NANOS, NO_OUTPUT)).isFalse();
        assertThat(written).isEmpty();
    }

    @Test
    void aSilentShellGetsTheTextAfterTheLimit() {
        gate.started(0);
        gate.send("echo hi\n", MS, NO_OUTPUT);
        assertThat(gate.poll(LIMIT_NANOS - MS, NO_OUTPUT)).isTrue();
        assertThat(written).isEmpty();
        assertThat(gate.poll(LIMIT_NANOS, NO_OUTPUT)).isFalse();
        assertThat(written).containsExactly("echo hi\n");
    }

    @Test
    void aShellThatKeepsWritingGetsTheTextAfterTheLimit() {
        gate.started(0);
        gate.send("x\n", 0, NO_OUTPUT);
        assertThat(gate.poll(LIMIT_NANOS - MS, LIMIT_NANOS - 2 * MS)).isTrue();
        assertThat(gate.poll(LIMIT_NANOS, LIMIT_NANOS - MS)).isFalse();
        assertThat(written).containsExactly("x\n");
    }

    @Test
    void aGateNeverStartedWritesAtOnce() {
        assertThat(gate.send("a", 0, NO_OUTPUT)).isFalse();
        assertThat(written).containsExactly("a");
    }
}
