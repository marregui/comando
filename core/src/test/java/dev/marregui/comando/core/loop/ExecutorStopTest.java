package dev.marregui.comando.core.loop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// [ExecutorStop] stops an owner's executors so that their threads have ended when the owner's close returns.
/// - `now` interrupts and waits: a task that takes a while to answer its interrupt has ended when it returns
/// - `now` gives up at its bound and says so for a task that ignores the interrupt
/// - `drain` lets a queued write finish before stopping
class ExecutorStopTest {

    /// A strand whose task, interrupted, still works 300 ms before it ends: when `now` returns, the task has ended and
    /// the strand's thread is gone.
    @Test
    void nowWaitsForATaskThatEndsAWhileAfterItsInterrupt() throws Exception {
        final ExecutorService strand = Threads.strand("stop-slow");
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean ended = new AtomicBoolean();
        strand.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (final InterruptedException e) {
                // the cleanup a cut task does before it ends
                final long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                ended.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(ExecutorStop.now("test", strand)).isTrue();

        assertThat(ended).isTrue();
        assertThat(strand.isTerminated()).isTrue();
    }

    /// A task that ignores its interrupt for longer than the bound: `now` returns at the bound, false, and does not
    /// wait for it.
    @Test
    void nowReturnsFalseAtItsBoundForATaskThatIgnoresTheInterrupt() throws Exception {
        final ExecutorService strand = Threads.strand("stop-deaf");
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        strand.execute(() -> {
            started.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    waiting = !release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    // ignored on purpose: a task deaf to the stop
                }
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            final long start = System.nanoTime();
            assertThat(ExecutorStop.now("test", Duration.ofMillis(200), strand)).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
        }
    }

    /// A slow write queued behind another when the owner closes: `drain` lets both finish, and nothing is interrupted.
    @Test
    void drainLetsTheQueuedWritesFinish() throws Exception {
        final ExecutorService writes = Threads.strand("stop-writes");
        final CountDownLatch first = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicBoolean secondDone = new AtomicBoolean();
        writes.execute(() -> {
            first.countDown();
            pause(200, interrupted);
        });
        writes.execute(() -> {
            pause(200, interrupted);
            secondDone.set(true);
        });
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(ExecutorStop.drain("test", Duration.ofSeconds(5), writes)).isTrue();

        assertThat(secondDone).isTrue();
        assertThat(interrupted).isFalse();
    }

    private static void pause(final long millis, final AtomicBoolean interrupted) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            interrupted.set(true);
        }
    }
}
