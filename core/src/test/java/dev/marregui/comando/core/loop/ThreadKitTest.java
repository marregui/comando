package dev.marregui.comando.core.loop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// [Threads], [Errands], and [Watchdog] supply named threads with the ownership and shutdown rules of the runtime.
///
/// - strands serialize work on virtual threads
/// - closing errands interrupts active calls and rejects new ones
/// - one platform watchdog serves all registered looks
class ThreadKitTest {

    /// A twenty-task strand keeps submission order on one live virtual thread carrying the requested name.
    @Test
    void strandsRunInOrderOnOneVirtualThreadThatTestsCanFindByName() throws Exception {
        ExecutorService strand = Threads.strand("kit-strand");
        List<Integer> order = new CopyOnWriteArrayList<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            strand.execute(() -> {
                order.add(n);
                threads.add(Thread.currentThread());
            });
        }
        strand.shutdown();
        assertThat(strand.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).isSorted().hasSize(20);
        assertThat(threads.stream().distinct().toList()).singleElement().satisfies(t -> {
            assertThat(t.isVirtual()).isTrue();
            assertThat(t.getName()).isEqualTo("kit-strand");
        });
    }

    /// A delayed task runs on a named virtual thread, and cancelling a later one immediately removes it from the timed strand queue.
    @Test
    void aTimedStrandRunsOnAVirtualThreadAndACancelledTaskLeavesItsQueue() throws Exception {
        ScheduledExecutorService timed = Threads.timedStrand("kit-timed");
        CountDownLatch ran = new CountDownLatch(1);
        Thread[] on = new Thread[1];
        timed.schedule(() -> {
            on[0] = Thread.currentThread();
            ran.countDown();
        }, 20, TimeUnit.MILLISECONDS);
        var later = timed.schedule(() -> { }, 1, TimeUnit.HOURS);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(on[0].isVirtual()).isTrue();
        assertThat(Threads.alive("kit-timed")).containsExactly(on[0]);
        later.cancel(false);
        assertThat(((ScheduledThreadPoolExecutor) timed).getQueue()).isEmpty();
        timed.shutdownNow();
        assertThat(timed.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    /// Closing two blocked errands interrupts and joins both within `CLOSE_WAIT`, then makes a later start return null.
    @Test
    void closingErrandsInterruptsAndWaitsForThoseRunningAndRefusesNewOnes() throws Exception {
        Errands errands = new Errands("test");
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger interrupted = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            errands.start("kit-errand", () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await(); // blocks until interrupted
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errands.running()).isEqualTo(2);
        long start = System.nanoTime();
        errands.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Errands.CLOSE_WAIT);
        assertThat(interrupted.get()).isEqualTo(2);
        assertThat(errands.running()).isZero();
        assertThat(errands.start("late", () -> { })).isNull();
    }

    /// Two registrations execute on the same platform `watchdog` thread, which ends after the final handle closes.
    @Test
    void oneWatchdogThreadRunsEveryLookAndEndsWhenTheLastLeaves() throws Exception {
        CountDownLatch both = new CountDownLatch(4);
        List<Thread> lookers = new CopyOnWriteArrayList<>();
        AutoCloseable a = Watchdog.shared().watch(() -> {
            lookers.add(Thread.currentThread());
            both.countDown();
        });
        AutoCloseable b = Watchdog.shared().watch(() -> {
            lookers.add(Thread.currentThread());
            both.countDown();
        });
        assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(lookers.stream().distinct().toList()).singleElement().satisfies(t -> {
            assertThat(t.isVirtual()).isFalse();
            assertThat(t.getName()).isEqualTo("watchdog");
        });
        a.close();
        b.close();
        Thread watchdog = lookers.getFirst();
        assertThat(watchdog.join(Duration.ofSeconds(5))).as("the thread ends with its last look").isTrue();
    }
}
