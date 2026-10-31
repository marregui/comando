package dev.marregui.comando.core.loop;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [ExecutorStop] stops an owner's executors so that their threads have ended when the owner's close returns.
/// - `now` interrupts and waits: a task that takes a while to answer its interrupt has ended when it returns
/// - `now` gives up at its bound and says so for a task that ignores the interrupt
/// - `drain` lets a queued write finish before stopping
/// - the log names by label each executor still running at the bound, and only those
/// - `drain` of several executors waits one bound for all of them, not one each
class ExecutorStopTest {

    /// Two labelled strands, one deaf to its interrupt and one that answers it: `now` returns false and its warning
    /// names the deaf one by its label and not the other.
    @Test
    void nowNamesInTheLogEachExecutorStillRunningAtTheBound() throws Exception {
        final ExecutorService deaf = Threads.strand("stop-named-deaf");
        final ExecutorService quick = Threads.strand("stop-named-quick");
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        deaf.execute(() -> {
            started.countDown();
            ignoreInterrupts(release);
        });
        quick.execute(() -> {
            started.countDown();
            pause(60_000, new AtomicBoolean());
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final Handler capture = new Handler() {
            @Override
            public void publish(final LogRecord entry) {
                warnings.add(entry.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final Logger log = Logger.getLogger(ExecutorStop.class.getName());
        log.addHandler(capture);
        try {
            assertThat(ExecutorStop.now("test", Duration.ofMillis(200), Map.of("deaf-strand", deaf, "quick-strand", quick)))
                    .isFalse();
            assertThat(warnings).singleElement().asString()
                    .contains("owner=test", "running=[deaf-strand]")
                    .doesNotContain("quick-strand");
            assertThat(quick.isTerminated()).isTrue();
        } finally {
            log.removeHandler(capture);
            release.countDown();
        }
    }

    /// Three strands each in a write that outlasts the 500 ms bound when the owner closes: `drain` waits that bound
    /// once for all three, then interrupts them, so it returns after about one bound, not three.
    @Test
    void drainOfSeveralWaitsOneBoundForAllOfThem() throws Exception {
        final ExecutorService a = Threads.strand("stop-drain-a");
        final ExecutorService b = Threads.strand("stop-drain-b");
        final ExecutorService c = Threads.strand("stop-drain-c");
        final CountDownLatch started = new CountDownLatch(3);
        final AtomicBoolean interrupted = new AtomicBoolean();
        for (final ExecutorService e : List.of(a, b, c)) {
            e.execute(() -> {
                started.countDown();
                pause(60_000, interrupted);
            });
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        final long startNanos = System.nanoTime();

        assertThat(ExecutorStop.drain("test", Duration.ofMillis(500), Map.of("a", a, "b", b, "c", c))).isTrue();

        assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isBetween(Duration.ofMillis(500), Duration.ofMillis(1_200));
        assertThat(interrupted).isTrue();
        assertThat(List.of(a, b, c)).allMatch(ExecutorService::isTerminated);
    }

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
                // the cleanup a cut task does before it ends: a sleep, not a spin, on a strand's virtual thread (G-16.12);
                // the interrupt was consumed by the catch, so it does not cut this sleep short
                try {
                    Thread.sleep(300);
                } catch (final InterruptedException again) {
                    Thread.currentThread().interrupt();
                    return;
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

    /// Waits for `release`, its interrupts ignored on purpose: a task deaf to the stop.
    private static void ignoreInterrupts(final CountDownLatch release) {
        boolean waiting = true;
        while (waiting) {
            try {
                waiting = !release.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                // ignored on purpose
            }
        }
    }

    private static void pause(final long millis, final AtomicBoolean interrupted) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            interrupted.set(true);
        }
    }
}
