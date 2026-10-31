package dev.marregui.comando.core.loop;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// [Watchdog] keeps running its other looks when one of them fails.
class WatchdogTest {

    /// A look that throws `StackOverflowError` is run once and dropped, while a second look goes on being run three
    /// more periods.
    @Test
    void aLookThatThrowsAnErrorIsDroppedAndTheOtherLooksGoOn() throws Exception {
        final AtomicInteger failingRuns = new AtomicInteger();
        final CountDownLatch failed = new CountDownLatch(1);
        final CountDownLatch looked = new CountDownLatch(3);
        final AutoCloseable failing = Watchdog.shared().watch(() -> {
            failingRuns.incrementAndGet();
            failed.countDown();
            throw new StackOverflowError("simulated");
        });
        final AutoCloseable healthy = Watchdog.shared().watch(() -> {
            if (failed.getCount() == 0) {
                looked.countDown();
            }
        });
        try {
            assertThat(looked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failingRuns).hasValue(1);
        } finally {
            failing.close();
            healthy.close();
        }
    }
}
