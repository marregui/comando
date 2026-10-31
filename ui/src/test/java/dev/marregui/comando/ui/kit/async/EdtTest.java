package dev.marregui.comando.ui.kit.async;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/// [Edt#runAndWait] runs a task on the event dispatch thread and waits for it at most a bound.
/// - A task that runs within the bound reports true and ran on the EDT.
/// - An EDT that does not answer within the bound ends the wait with false, and the task never runs.
/// - A task that throws on the EDT fails the caller with the cause attached.
class EdtTest {

    /// A task on a free EDT runs there, and the wait returns true.
    @Test
    void aBoundedWaitRunsTheTaskOnTheEdtAndSaysItRan() {
        final AtomicBoolean onEdt = new AtomicBoolean();
        assertThat(Edt.runAndWait(() -> onEdt.set(SwingUtilities.isEventDispatchThread()), Duration.ofSeconds(5))).isTrue();
        assertThat(onEdt).isTrue();
    }

    /// With the EDT held by an earlier event, a 200 ms bound returns false well inside 5 s, and the task, not started
    /// by the bound, is dropped rather than run once the EDT frees.
    @Test
    void aBoundedWaitOnAnEdtThatDoesNotAnswerEndsAndTheTaskNeverRuns() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch held = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        final AtomicBoolean ran = new AtomicBoolean();
        try {
            final boolean done = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> Edt.runAndWait(() -> ran.set(true), Duration.ofMillis(200)));
            assertThat(done).isFalse();
        } finally {
            release.countDown();
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(ran).as("a task that had not started by the bound is dropped").isFalse();
    }

    /// A task that throws on the EDT surfaces to the caller as [IllegalStateException] whose root cause is the task's
    /// exception.
    @Test
    void aTaskThatFailsOnTheEdtFailsTheCaller() {
        assertThatThrownBy(() -> Edt.runAndWait(() -> {
            throw new IllegalArgumentException("boom");
        }, Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class).hasRootCauseMessage("boom");
    }
}
