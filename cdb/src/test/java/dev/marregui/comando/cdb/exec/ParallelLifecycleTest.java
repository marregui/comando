package dev.marregui.comando.cdb.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.ColumnUse;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// A parallel query's lifecycle: a cancel is answered at the next frame and starts no other, a failing frame fails the
/// query and lets go of every partition it read, queries sharing one pool answer as alone, and a pool whose only worker
/// is busy still answers. Tests that need the frames of a real pool are skipped while a segment is one frame.
class ParallelLifecycleTest {

    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long START_MICROS = 1_793_491_200_000_000L;
    private static final long NOW_MICROS = START_MICROS + 10 * DAY_MICROS;
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // the bound the feature promises between a cancel and the query's answer
    private static final long CANCEL_MILLIS = 50L;
    // far above any answer of these tables on a loaded laptop; reached only by a hang
    private static final long TIMEOUT_SECONDS = 60L;
    // the bound of a wait for a let-go folder to be deleted: the applier publishes within its 5 s schedule
    private static final long EVENTUALLY_NANOS = TimeUnit.SECONDS.toNanos(30L);
    // the store's direct-commit threshold: a commit this large rewrites an out-of-order partition into a new folder
    private static final int REWRITE_ROWS = 100_000;
    private static final String D = "SELECT * FROM b LATEST ON time PARTITION BY status";
    private static final String F = "SELECT count() FROM b WHERE status = 'warning'";
    private static final String FAILING = "SELECT count() c FROM f WHERE s < ts";

    @TempDir
    static Path root;

    private static Store store;
    private static TwoSeedRandom random;

    @BeforeAll
    static void writeTables() {
        random = TwoSeedRandom.of("cdb.parallel", "ParallelLifecycleTest");
        store = Store.open(root);
        writeBench();
        writeFailing();
    }

    @AfterAll
    static void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    /// The pool checks the cancel before every frame: the await after the frame that cancelled throws at once, no
    /// frame starts once the cancel is seen, and none runs after the job is closed.
    @Test
    void cancelStopsTheJobAtTheNextFrame() {
        final int frames = 64;
        final int cancelAt = 5;
        try (final QueryPool pool = QueryPool.of(3)) {
            final CdbCancel cancel = new CdbCancel();
            final QueryContext ctx = new QueryContext(cancel, pool);
            final AtomicInteger started = new AtomicInteger();
            final AtomicInteger startedAfterCancel = new AtomicInteger();
            final AtomicLong cancelledAtNanos = new AtomicLong();
            final Job<Long> job = pool.submit(frames, frame -> {
                if (cancel.cancelled()) {
                    startedAfterCancel.incrementAndGet();
                }
                started.incrementAndGet();
                if (frame == cancelAt) {
                    cancelledAtNanos.set(System.nanoTime());
                    cancel.cancel();
                }
                return spin(frame);
            }, ctx);
            long thrownAtNanos = 0L;
            int answered = 0;
            try {
                for (int frame = 0; frame < frames; frame++) {
                    job.await(frame);
                    answered++;
                }
            } catch (final CdbException _) {
                thrownAtNanos = System.nanoTime();
            } finally {
                job.close();
            }
            final int afterClose = started.get();
            runToTheEnd(pool, frames);

            assertThat(thrownAtNanos).as("cancelled job threw").isNotZero();
            // a cancel fails every await from then on, frames already done included: the query stops at once
            assertThat(answered).as("frames answered").isLessThanOrEqualTo(cancelAt + 1);
            assertThat(TimeUnit.NANOSECONDS.toMillis(thrownAtNanos - cancelledAtNanos.get())).as("cancel to answer, ms")
                    .isLessThanOrEqualTo(CANCEL_MILLIS);
            // a worker may have passed its check just before the cancel
            assertThat(startedAfterCancel.get()).as("frames started after the cancel")
                    .isLessThanOrEqualTo(pool.threads());
            assertThat(started.get()).as("frames started after close").isEqualTo(afterClose);
        }
    }

    /// A query cancelled before it starts runs no frame and fails.
    @Test
    void cancelledQueryFails() {
        final Plan plan = ParallelRows.plan(store, "SELECT status, count() c FROM b GROUP BY status", NOW_MICROS);
        try (final QueryPool pool = QueryPool.of(3)) {
            final CdbCancel cancel = new CdbCancel();
            cancel.cancel();
            assertThatThrownBy(() -> ParallelRows.drain(
                    Executor.open(plan, store, NOW_MICROS, new QueryContext(cancel, pool))))
                    .isInstanceOf(CdbException.class);
        }
    }

    /// A cancel during a GROUP BY over many frames is answered within 50 ms, the best of three cancels: each comes at a
    /// third of the query's uncancelled time, earlier on a retry when the query ended first.
    @Test
    void cancelDuringGroupByAnswersWithin50Millis() throws InterruptedException {
        try (final QueryPool pool = QueryPool.of(3)) {
            // the bench table spans several frames once frames exist; the heavy table is written only then
            assumeTrue(frames(ParallelRows.plan(store, "SELECT status, count() c FROM b GROUP BY status", NOW_MICROS),
                    pool) > 1, "a segment is one frame: no frame to cancel between");
            writeHeavy("g");
            final Plan plan = ParallelRows.plan(store, "SELECT k, count() c, count_distinct(s) cd_s,"
                    + " approx_percentile(p, 0.9) ap_p, sum(p) sum_p FROM g GROUP BY k", NOW_MICROS);
            final long startedNanos = System.nanoTime();
            ParallelRows.drain(Executor.open(plan, store, NOW_MICROS, new QueryContext(null, pool)));
            long delayNanos = (System.nanoTime() - startedNanos) / 3;
            // the best of up to three cancels that came before the query ended: a cancel never checked at a page
            // misses the bound on every one, a runner's scheduling stall on one only; three more tries, at halved
            // delays, for the cancels that came after the query ended
            long latencyNanos = -1L;
            int cancels = 0;
            for (int attempt = 0; attempt < 6 && cancels < 3; attempt++) {
                final long latency = cancelAfter(plan, pool, delayNanos);
                if (latency < 0) {
                    delayNanos /= 2;
                } else {
                    cancels++;
                    latencyNanos = latencyNanos < 0 ? latency : Math.min(latencyNanos, latency);
                }
            }
            assertThat(latencyNanos).as("a cancel came before the query ended").isNotNegative();
            assertThat(TimeUnit.NANOSECONDS.toMillis(latencyNanos)).as("cancel to answer, ms")
                    .isLessThanOrEqualTo(CANCEL_MILLIS);
        }
    }

    /// A frame that fails, one past the first, fails the query on every pool size, and the query lets go of every
    /// partition version it read: a later rewrite of the partition deletes the old folder, which a held reader keeps.
    @Test
    void failingFrameFailsTheQueryAndReleasesEveryReader() {
        final Path dir = root.resolve("f");
        // the control: the folder a reader holds outlives a rewrite, and goes when the reader closes
        final Set<String> held = folders(dir);
        final TableReader reader = store.reader("f");
        try {
            rewrite();
            assertThat(folders(dir)).as("folders while a reader holds them").containsAll(held);
        } finally {
            reader.close();
        }
        assertGone(dir, held);

        final Plan plan = ParallelRows.plan(store, FAILING, NOW_MICROS);
        for (final int size : new int[] {0, 1, 3, 11}) {
            final Set<String> read = folders(dir);
            try (final QueryPool pool = QueryPool.of(size)) {
                assertThatThrownBy(() -> ParallelRows.drain(
                        Executor.open(plan, store, NOW_MICROS, new QueryContext(null, pool))))
                        .as("query on a pool of %d", size)
                        .isInstanceOf(CdbException.class);
            }
            rewrite();
            assertGone(dir, read);
        }
    }

    /// Eight threads run D and F of the bench a hundred times each on one pool; every answer equals the serial one.
    @Test
    void concurrentQueriesOnOnePoolAnswerAsAlone() throws InterruptedException {
        final int threads = 8;
        final int rounds = 100;
        final Plan[] plans = {ParallelRows.plan(store, D, NOW_MICROS), ParallelRows.plan(store, F, NOW_MICROS)};
        final ParallelRows.Answer[] expected = new ParallelRows.Answer[plans.length];
        for (int i = 0; i < plans.length; i++) {
            expected[i] = ParallelRows.drain(SerialPlans.open(plans[i], store, NOW_MICROS));
        }
        final CyclicBarrier start = new CyclicBarrier(threads);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger answers = new AtomicInteger();
        final AtomicInteger mismatches = new AtomicInteger();
        final AtomicReference<String> firstMismatch = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Thread> workers = new ArrayList<>(threads);
        try (final QueryPool pool = QueryPool.of(3)) {
            try {
                for (int t = 0; t < threads; t++) {
                    final int first = t;
                    // platform threads: the queries are CPU-bound
                    workers.add(Thread.ofPlatform().name("parallel-lifecycle-test-" + t).start(() -> {
                        try {
                            start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            for (int r = 0; r < rounds; r++) {
                                final int q = (first + r) % plans.length;
                                final String difference = ParallelRows.drain(Executor.open(plans[q], store,
                                        NOW_MICROS, new QueryContext(null, pool))).differenceFrom(expected[q], true);
                                if (difference != null) {
                                    mismatches.incrementAndGet();
                                    firstMismatch.compareAndSet(null, (q == 0 ? D : F) + ": " + difference);
                                }
                                answers.incrementAndGet();
                            }
                        } catch (final InterruptedException e) {
                            failure.compareAndSet(null, e);
                            Thread.currentThread().interrupt();
                        } catch (final Exception | Error e) {
                            failure.compareAndSet(null, e);
                        } finally {
                            done.countDown();
                        }
                    }));
                }
                assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("every thread done").isTrue();
            } finally {
                stop(workers);
            }
        }
        assertThat(failure.get()).as("a thread's failure").isNull();
        assertThat(firstMismatch.get()).as("first mismatch").isNull();
        assertThat(mismatches.get()).isZero();
        assertThat(answers.get()).isEqualTo(threads * rounds);
    }

    /// A query on a pool of one worker, held by another job's frame, runs its frames on its own thread and answers
    /// the serial rows within the timeout instead of waiting for the worker.
    @Test
    void heldWorkerDoesNotDeadlockAQuery() throws InterruptedException {
        final Plan plan = ParallelRows.plan(store, "SELECT status, count() c, avg(temperature) avg_t FROM b"
                + " GROUP BY status", NOW_MICROS);
        final ParallelRows.Answer expected = ParallelRows.drain(SerialPlans.open(plan, store, NOW_MICROS));
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch answered = new CountDownLatch(1);
        final AtomicReference<ParallelRows.Answer> answer = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final boolean workerHeld;
        try (final QueryPool pool = QueryPool.of(1)) {
            final Job<Integer> holder = pool.submit(1, _ -> {
                workerStarted.countDown();
                try {
                    if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("holding frame not released");
                    }
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            }, QueryContext.uncancellable());
            Thread query = null;
            try {
                // a second is ample for an idle worker to start a frame; a pool that runs frames only at their await
                // never holds it
                workerHeld = workerStarted.await(1, TimeUnit.SECONDS);
                query = Thread.ofPlatform().name("parallel-lifecycle-test-query").start(() -> {
                    try {
                        answer.set(ParallelRows.drain(
                                Executor.open(plan, store, NOW_MICROS, new QueryContext(null, pool))));
                    } catch (final RuntimeException | Error e) {
                        failure.set(e);
                    } finally {
                        answered.countDown();
                    }
                });
                assertThat(answered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("query answered").isTrue();
            } finally {
                release.countDown();
                holder.close();
                if (query != null) {
                    stop(List.of(query));
                }
            }
        }
        assertThat(failure.get()).as("the query's failure").isNull();
        assertThat(answer.get().differenceFrom(expected, true)).isNull();
        assumeTrue(workerHeld, "the pool started no frame before its await: no worker to hold");
    }

    // the latency from the cancel to the query's failure, -1 when the query answered first
    private static long cancelAfter(final Plan plan, final QueryPool pool, final long delayNanos)
            throws InterruptedException {
        final CdbCancel cancel = new CdbCancel();
        final AtomicLong cancelledAtNanos = new AtomicLong();
        final CountDownLatch finished = new CountDownLatch(1);
        final Thread canceller = Thread.ofPlatform().name("parallel-lifecycle-test-canceller").start(() -> {
            try {
                // the query's end stops the wait; else the delay runs out and the cancel goes
                if (!finished.await(delayNanos, TimeUnit.NANOSECONDS)) {
                    cancelledAtNanos.set(System.nanoTime());
                    cancel.cancel();
                }
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            ParallelRows.drain(Executor.open(plan, store, NOW_MICROS, new QueryContext(cancel, pool)));
            return -1L;
        } catch (final CdbException _) {
            return System.nanoTime() - cancelledAtNanos.get();
        } finally {
            finished.countDown();
            stop(List.of(canceller));
        }
    }

    private static int frames(final Plan plan, final QueryPool pool) {
        if (!(plan instanceof Plan.Aggregate(final Plan child, _, _, _))) {
            throw new IllegalStateException("not a GROUP BY [plan=" + plan + ']');
        }
        try (final FrameSource source = FrameSource.of(child, store, NOW_MICROS, ColumnUse.of(plan),
                new QueryContext(null, pool))) {
            return source.frames();
        }
    }

    // a job of frames frames awaited to its end, so a frame an earlier job left queued would have run by now
    private static void runToTheEnd(final QueryPool pool, final int frames) {
        try (final Job<Long> job = pool.submit(frames, ParallelLifecycleTest::spin, QueryContext.uncancellable())) {
            for (int frame = 0; frame < frames; frame++) {
                job.await(frame);
            }
        }
    }

    // a frame's worth of work the JIT cannot drop
    private static long spin(final int frame) {
        long x = frame;
        for (int i = 0; i < 200_000; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        return x;
    }

    private static void stop(final List<Thread> threads) throws InterruptedException {
        for (final Thread thread : threads) {
            thread.interrupt();
        }
        for (final Thread thread : threads) {
            assertThat(thread.join(Duration.ofSeconds(TIMEOUT_SECONDS))).as(thread.getName()).isTrue();
        }
    }

    // the partition version folders of the table in dir
    private static Set<String> folders(final Path dir) {
        try (final Stream<Path> entries = Files.list(dir)) {
            final Set<String> out = new TreeSet<>();
            entries.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("2026-")).forEach(out::add);
            return out;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // the folders go once nothing holds them, deleted by the thread that lets go of the last version, the writer's
    // or the applier's
    private static void assertGone(final Path dir, final Set<String> old) {
        final long start = System.nanoTime();
        long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
        while (!kept(dir, old).isEmpty()) {
            if (System.nanoTime() - start - EVENTUALLY_NANOS > 0L) {
                fail("partition folders still held after a rewrite [folders=" + kept(dir, old) + ", waitMillis="
                        + TimeUnit.NANOSECONDS.toMillis(EVENTUALLY_NANOS) + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(backoffNanos * 2L, TimeUnit.MILLISECONDS.toNanos(10L));
        }
    }

    private static Set<String> kept(final Path dir, final Set<String> old) {
        final Set<String> left = folders(dir);
        left.retainAll(old);
        return left;
    }

    // out-of-order rows into the table's one day, in a commit large enough to write a new partition folder
    private static void rewrite() {
        try (final TableWriter writer = store.writer("f", WAIT_MILLIS)) {
            for (int r = 0; r < REWRITE_ROWS; r++) {
                writer.newRow(START_MICROS + 1 + r);
                writer.putText(1, "2026-11-01T00:00:00Z");
                writer.putLong(2, r);
                writer.endRow();
            }
            writer.commit();
        }
    }

    // ts, s, v over one day: s a valid timestamp text on every row but one, past the first frame
    private static void writeFailing() {
        store.create(new TableDef("f", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("s", CdbType.VARCHAR),
                new ColumnDef("v", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0L), false);
        final int rows = 3 * Frames.FRAME_ROWS + 1_000;
        final int bad = 2 * Frames.FRAME_ROWS + random.nextInt(Frames.FRAME_ROWS);
        try (final TableWriter writer = store.writer("f", WAIT_MILLIS)) {
            for (int r = 0; r < rows; r++) {
                writer.newRow(START_MICROS + (long) r * 400_000L);
                writer.putText(1, r == bad ? "bad" : "2026-11-01T12:00:00Z");
                writer.putLong(2, r);
                writer.endRow();
            }
            writer.commit();
        }
    }

    // the bench's shape: time, seq, temperature, humidity, status over three days
    private static void writeBench() {
        store.create(new TableDef("b", List.of(
                new ColumnDef("time", CdbType.TIMESTAMP),
                new ColumnDef("seq", CdbType.LONG),
                new ColumnDef("temperature", CdbType.DOUBLE),
                new ColumnDef("humidity", CdbType.DOUBLE),
                new ColumnDef("status", CdbType.VARCHAR)), 0, PartitionBy.DAY, List.of(), 0L), false);
        final String[] statuses = {"ok", "warning", "critical", "unknown"};
        final int rows = 240_000;
        final long stepMicros = 3 * DAY_MICROS / rows;
        try (final TableWriter writer = store.writer("b", WAIT_MILLIS)) {
            for (int r = 0; r < rows; r++) {
                writer.newRow(START_MICROS + r * stepMicros);
                writer.putLong(1, r);
                writer.putDouble(2, 15 + random.nextDouble() * 20);
                writer.putDouble(3, random.nextDouble() * 100);
                writer.putText(4, statuses[random.nextInt(statuses.length)]);
                writer.endRow();
            }
            writer.commit();
        }
    }

    // a GROUP BY's worth of work per row: many keys, distinct texts and a percentile over two days
    private static void writeHeavy(final String table) {
        store.create(new TableDef(table, List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("k", CdbType.LONG),
                new ColumnDef("s", CdbType.VARCHAR),
                new ColumnDef("p", CdbType.DOUBLE)), 0, PartitionBy.DAY, List.of(), 0L), true);
        final int rows = 800_000;
        final long stepMicros = 2 * DAY_MICROS / rows;
        try (final TableWriter writer = store.writer(table, WAIT_MILLIS)) {
            for (int r = 0; r < rows; r++) {
                writer.newRow(START_MICROS + r * stepMicros);
                writer.putLong(1, random.nextInt(5_000));
                writer.putText(2, "v" + random.nextInt(50_000));
                writer.putDouble(3, random.nextDouble() * 1_000);
                writer.endRow();
            }
            writer.commit();
        }
    }
}
