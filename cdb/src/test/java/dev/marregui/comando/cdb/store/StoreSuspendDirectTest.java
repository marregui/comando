package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// A direct commit that passed the writer's suspended check while the applier's last failed batch still held the
/// table's apply lock: once the applier suspends the table and lets the lock go, the commit applies the queued
/// commits on its own thread before its own, so its transaction follows every answered one and its checkpoint cuts
/// none of them from the log.
///
/// The case sets [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one JVM, and
/// the test resets it.
class StoreSuspendDirectTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer holds the lease in this test but the blocked direct commit, which the test lets go
    private static final long WAIT_MILLIS = 5_000L;
    // the applier's failures that suspend a table: the third is held until the direct commit waits on the lock
    private static final int SUSPENDING_FAILURE = 3;
    // a commit of this many rows or more takes the direct path
    private static final int DIRECT_ROWS = 2;
    // a lag-0 read the failing applier cannot serve gives up after this, and demands a batch each time
    private static final long SHORT_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(200L);
    // the bound of a wait for a thread to reach its point: only a hang reaches it
    private static final long LATCH_SECONDS = 60L;

    /// Column writes fail on the applier's thread only. Its third failed batch, the one that suspends the table, is
    /// held until a two-row direct commit waits on the apply lock, then let go: the direct commit is answered, and a
    /// reopen reads all four rows, the two logged commits' included.
    @Test
    void aDirectCommitQueuedBehindTheSuspendingBatchLosesNoAnsweredCommit(@TempDir final Path root)
            throws InterruptedException {
        final AtomicBoolean failing = new AtomicBoolean(true);
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        StoreFiles.recorder = new Silent() {
            @Override
            public void wrote(final Path file, final long position, final ByteBuffer bytes) {
                if (failing.get() && file.getFileName().toString().endsWith(".d")
                        && Thread.currentThread().getName().startsWith("cdb-applier-")) {
                    if (failures.incrementAndGet() == SUSPENDING_FAILURE) {
                        entered.countDown();
                        await(release);
                    }
                    throw new UncheckedIOException(new IOException("injected applier write failure [file=" + file
                            + ']'));
                }
            }
        };
        final AtomicReference<Throwable> refused = new AtomicReference<>();
        final AtomicBoolean answered = new AtomicBoolean();
        Thread direct = null;
        try {
            final Store store = Store.open(root);
            try {
                store.create(M, false);
                store.readWaitNanos = SHORT_WAIT_NANOS;
                store.directRows = DIRECT_ROWS;
                commit(store, 0, 1);
                commit(store, 1, 1);
                final TableState state = store.states().iterator().next();
                final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_SECONDS);
                // each lag-0 read demands a batch, which fails, until the third is held
                while (!entered.await(10L, TimeUnit.MILLISECONDS)) {
                    if (System.nanoTime() - deadlineNanos > 0L) {
                        fail("the suspending batch not reached [waitSeconds=" + LATCH_SECONDS + ']');
                    }
                    tryRead(store);
                }
                direct = Thread.ofPlatform().name("store-suspend-direct-test-commit").start(() -> {
                    try {
                        commit(store, 2, DIRECT_ROWS);
                        answered.set(true);
                    } catch (final RuntimeException e) {
                        refused.set(e);
                    }
                });
                long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
                while (!state.applyLock.hasQueuedThread(direct)) {
                    if (System.nanoTime() - deadlineNanos > 0L) {
                        fail("the direct commit never waited on the apply lock [waitSeconds=" + LATCH_SECONDS + ']');
                    }
                    LockSupport.parkNanos(backoffNanos);
                    backoffNanos = Math.min(2L * backoffNanos, TimeUnit.MILLISECONDS.toNanos(10L));
                }
                release.countDown();
                direct.join(TimeUnit.SECONDS.toMillis(LATCH_SECONDS));
                assertThat(direct.isAlive()).as("the direct commit ended").isFalse();
                assertThat(refused.get()).as("the direct commit's failure").isNull();
                assertThat(answered).as("the direct commit answered").isTrue();
                assertThat(store.walStatus().getFirst().suspended()).isTrue();
            } finally {
                release.countDown();
                if (direct != null) {
                    direct.join(TimeUnit.SECONDS.toMillis(LATCH_SECONDS));
                }
                failing.set(false);
                store.close();
            }
            StoreFiles.recorder = null;
            try (final Store reopened = Store.open(root)) {
                assertThat(read(reopened)).containsExactly(
                        "0.0|" + TS, "1.0|" + (TS + 1L), "2.0|" + (TS + 2L), "3.0|" + (TS + 3L));
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    // a bounded wait for the test to let go, the interrupt kept
    private static void await(final CountDownLatch latch) {
        try {
            latch.await(LATCH_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    // one commit of `rows` rows, valued from `first` up, each at TS plus its value
    private static void commit(final Store store, final int first, final int rows) {
        try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
            for (int r = 0; r < rows; r++) {
                writer.newRow(TS + first + r);
                writer.putDouble(0, first + r);
                writer.endRow();
            }
            writer.commit();
        }
    }

    // a lag-0 read that the failing applier cannot serve: it gives up after its wait, the batch demanded
    private static void tryRead(final Store store) {
        try (final TableReader reader = store.reader(M.name())) {
            rows(reader);
        } catch (final RuntimeException _) {
            // expected while the applier fails
        }
    }

    private static List<String> read(final Store store) {
        try (final TableReader reader = store.reader(M.name())) {
            return rows(reader);
        }
    }

    /// A recorder that sees nothing; the test overrides the operation it fails.
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
