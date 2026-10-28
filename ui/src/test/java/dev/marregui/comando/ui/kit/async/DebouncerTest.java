package dev.marregui.comando.ui.kit.async;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [Debouncer] runs a task once, a fixed delay after the last of a burst of requests.
/// - A burst of requests runs the task once, after the delay counted from the last request.
/// - [Debouncer#pending] is true from a request until the task runs, and false after a stop.
/// - A stop drops a waiting request: the task does not run.
class DebouncerTest {

    /// Ten requests in a row on a 500 ms debouncer run the task exactly once, after the delay; [Debouncer#pending] reads
    /// true while it waits and false once the task has run. The delay is long against the burst, so a loaded machine
    /// cannot split it in two.
    @Test
    void aBurstRunsTheTaskOnceAfterTheLastRequest() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Debouncer d = new Debouncer(500, runs::incrementAndGet);
        for (int i = 0; i < 10; i++) {
            SwingUtilities.invokeAndWait(d::request);
        }
        assertThat(onEdt(d)).as("pending while the delay runs").isTrue();
        assertThat(runs).as("nothing before the delay after the last request").hasValue(0);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs).hasValue(1));
        assertThat(onEdt(d)).as("not pending once run").isFalse();
        Thread.sleep(600);
        assertThat(runs).as("one run for the whole burst").hasValue(1);
    }

    /// A stop between a request and its delay drops the request: [Debouncer#pending] turns false and the task never runs.
    @Test
    void aStopDropsTheWaitingRequest() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Debouncer d = new Debouncer(50, runs::incrementAndGet);
        SwingUtilities.invokeAndWait(() -> {
            d.request();
            d.stop();
        });
        assertThat(onEdt(d)).isFalse();
        Thread.sleep(200);
        assertThat(runs).hasValue(0);
    }

    private static boolean onEdt(Debouncer d) throws Exception {
        boolean[] pending = new boolean[1];
        SwingUtilities.invokeAndWait(() -> pending[0] = d.pending());
        return pending[0];
    }
}
