package dev.marregui.comando.cdb.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/// The query pool hands every frame's partial back in frame order, whatever its size, runs each frame exactly once,
/// on a worker or on the awaiting thread, and stops a job's frames at its first failure, its cancel or its close.
/// Its workers are platform daemon threads named `cdb-query-<n>`, started at the first submit and ended by the
/// close. The race hunt's seed is in the output; replay with `-Dcdb.pool.seed=<seed>`.
@Timeout(120)
class QueryPoolTest {

    /// Ten seconds only bounds a hung test; every wait here ends in milliseconds when the pool works.
    private static final long TIMEOUT_SECONDS = 10L;
    /// How long a test waits to see that something does not happen: long against a worker's next claim (micros).
    private static final long ABSENCE_MILLIS = 200L;
    private static final long SEED = Long.getLong("cdb.pool.seed", System.nanoTime());
    private static final String WORKER = "cdb-query-";

    /// Frame `f`'s partial is the one the consumer reads at `await(f)` at 0, 1, 3 and 11 workers, the earlier frames
    /// running longer so they end after the later ones; with no worker every frame runs on the awaiting thread.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 11})
    void partialsComeInFrameOrderAtEveryPoolSize(final int threads) {
        final int frames = 500;
        final Set<String> runners = ConcurrentHashMap.newKeySet();
        try (QueryPool pool = QueryPool.of(threads);
             Job<Long> job = pool.submit(frames, f -> {
                 runners.add(Thread.currentThread().getName());
                 spin(4 * (frames - f));
                 return f * 31L + 7L;
             }, context(pool, null))) {
            for (int f = 0; f < frames; f++) {
                assertThat(job.await(f)).isEqualTo(f * 31L + 7L);
            }
        }
        if (threads == 0) {
            assertThat(runners).containsExactly(Thread.currentThread().getName());
        }
    }

    /// With the one worker held by another job, every frame of a new job runs on its awaiting thread instead of
    /// waiting for the pool.
    @Test
    void theAwaiterRunsTheFramesWhileThePoolIsBusy() throws InterruptedException {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> runners = new CopyOnWriteArrayList<>();
        try (QueryPool pool = QueryPool.of(1);
             Job<String> blocker = pool.submit(1, _ -> {
                 held.countDown();
                 awaitLatch(release);
                 return Thread.currentThread().getName();
             }, context(pool, null))) {
            try {
                assertThat(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                try (Job<Integer> job = pool.submit(3, f -> {
                    runners.add(Thread.currentThread().getName());
                    return f;
                }, context(pool, null))) {
                    for (int f = 0; f < 3; f++) {
                        assertThat(job.await(f)).isEqualTo(f);
                    }
                }
            } finally {
                release.countDown();
            }
            assertThat(blocker.await(0)).startsWith(WORKER);
        }
        assertThat(runners).hasSize(3).containsOnly(Thread.currentThread().getName());
    }

    /// With no worker, frame 3's failure, a `RuntimeException` or an `Error`, is thrown by its await and by every
    /// later await, the same instance, and no frame after it starts.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theFirstFailureOnTheAwaiterIsThrownByEveryAwait(final boolean error) {
        final Throwable failure = failure(error);
        final AtomicInteger started = new AtomicInteger();
        try (QueryPool pool = QueryPool.of(0);
             Job<Integer> job = pool.submit(10, f -> {
                 started.incrementAndGet();
                 if (f == 3) {
                     throw unchecked(failure);
                 }
                 return f;
             }, context(pool, null))) {
            for (int f = 0; f < 3; f++) {
                assertThat(job.await(f)).isEqualTo(f);
            }
            assertThatThrownBy(() -> job.await(3)).isSameAs(failure);
            assertThatThrownBy(() -> job.await(4)).isSameAs(failure);
            assertThatThrownBy(() -> job.await(9)).isSameAs(failure);
            assertThatThrownBy(() -> job.await(0)).isSameAs(failure);
        }
        assertThat(started).hasValue(4);
    }

    /// A worker's failure of frame 0, a `RuntimeException` or an `Error`, stops the job: the worker starts none of
    /// its other frames, and every await throws that failure.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aWorkersFailureStopsTheJobsFrames(final boolean error) throws InterruptedException {
        final Throwable failure = failure(error);
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch fail = new CountDownLatch(1);
        final CountDownLatch another = new CountDownLatch(1);
        try (QueryPool pool = QueryPool.of(1);
             Job<Integer> job = pool.submit(1_000, f -> {
                 if (f == 0) {
                     first.countDown();
                     awaitLatch(fail);
                     throw unchecked(failure);
                 }
                 another.countDown();
                 return f;
             }, context(pool, null))) {
            try {
                assertThat(first.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } finally {
                fail.countDown();
            }
            assertThatThrownBy(() -> job.await(0)).isSameAs(failure);
            assertThat(another.await(ABSENCE_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            assertThatThrownBy(() -> job.await(500)).isSameAs(failure);
            assertThatThrownBy(() -> job.await(999)).isSameAs(failure);
        }
        assertThat(another.getCount()).isEqualTo(1L);
    }

    /// A job cancelled while it waits behind a held worker runs none of its frames: the worker passes over them to the
    /// next job, and the job's await throws the cancel.
    @Test
    void aCancelReachesTheQueuedFrames() throws InterruptedException {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch nextRan = new CountDownLatch(1);
        final AtomicInteger ran = new AtomicInteger();
        final AtomicReference<String> nextRunner = new AtomicReference<>();
        final CdbCancel cancel = new CdbCancel();
        try (QueryPool pool = QueryPool.of(1);
             Job<Integer> blocker = pool.submit(1, f -> {
                 held.countDown();
                 awaitLatch(release);
                 return f;
             }, context(pool, null))) {
            try {
                assertThat(held.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } catch (final InterruptedException e) {
                release.countDown();
                throw e;
            }
            try (Job<Integer> cancelled = pool.submit(100, f -> {
                ran.incrementAndGet();
                return f;
            }, context(pool, cancel));
                 Job<Integer> next = pool.submit(1, f -> {
                     nextRunner.set(Thread.currentThread().getName());
                     nextRan.countDown();
                     return f;
                 }, context(pool, null))) {
                cancel.cancel();
                release.countDown();
                assertThat(nextRan.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(nextRunner.get()).startsWith(WORKER);
                assertThat(ran).hasValue(0);
                assertThatThrownBy(() -> cancelled.await(0)).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> cancelled.await(99)).isInstanceOf(CdbException.class);
                assertThat(next.await(0)).isZero();
                assertThat(blocker.await(0)).isZero();
            }
        }
        assertThat(ran).hasValue(0);
    }

    /// A job's close returns only once the frames its workers run have ended.
    @Test
    void closeWaitsForTheFramesInFlight() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(3);
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger ended = new AtomicInteger();
        final AtomicInteger endedAtClose = new AtomicInteger(-1);
        try (QueryPool pool = QueryPool.of(3)) {
            final Job<Integer> job = pool.submit(3, f -> {
                started.countDown();
                awaitLatch(gate);
                ended.incrementAndGet();
                return f;
            }, context(pool, null));
            // the closer starts once every frame runs: a close stops claims, so a frame not yet claimed never starts
            final Thread closer;
            try {
                assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                closer = Thread.ofPlatform().name("query-pool-test-closer").start(() -> {
                    job.close();
                    endedAtClose.set(ended.get());
                    closed.countDown();
                });
                assertThat(closed.await(ABSENCE_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                gate.countDown();
            }
            assertThat(closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(closer.join(Duration.ofSeconds(TIMEOUT_SECONDS))).isTrue();
        }
        assertThat(endedAtClose).hasValue(3);
    }

    /// A job closed while its one worker runs frame 0 skips the frames not yet started.
    @Test
    void closeSkipsTheFramesNotStarted() throws InterruptedException {
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();
        final AtomicBoolean closeReturned = new AtomicBoolean();
        try (QueryPool pool = QueryPool.of(1)) {
            final Job<Integer> job = pool.submit(10, f -> {
                started.incrementAndGet();
                if (f == 0) {
                    first.countDown();
                    awaitLatch(gate);
                }
                return f;
            }, context(pool, null));
            final Thread closer;
            try {
                assertThat(first.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                closer = Thread.ofPlatform().name("query-pool-test-closer").start(() -> {
                    job.close();
                    closeReturned.set(true);
                });
                // parked on the job, the close has stopped the frames and waits for frame 0
                assertEventually(() -> closer.getState() == Thread.State.TIMED_WAITING
                        && LockSupport.getBlocker(closer) == job, "the close waiting for frame 0");
            } finally {
                gate.countDown();
            }
            assertThat(closer.join(Duration.ofSeconds(TIMEOUT_SECONDS))).isTrue();
            assertThat(closeReturned).isTrue();
            assertThat(started).hasValue(1);
        }
    }

    /// An await after the job's close is refused, and a second close returns.
    @Test
    void anAwaitAfterCloseIsRefused() {
        try (QueryPool pool = QueryPool.of(0)) {
            final Job<Integer> job = pool.submit(2, f -> f, context(pool, null));
            assertThat(job.await(0)).isZero();
            job.close();
            job.close();
            assertThatThrownBy(() -> job.await(1)).isInstanceOf(IllegalStateException.class);
        }
    }

    /// Frame 7 fails on a worker before frame 2 ends: frames 0 and 1 still answer, and frame 2's own failure, the
    /// lower one, is what the awaits from 2 on throw, as the serial run would; then with frame 7's failure alone,
    /// frames 0 to 6 answer. At 6 and 11 workers, so frame 7 is in the window while frame 2 runs.
    @ParameterizedTest
    @ValueSource(ints = {6, 11})
    void theLowestFailedFrameIsTheFailureAndTheFramesBeforeItAnswer(final int threads) throws InterruptedException {
        final RuntimeException late = new IllegalStateException("frame 7 failed");
        final RuntimeException early = new IllegalStateException("frame 2 failed");
        final CountDownLatch lateFailed = new CountDownLatch(1);
        try (QueryPool pool = QueryPool.of(threads);
             Job<Integer> job = pool.submit(10, f -> {
                 if (f == 7) {
                     lateFailed.countDown();
                     throw late;
                 }
                 if (f == 2) {
                     awaitLatch(lateFailed);
                     throw early;
                 }
                 return f;
             }, context(pool, null))) {
            assertThat(job.await(0)).isZero();
            assertThat(job.await(1)).isEqualTo(1);
            assertThatThrownBy(() -> job.await(2)).isSameAs(early);
            assertThatThrownBy(() -> job.await(3)).isSameAs(early);
        }
        // with the failure at 7 alone, frames 0 to 6 answer
        try (QueryPool pool = QueryPool.of(threads);
             Job<Integer> job = pool.submit(10, f -> {
                 if (f == 7) {
                     lateFailed.countDown();
                     throw late;
                 }
                 return f;
             }, context(pool, null))) {
            for (int f = 0; f < 7; f++) {
                assertThat(job.await(f)).isEqualTo(f);
            }
            assertThatThrownBy(() -> job.await(7)).isSameAs(late);
        }
    }

    /// A frame that submits a job of its own and awaits it, on a pool of one worker, ends: the nested frames run on
    /// the awaiting worker rather than wait for the pool.
    @Test
    void aFrameThatAwaitsANestedJobOnAPoolOfOneEnds() {
        try (QueryPool pool = QueryPool.of(1);
             Job<Integer> outer = pool.submit(4, f -> {
                 try (Job<Integer> inner = pool.submit(3, g -> f * 10 + g, context(pool, null))) {
                     return inner.await(0) + inner.await(1) + inner.await(2);
                 }
             }, context(pool, null))) {
            for (int f = 0; f < 4; f++) {
                assertThat(outer.await(f)).isEqualTo(30 * f + 3);
            }
        }
    }

    /// A job submitted to a closed pool runs every frame on its awaiting thread.
    @Test
    void aSubmitAfterCloseRunsOnTheAwaiter() {
        final Set<String> runners = ConcurrentHashMap.newKeySet();
        final QueryPool pool = QueryPool.of(2);
        pool.close();
        try (Job<Integer> job = pool.submit(5, f -> {
            runners.add(Thread.currentThread().getName());
            return f;
        }, context(pool, null))) {
            for (int f = 0; f < 5; f++) {
                assertThat(job.await(f)).isEqualTo(f);
            }
        }
        assertThat(runners).containsExactly(Thread.currentThread().getName());
    }

    /// Workers run at most the pool's threads plus one frames past the last one awaited, so the partials held ahead
    /// of the merge stay bounded.
    @Test
    void workersRunAtMostTheWindowPastTheAwaitedFrame() {
        final int threads = 3;
        final AtomicInteger highest = new AtomicInteger(-1);
        try (QueryPool pool = QueryPool.of(threads);
             Job<Integer> job = pool.submit(100, f -> {
                 highest.accumulateAndGet(f, Math::max);
                 return f;
             }, context(pool, null))) {
            assertThat(job.await(0)).isZero();
            assertEventually(() -> highest.get() >= threads + 1, "the window's frames ran");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ABSENCE_MILLIS));
            // awaited is 1 after await(0): frames below 1 + threads + 1 may run, no further
            assertThat(highest.get()).isEqualTo(threads + 1);
        }
    }

    /// An interrupt of a consumer waiting for a worker's frame fails the job with the store's failure and keeps the
    /// consumer's interrupt status.
    @Test
    void anInterruptedAwaitFailsTheJobAndKeepsTheInterrupt() throws InterruptedException {
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean interruptKept = new AtomicBoolean();
        try (QueryPool pool = QueryPool.of(1);
             Job<Integer> job = pool.submit(1, f -> {
                 first.countDown();
                 awaitLatch(gate);
                 return f;
             }, context(pool, null))) {
            try {
                assertThat(first.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                final Thread consumer = Thread.ofPlatform().name("query-pool-test-consumer").start(() -> {
                    try {
                        job.await(0);
                    } catch (final RuntimeException e) {
                        thrown.set(e);
                        interruptKept.set(Thread.currentThread().isInterrupted());
                    }
                });
                consumer.interrupt();
                assertThat(consumer.join(Duration.ofSeconds(TIMEOUT_SECONDS))).isTrue();
            } finally {
                gate.countDown();
            }
            assertThat(thrown.get()).isInstanceOf(CdbException.class);
            assertThat(interruptKept).isTrue();
            assertThatThrownBy(() -> job.await(0)).isSameAs(thrown.get());
        }
    }

    /// A pool starts its workers at its first submit, not before, as platform daemon threads named `cdb-query-<n>`,
    /// and none of them is alive once it has closed; a pool of no worker starts none.
    @Test
    void workersStartAtTheFirstSubmitAreNamedAndEndAtClose() {
        final Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
        final List<Thread> workers;
        try (QueryPool none = QueryPool.of(0); Job<Integer> job = none.submit(4, f -> f, context(none, null))) {
            for (int f = 0; f < 4; f++) {
                assertThat(job.await(f)).isEqualTo(f);
            }
            assertThat(newWorkers(before)).isEmpty();
        }
        try (QueryPool pool = QueryPool.of(3)) {
            assertThat(newWorkers(before)).isEmpty();
            try (Job<Integer> job = pool.submit(8, f -> f, context(pool, null))) {
                for (int f = 0; f < 8; f++) {
                    assertThat(job.await(f)).isEqualTo(f);
                }
            }
            workers = newWorkers(before);
            assertThat(workers).hasSize(3).allSatisfy(t -> {
                assertThat(t.getName()).matches("cdb-query-\\d+");
                assertThat(t.isDaemon()).isTrue();
                assertThat(t.isVirtual()).isFalse();
            });
        }
        assertThat(workers).noneMatch(Thread::isAlive);
        assertThat(newWorkers(before)).isEmpty();
    }

    /// 1,000 seeded rounds of 4 consumers each submitting a job of 1 to 48 frames of random length to one pool of 3
    /// workers at once: every frame runs exactly once and every await reads its own frame's partial.
    @Test
    void concurrentJobsLoseAndRepeatNoFrame() throws InterruptedException {
        System.out.println("concurrentJobsLoseAndRepeatNoFrame seed=" + SEED);
        final int consumers = 4;
        final int rounds = 1_000;
        final CyclicBarrier start = new CyclicBarrier(consumers);
        final CountDownLatch done = new CountDownLatch(consumers);
        final AtomicInteger wrong = new AtomicInteger();
        final AtomicInteger lost = new AtomicInteger();
        final AtomicInteger repeated = new AtomicInteger();
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        final List<Thread> threads = new ArrayList<>();
        try (QueryPool pool = QueryPool.of(3)) {
            for (int c = 0; c < consumers; c++) {
                final int id = c;
                final Random random = new Random(SEED + id);
                threads.add(Thread.ofPlatform().name("query-pool-test-consumer-" + id).start(() -> {
                    try {
                        for (int r = 0; r < rounds; r++) {
                            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            final int frames = 1 + random.nextInt(48);
                            final int[] spins = new int[frames];
                            for (int f = 0; f < frames; f++) {
                                spins[f] = random.nextInt(2_000);
                            }
                            final AtomicIntegerArray runs = new AtomicIntegerArray(frames);
                            final int base = (id * rounds + r) * 64;
                            try (Job<Integer> job = pool.submit(frames, f -> {
                                runs.incrementAndGet(f);
                                spin(spins[f]);
                                return base + f;
                            }, context(pool, null))) {
                                for (int f = 0; f < frames; f++) {
                                    if (job.await(f) != base + f) {
                                        wrong.incrementAndGet();
                                    }
                                }
                            }
                            for (int f = 0; f < frames; f++) {
                                final int n = runs.get(f);
                                if (n == 0) {
                                    lost.incrementAndGet();
                                } else if (n > 1) {
                                    repeated.incrementAndGet();
                                }
                            }
                        }
                    } catch (final Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                }));
            }
            assertThat(done.await(100, TimeUnit.SECONDS)).isTrue();
        }
        for (final Thread t : threads) {
            assertThat(t.join(Duration.ofSeconds(TIMEOUT_SECONDS))).isTrue();
        }
        assertThat(failures).as("seed=" + SEED).isEmpty();
        assertThat(wrong).as("seed=" + SEED).hasValue(0);
        assertThat(lost).as("seed=" + SEED).hasValue(0);
        assertThat(repeated).as("seed=" + SEED).hasValue(0);
    }

    private static QueryContext context(final QueryPool pool, final CdbCancel cancel) {
        return new QueryContext(cancel, pool);
    }

    // a frame's stand-in work, which the JIT keeps
    private static void spin(final int iterations) {
        for (int i = 0; i < iterations; i++) {
            Thread.onSpinWait();
        }
    }

    private static void awaitLatch(final CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("time out waiting for the test's gate");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the test's gate", e);
        }
    }

    private static void assertEventually(final BooleanSupplier condition, final String what) {
        final long start = System.nanoTime();
        final long boundNanos = TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start - boundNanos > 0L) {
                fail("condition not reached [what=" + what + ", waitSeconds=" + TIMEOUT_SECONDS + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(backoffNanos * 2L, TimeUnit.MILLISECONDS.toNanos(10L));
        }
    }

    private static Throwable failure(final boolean error) {
        return error ? new AssertionError("frame failed") : new IllegalStateException("frame failed");
    }

    private static RuntimeException unchecked(final Throwable failure) {
        if (failure instanceof final Error e) {
            throw e;
        }
        return (RuntimeException) failure;
    }

    // the live cdb-query threads started since the snapshot
    private static List<Thread> newWorkers(final Set<Thread> before) {
        final List<Thread> out = new ArrayList<>();
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(WORKER) && !before.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
