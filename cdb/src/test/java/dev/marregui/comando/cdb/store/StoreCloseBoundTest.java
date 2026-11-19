package dev.marregui.comando.cdb.store;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A store's close waits a bounded time for what may still write a table's files, and past it fails keeping the
/// root's lock, so no store opens the root under them:
///
/// - an apply holding a table's apply lock past the close's wait
/// - a timed log force still in flight past the forcer's wait
/// - a table whose close throws: the tables after it are still closed, or kept for the next close
///
/// Once they ended, a later close closes what is left and releases the lock, and the root opens with every answered
/// commit. The force and throw cases set [StoreFiles#recorder], a static: safe because cdb's tests run no two classes
/// at once in one JVM, and the test resets it.
class StoreCloseBoundTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final TableDef N = new TableDef("n", M.columns(), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // the close's wait in these tests: short, so the test does not sit out the store's own ten seconds
    private static final long CLOSE_WAIT_MILLIS = 200L;
    // the most a close may take past its wait on a loaded machine before the test calls it unbounded
    private static final long CLOSE_SLACK_MILLIS = 5_000L;
    // the bound of a wait for the helper thread or the forcer to reach its point: only a hang reaches it
    private static final long LATCH_SECONDS = 60L;
    // an interval no test waits out: the only force is the close's last round
    private static final long LONG_INTERVAL_MILLIS = 60_000L;

    /// Another thread holds the table's apply lock: the close throws a `CdbException` within its wait plus slack and
    /// keeps the root's lock, so a second store cannot open the root. Once the lock is let go, a later close succeeds
    /// and the root opens with the committed row.
    @Test
    void aCloseFailsKeepingTheRootLockWhileAnApplyHoldsTheTable(@TempDir final Path root) throws InterruptedException {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean releasedInTime = new AtomicBoolean();
        Thread holder = null;
        final Store store = Store.open(root);
        try {
            store.closeWaitMillis = CLOSE_WAIT_MILLIS;
            store.create(M, false);
            commit(store, 1.0, TS);
            // applied by the read, so the applier has nothing queued that would block it on the lock
            assertThat(read(store)).containsExactly("1.0|" + TS);
            store.applierPaused = true;
            final TableState state = store.states().iterator().next();
            holder = Thread.ofPlatform().name("store-close-bound-test-apply").start(() -> {
                state.applyLock.lock();
                try {
                    held.countDown();
                    releasedInTime.set(await(release));
                } finally {
                    state.applyLock.unlock();
                }
            });
            assertThat(held.await(LATCH_SECONDS, TimeUnit.SECONDS)).as("the apply lock taken").isTrue();
            final long startNanos = System.nanoTime();
            assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
            final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertThat(tookMillis).isLessThan(CLOSE_WAIT_MILLIS + CLOSE_SLACK_MILLIS);
            assertThatThrownBy(() -> Store.open(root)).isInstanceOf(CdbException.class);
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(LATCH_SECONDS));
            assertThat(holder.isAlive()).as("the apply lock holder ended").isFalse();
            assertThat(releasedInTime).as("the holder released by the test, not by its timeout").isTrue();
            store.close();
        } finally {
            release.countDown();
            if (holder != null) {
                holder.join(TimeUnit.SECONDS.toMillis(LATCH_SECONDS));
            }
            // a no-op after the close above; else the close the test did not reach, the holder let go
            store.close();
        }
        try (final Store reopened = Store.open(root)) {
            assertThat(read(reopened)).containsExactly("1.0|" + TS);
        }
    }

    /// The timed forcer's last force of the log blocks: the close throws a `CdbException` once the forcer's wait ran
    /// out, keeps the root's lock and closes no table under the force. Once the force is let go, a later close
    /// succeeds and the root opens with the committed row.
    @Test
    void aCloseFailsKeepingTheRootLockWhileALogForceRuns(@TempDir final Path root) throws InterruptedException {
        final AtomicBoolean blocking = new AtomicBoolean();
        final CountDownLatch forcing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        StoreFiles.recorder = new Silent() {
            @Override
            public void forcing(final Path file) {
                if (file.getFileName().toString().equals(Wal.FILE) && blocking.compareAndSet(true, false)) {
                    forcing.countDown();
                    await(release);
                }
            }
        };
        try {
            final Store store = Store.open(root, new StoreOptions(LONG_INTERVAL_MILLIS));
            try {
                store.create(M, false);
                // answered before its force, which only the close's last round makes
                commit(store, 1.0, TS);
                final LogForcer forcer = store.forcer();
                forcer.joinMillis = CLOSE_WAIT_MILLIS;
                blocking.set(true);
                assertThatThrownBy(store::close).isInstanceOf(CdbException.class);
                assertThat(forcing.await(LATCH_SECONDS, TimeUnit.SECONDS)).as("the last force in flight").isTrue();
                assertThatThrownBy(() -> Store.open(root)).isInstanceOf(CdbException.class);
                release.countDown();
                // the force let go may take more than the short wait to end on a loaded machine
                forcer.joinMillis = TimeUnit.SECONDS.toMillis(LATCH_SECONDS);
                store.close();
            } finally {
                release.countDown();
                // a no-op after the close above; else the close the test did not reach, the force let go
                store.forcer().joinMillis = TimeUnit.SECONDS.toMillis(LATCH_SECONDS);
                store.close();
            }
            StoreFiles.recorder = null;
            try (final Store reopened = Store.open(root)) {
                assertThat(read(reopened)).containsExactly("1.0|" + TS);
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    /// The close's checkpoint of the first table it closes throws an `Error`: the close throws it and keeps the root's
    /// lock, and records the failed table and every table after it as still to close. A later close closes each
    /// table, the failed one without a checkpoint, then releases the lock, and the root opens with both tables' rows.
    @Test
    void aTableWhoseCloseThrowsLeavesEveryTableForTheNextClose(@TempDir final Path root) {
        final Thread closing = Thread.currentThread();
        final AtomicBoolean failing = new AtomicBoolean();
        StoreFiles.recorder = new Silent() {
            @Override
            public void wrote(final Path file, final long position, final ByteBuffer bytes) {
                if (Thread.currentThread() == closing && file.getFileName().toString().endsWith(".d")
                        && failing.compareAndSet(true, false)) {
                    throw new InternalError("injected column write error on close [file=" + file + ']');
                }
            }
        };
        try {
            final Store store = Store.open(root);
            try {
                store.create(M, false);
                store.create(N, false);
                // queued, so the close's checkpoint applies them on this thread
                store.applierPaused = true;
                commit(store, M, 1.0, TS);
                commit(store, N, 2.0, TS);
                failing.set(true);
                assertThatThrownBy(store::close).isInstanceOf(InternalError.class);
                assertThat(failing).as("the first checkpoint's write failed").isFalse();
                assertThatThrownBy(() -> Store.open(root)).isInstanceOf(CdbException.class);
                store.close();
                assertThat(store.states()).allSatisfy(state -> assertThat(state.closed()).as(state.key).isTrue());
            } finally {
                failing.set(false);
                // a no-op after the close above; else the close the test did not reach
                store.close();
            }
            StoreFiles.recorder = null;
            try (final Store reopened = Store.open(root)) {
                assertThat(read(reopened, M)).containsExactly("1.0|" + TS);
                assertThat(read(reopened, N)).containsExactly("2.0|" + TS);
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    // a bounded wait for the test to let go; false when it ran out, the interrupt kept
    private static boolean await(final CountDownLatch latch) {
        try {
            return latch.await(LATCH_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void commit(final Store store, final double value, final long ts) {
        commit(store, M, value, ts);
    }

    private static void commit(final Store store, final TableDef def, final double value, final long ts) {
        try (final TableWriter writer = store.writer(def.name(), WAIT_MILLIS)) {
            writer.newRow(ts);
            writer.putDouble(0, value);
            writer.endRow();
            writer.commit();
        }
    }

    private static List<String> read(final Store store) {
        return read(store, M);
    }

    private static List<String> read(final Store store, final TableDef def) {
        try (final TableReader reader = store.reader(def.name())) {
            return rows(reader);
        }
    }

    /// A recorder that sees nothing; a test overrides the operation it blocks.
    private static class Silent implements StoreFiles.Recorder {

        @Override
        public void created(final Path path) {
        }

        @Override
        public void wrote(final Path file, final long position, final ByteBuffer bytes) {
        }

        @Override
        public void truncated(final Path file, final long size) {
        }

        @Override
        public void forcing(final Path file) {
        }

        @Override
        public void folderForcing(final Path folder) {
        }

        @Override
        public void moved(final Path from, final Path to) {
        }

        @Override
        public void deleted(final Path path) {
        }
    }
}
