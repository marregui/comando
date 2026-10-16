package dev.marregui.comando.ui.kit.async;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class EdtTest {

    @Test
    void aBoundedWaitRunsTheTaskOnTheEdtAndSaysItRan() {
        AtomicBoolean onEdt = new AtomicBoolean();
        assertThat(Edt.runAndWait(() -> onEdt.set(SwingUtilities.isEventDispatchThread()), Duration.ofSeconds(5))).isTrue();
        assertThat(onEdt).isTrue();
    }

    @Test
    void aBoundedWaitOnAnEdtThatDoesNotAnswerEndsAndTheTaskNeverRuns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean ran = new AtomicBoolean();
        try {
            boolean done = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> Edt.runAndWait(() -> ran.set(true), Duration.ofMillis(200)));
            assertThat(done).isFalse();
        } finally {
            release.countDown();
        }
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(ran).as("a task that had not started by the bound is dropped").isFalse();
    }

    @Test
    void aTaskThatFailsOnTheEdtFailsTheCaller() {
        assertThatThrownBy(() -> Edt.runAndWait(() -> {
            throw new IllegalArgumentException("boom");
        }, Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class).hasRootCauseMessage("boom");
    }
}
