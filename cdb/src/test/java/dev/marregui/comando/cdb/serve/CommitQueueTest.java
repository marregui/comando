package dev.marregui.comando.cdb.serve;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The queue of `/write` batches in front of recording appenders: one commit per table at a time, the batches queued
/// meanwhile written together in the next one, tables committed in parallel, a refused row failing only its own
/// request, and a close that commits what is queued and refuses what comes after.
class CommitQueueTest {

    /// Ten seconds only bounds a hung test; every wait here ends in milliseconds when the queue works.
    private static final long TIMEOUT_SECONDS = 10L;

    /// The batches queued while a table's commit runs go into its next commit together: one appender, one flush.
    @Test
    void batchesQueuedDuringACommitShareTheNext() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final Appenders appenders = new Appenders(release);
        final CommitQueue queue = new CommitQueue(appenders);
        final Writers writers = new Writers();

        writers.start(() -> queue.write(body("t", 0)));
        awaitTrue(() -> appenders.opened.size() == 1);
        for (int i = 1; i < 20; i++) {
            final int n = i;
            writers.start(() -> queue.write(body("t", n)));
        }
        awaitTrue(() -> queue.queued("t") == 19);
        release.countDown();
        writers.join();

        assertThat(writers.failures).isEmpty();
        assertThat(writers.refusals).isEmpty();
        assertThat(appenders.opened).hasSize(2);
        assertThat(appenders.opened.get(1).flushed.stream().filter(c -> c.startsWith("at(")).count()).isEqualTo(19);
        assertThat(appenders.opened).allSatisfy(a -> assertThat(a.closes()).isEqualTo(1));
    }

    /// Two tables commit at once: each commit's flush waits for the other's at a barrier, which only meets when the
    /// two run in parallel; serialised, the first flush would time out.
    @Test
    void twoTablesCommitInParallel() throws Exception {
        final CyclicBarrier both = new CyclicBarrier(2);
        final Appenders appenders = new Appenders(() -> await(both));
        final CommitQueue parallel = new CommitQueue(appenders);
        final Writers writers = new Writers();
        final long start = System.nanoTime();

        writers.start(() -> parallel.write(body("a", 1)));
        writers.start(() -> parallel.write(body("b", 1)));
        writers.join();
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(writers.failures).isEmpty();
        assertThat(writers.refusals).isEmpty();
        assertThat(both.isBroken()).isFalse();
        assertThat(tookMillis).isLessThan(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertThat(appenders.opened).hasSize(2);
        assertThat(parallel.queued("a")).isZero();
    }

    /// A row the store refuses fails only its own request, with its row in the body; the other batches of the same
    /// commit are written, and so are the refused batch's rows before it.
    @Test
    void aRefusedRowFailsOnlyItsRequest() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final Appenders appenders = new Appenders(release);
        // the second commit's appender refuses its third row: the refused body's second row
        appenders.refuseAt = 3;
        final CommitQueue queue = new CommitQueue(appenders);
        final Writers writers = new Writers();

        writers.start(() -> queue.write(body("t", 0)));
        awaitTrue(() -> appenders.opened.size() == 1);
        final CommitQueue.Body twoRows = body("t", 10);
        twoRows.table("t").symbol("s", "w11").longColumn("v", 11L).at(11L);
        writers.start(() -> queue.write(body("t", 1)));
        awaitTrue(() -> queue.queued("t") == 1);
        writers.start(() -> queue.write(twoRows));
        awaitTrue(() -> queue.queued("t") == 2);
        release.countDown();
        writers.join();

        assertThat(writers.failures).isEmpty();
        assertThat(writers.refusals).singleElement().satisfies(r -> {
            assertThat(r.row()).isEqualTo(1);
            assertThat(r.cause()).isInstanceOf(CdbException.class);
        });
        assertThat(appenders.opened.get(1).flushed).containsSubsequence(
                "table(t)", "symbol(s=w1)", "long(v=1)", "at(1)",
                "table(t)", "symbol(s=w10)", "long(v=10)", "at(10)");
        assertThat(appenders.opened.get(1).flushed).doesNotContain("at(11)");
    }

    /// Close lets the batches already queued commit, and refuses a batch queued after it.
    @Test
    void closeCommitsTheQueuedBatchesAndRefusesNewOnes() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final Appenders appenders = new Appenders(release);
        final CommitQueue queue = new CommitQueue(appenders);
        final Writers writers = new Writers();

        writers.start(() -> queue.write(body("t", 0)));
        awaitTrue(() -> appenders.opened.size() == 1);
        for (int i = 1; i <= 5; i++) {
            final int n = i;
            writers.start(() -> queue.write(body("t", n)));
        }
        awaitTrue(() -> queue.queued("t") == 5);
        queue.close();
        assertThatThrownBy(() -> queue.write(body("t", 6))).isInstanceOf(CdbException.class);
        release.countDown();
        writers.join();

        assertThat(writers.failures).isEmpty();
        assertThat(writers.refusals).isEmpty();
        assertThat(appenders.opened).hasSize(2);
        assertThat(appenders.opened.get(1).flushed.stream().filter(c -> c.startsWith("at(")).toList())
                .containsExactlyInAnyOrder("at(1)", "at(2)", "at(3)", "at(4)", "at(5)");
    }

    private static CommitQueue.Body body(final String table, final int n) {
        final CommitQueue.Body body = new CommitQueue.Body();
        body.table(table).symbol("s", "w" + n).longColumn("v", n).at(n);
        return body;
    }

    private static void await(final CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final Exception e) {
            throw new IllegalStateException("barrier not met", e);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // polls with a doubling pause up to 50 ms, bounded by the test timeout; the server tests of the package share it
    static void awaitTrue(final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        long pauseMillis = 1L;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline).as("condition not met in time").isNegative();
            Thread.sleep(pauseMillis);
            pauseMillis = Math.min(50L, pauseMillis * 2L);
        }
    }

    /// Hands out recording appenders; the first one's flush waits for `release`, the others' run `beforeFlush`.
    private static final class Appenders implements Supplier<CdbAppender> {

        final List<RecordingAppender> opened = new CopyOnWriteArrayList<>();
        private final Runnable firstFlush;
        private final Runnable beforeFlush;
        // written by the test thread before any write, read by committers
        volatile int refuseAt;

        Appenders(final CountDownLatch release) {
            this.firstFlush = () -> await(release);
            this.beforeFlush = () -> { };
        }

        Appenders(final Runnable beforeFlush) {
            this.firstFlush = beforeFlush;
            this.beforeFlush = beforeFlush;
        }

        @Override
        public CdbAppender get() {
            final RecordingAppender recording = new RecordingAppender(opened.isEmpty() ? 0 : refuseAt);
            final Runnable hook = opened.isEmpty() ? firstFlush : beforeFlush;
            opened.add(recording);
            return new HookedAppender(recording, hook);
        }
    }

    /// Records through `delegate`, running `beforeFlush` first on each flush.
    private record HookedAppender(RecordingAppender delegate, Runnable beforeFlush) implements CdbAppender {

        @Override
        public CdbAppender table(final String table) {
            delegate.table(table);
            return this;
        }

        @Override
        public CdbAppender symbol(final String column, final String value) {
            delegate.symbol(column, value);
            return this;
        }

        @Override
        public CdbAppender stringColumn(final String column, final String value) {
            delegate.stringColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender longColumn(final String column, final long value) {
            delegate.longColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender doubleColumn(final String column, final double value) {
            delegate.doubleColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender booleanColumn(final String column, final boolean value) {
            delegate.booleanColumn(column, value);
            return this;
        }

        @Override
        public CdbAppender timestampColumn(final String column, final long micros) {
            delegate.timestampColumn(column, micros);
            return this;
        }

        @Override
        public void at(final long micros) {
            delegate.at(micros);
        }

        @Override
        public void flush() {
            beforeFlush.run();
            delegate.flush();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /// Writes on virtual threads; the outcomes are collected for the test thread to assert once all joined.
    private static final class Writers {

        final Queue<CommitQueue.Refusal> refusals = new ConcurrentLinkedQueue<>();
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        private final List<Thread> threads = new ArrayList<>();
        private final AtomicInteger started = new AtomicInteger();

        void start(final Supplier<CommitQueue.Refusal> write) {
            threads.add(Thread.ofVirtual().name("commit-queue-test-" + started.getAndIncrement()).start(() -> {
                try {
                    final CommitQueue.Refusal refusal = write.get();
                    if (refusal != null) {
                        refusals.add(refusal);
                    }
                } catch (final RuntimeException | Error e) {
                    failures.add(e);
                }
            }));
        }

        void join() throws InterruptedException {
            for (final Thread thread : threads) {
                assertThat(thread.join(Duration.ofSeconds(TIMEOUT_SECONDS))).as(thread.getName()).isTrue();
            }
        }
    }
}
