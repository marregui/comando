package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/// The store's applier writes the commits its logs answered into the column files and publishes them, on its own
/// thread:
///
/// - a read with lag 0 sees every commit answered before it, the thread applying on demand
/// - a read with a lag bound sees the published rows without waiting; a lag-0 read the thread cannot serve throws
/// - a direct commit, the store's close and a reopen apply what is queued; close leaves every log empty
/// - an apply that keeps failing suspends the table, and a reopen replays every answered commit
/// - a store whose queued commits pass the budget reports the pressure and slows its writers to the thread
///
/// The failure case sets [StoreFiles#recorder], a static: safe because cdb's tests run no two classes at once in one
/// JVM, and the test resets it.
class StoreApplyTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // the bound of a wait for the applier thread to act: ten times the store's own read wait would be a hang
    private static final long EVENTUALLY_NANOS = TimeUnit.SECONDS.toNanos(30L);
    // a lag-0 read the paused applier cannot serve gives up after this
    private static final long SHORT_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(200L);

    /// Each of 200 commits, applied by the store's thread alone (no apply on the committing thread), is seen by the
    /// lag-0 read that follows it, with every commit before it.
    @Test
    void aReadSeesEveryCommitAnsweredBeforeIt(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            for (int i = 0; i < 200; i++) {
                commit(store, i, TS + i);
                final List<String> rows = read(store);
                assertThat(rows).as("commit %d", i).hasSize(i + 1);
                assertThat(rows.getLast()).isEqualTo((double) i + "|" + (TS + i));
            }
            final WalStatus status = status(store);
            assertThat(status.appliedTxn()).isEqualTo(status.loggedTxn()).isEqualTo(200L);
            assertThat(status.pendingTxns()).isZero();
        }
    }

    /// With the thread held back, 10 commits stay queued: a read bound to a 60 s lag sees the rows published before
    /// them at once, a lag-0 read gives up after its wait; once the thread runs, a lag-0 read sees all 10, published by
    /// one batch.
    @Test
    void aPausedApplierKeepsCommitsQueuedUntilAReadNeedsThem(@TempDir final Path root) throws Exception {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            for (int i = 0; i < 3; i++) {
                commit(store, i, TS + i);
            }
            final List<String> old = read(store);
            assertThat(old).hasSize(3);
            final WalStatus before = status(store);
            store.applierPaused = true;
            for (int i = 3; i < 13; i++) {
                commit(store, i, TS + i);
            }
            final WalStatus queued = status(store);
            assertThat(queued.loggedTxn()).isEqualTo(queued.appliedTxn() + 10L).isEqualTo(13L);
            assertThat(queued.pendingTxns()).isEqualTo(10L);
            assertThat(queued.pendingRows()).isEqualTo(10L);
            assertThat(queued.pendingBytes()).isPositive();
            assertThat(queued.lagMillis()).isNotNegative();

            store.readWaitNanos = SHORT_WAIT_NANOS;
            // a wait here would run out and throw: the paused thread publishes nothing
            assertThat(ScopedValue.where(Store.READ_MAX_LAG_MILLIS, 60_000L).call(() -> read(store))).isEqualTo(old);
            assertThatThrownBy(() -> read(store)).isInstanceOf(CdbException.class);

            store.readWaitNanos = TimeUnit.SECONDS.toNanos(10L);
            store.applierPaused = false;
            assertThat(read(store)).hasSize(13);
            // the counters move right after the publish that woke the read
            assertEventually(() -> status(store).applies() - before.applies() == 1L, "the batch counted");
            final WalStatus after = status(store);
            assertThat(after.appliedTxn()).isEqualTo(after.loggedTxn()).isEqualTo(13L);
            assertThat(after.pendingTxns()).isZero();
            assertThat(after.applies() - before.applies()).as("batches").isEqualTo(1L);
            assertThat(after.appliedTxns() - before.appliedTxns()).as("commits applied").isEqualTo(10L);
        }
    }

    /// A commit of at least `directRows` rows, with three commits queued before it, applies them first and is written
    /// as the next transaction: all eight rows read in order, nothing stays queued, and `_txn` names it.
    @Test
    void aDirectCommitAppliesTheQueueFirst(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            store.directRows = 5;
            store.applierPaused = true;
            for (int i = 0; i < 3; i++) {
                commit(store, i, TS + i);
            }
            assertThat(status(store).pendingTxns()).isEqualTo(3L);
            try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
                for (int i = 3; i < 8; i++) {
                    row(writer, i, TS + i);
                }
                writer.commit();
            }
            final WalStatus status = status(store);
            assertThat(status.pendingTxns()).isZero();
            assertThat(status.appliedTxn()).isEqualTo(status.loggedTxn()).isEqualTo(4L);
            final List<String> expected = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                expected.add((double) i + "|" + (TS + i));
            }
            // the paused thread applied none of it: a read that had to wait would give up
            store.readWaitNanos = SHORT_WAIT_NANOS;
            assertThat(read(store)).isEqualTo(expected);
            assertThat(txnNumber(root)).isEqualTo(4L);
        }
    }

    /// Five commits queued behind a paused thread are applied and checkpointed by the store's close: the log is back
    /// at its header, `_txn` names the fifth, and the reopened store reads them with nothing to replay.
    @Test
    void closeAppliesAndCheckpointsTheQueue(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            store.applierPaused = true;
            for (int i = 0; i < 5; i++) {
                commit(store, i, TS + i);
            }
            assertThat(status(store).pendingTxns()).isEqualTo(5L);
        }
        assertThat(Files.size(root.resolve(M.name()).resolve(Wal.FILE))).isEqualTo(Wal.HEADER);
        assertThat(txnNumber(root)).isEqualTo(5L);
        try (final Store store = Store.open(root)) {
            assertThat(read(store)).hasSize(5);
            final WalStatus status = status(store);
            assertThat(status.checkpointTxn()).isEqualTo(status.appliedTxn()).isEqualTo(status.loggedTxn())
                    .isEqualTo(5L);
            assertThat(status.logBytes()).isEqualTo(Wal.HEADER);
        }
    }

    /// The folder copied while the store is open, two commits applied and three queued behind the paused thread, is
    /// what a kill leaves: a store opened on the copy replays the log and reads all five.
    @Test
    void aCopyTakenWithCommitsQueuedReplaysThem(@TempDir final Path dir) throws IOException {
        final Path root = dir.resolve("store");
        final Path copy = dir.resolve("copy");
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            commit(store, 0, TS);
            commit(store, 1, TS + 1L);
            assertThat(read(store)).hasSize(2);
            store.applierPaused = true;
            for (int i = 2; i < 5; i++) {
                commit(store, i, TS + i);
            }
            assertThat(status(store).pendingTxns()).isEqualTo(3L);
            copyTree(root, copy);
            try (final Store copied = Store.open(copy)) {
                assertThat(read(copied)).hasSize(5);
                final WalStatus status = status(copied);
                assertThat(status.appliedTxn()).isEqualTo(status.loggedTxn()).isEqualTo(5L);
                assertThat(status.pendingTxns()).isZero();
            }
            store.applierPaused = false;
            assertThat(read(store)).hasSize(5);
        }
    }

    /// The store's thread, `cdb-applier-<root name>`, runs while the store is open and is gone once it closes; a
    /// second close does nothing, and the closed store refuses reads.
    @Test
    void theApplierThreadEndsWithTheStore(@TempDir final Path dir) {
        final Path root = dir.resolve("applier-thread-store");
        final String name = "cdb-applier-applier-thread-store";
        // closed by hand, twice, in the body: the closes are what the test checks; the finally covers a failed assert
        final Store store = Store.open(root);
        try {
            assertThat(applierThreads(name)).hasSize(1).allMatch(Thread::isAlive);
            store.close();
            assertThat(applierThreads(name)).isEmpty();
            store.close();
            assertThat(applierThreads(name)).isEmpty();
            assertThatThrownBy(() -> store.reader(M.name())).isInstanceOf(CdbException.class);
        } finally {
            store.close();
        }
    }

    /// Every write of a column file fails: commits are still answered, since they only log, until the thread's third
    /// failed batch suspends the table; then `walStatus` shows the suspension and its error, a commit is refused and a
    /// lag-0 read throws. Reopened without the failure, the store replays every answered commit.
    @Test
    void anApplyThatKeepsFailingSuspendsTheTable(@TempDir final Path root) {
        final AtomicBoolean failing = new AtomicBoolean(true);
        StoreFiles.recorder = new Silent() {
            @Override
            public void wrote(final Path file, final long position, final ByteBuffer bytes) {
                if (failing.get() && file.getFileName().toString().endsWith(".d")) {
                    throw new UncheckedIOException(new IOException("injected column write failure [file=" + file
                            + ']'));
                }
            }
        };
        try {
            try (final Store store = Store.open(root)) {
                store.create(M, false);
                store.readWaitNanos = SHORT_WAIT_NANOS;
                commit(store, 0, TS);
                commit(store, 1, TS + 1L);
                // each lag-0 read demands a batch, which fails, and gives up after its wait
                assertEventually(() -> {
                    assertThatThrownBy(() -> read(store)).isInstanceOf(CdbException.class);
                    return status(store).lastError() != null;
                }, "a first failed batch");
                assertThat(status(store).suspended()).isFalse();
                commit(store, 2, TS + 2L);
                assertEventually(() -> {
                    assertThatThrownBy(() -> read(store)).isInstanceOf(CdbException.class);
                    return status(store).suspended();
                }, "the table suspended");
                final WalStatus status = status(store);
                assertThat(status.lastError()).isNotNull();
                assertThat(status.loggedTxn()).isEqualTo(3L);
                assertThat(status.appliedTxn()).isZero();
                assertThat(status.pendingTxns()).isEqualTo(3L);
                assertThatThrownBy(() -> commit(store, 3, TS + 3L)).isInstanceOf(CdbException.class);
                assertThatThrownBy(() -> read(store)).isInstanceOf(CdbException.class);
            }
            failing.set(false);
            StoreFiles.recorder = null;
            try (final Store store = Store.open(root)) {
                assertThat(read(store)).containsExactly("0.0|" + TS, "1.0|" + (TS + 1L), "2.0|" + (TS + 2L));
                assertThat(status(store).suspended()).isFalse();
            }
        } finally {
            StoreFiles.recorder = null;
        }
    }

    /// With a budget of one byte every commit passes it: 250 commits that do not wait report a memory pressure of 2
    /// at least once right after they return, 250 that wait for the thread to drain the queue all succeed, every row
    /// is read back, and the pressure is 0 again once the queue is empty.
    @Test
    void commitsOverTheQueuedBudgetReportPressureAndSucceed(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            store.queuedBudgetBytes = 1L;
            // a commit over the budget does not wait: it returns while the thread still has its rows queued
            store.readWaitNanos = 0L;
            int highest = 0;
            for (int i = 0; i < 250; i++) {
                commit(store, i, TS + i);
                highest = Math.max(highest, status(store).memoryPressure());
            }
            assertThat(highest).as("the highest memory pressure seen after a commit").isEqualTo(2);
            store.readWaitNanos = TimeUnit.SECONDS.toNanos(10L);
            for (int i = 250; i < 500; i++) {
                commit(store, i, TS + i);
            }
            assertThat(read(store)).hasSize(500);
            assertEventually(() -> status(store).memoryPressure() == 0, "the pressure back to 0");
            final WalStatus status = status(store);
            assertThat(status.appliedTxn()).isEqualTo(status.loggedTxn()).isEqualTo(500L);
            assertThat(status.pendingBytes()).isZero();
        }
    }

    /// A commit whose text would take a partition's VARCHAR column past its limit is refused before it is logged, so
    /// no answered commit is one the applier refuses: the log and the logged transaction stay where they were.
    @Test
    void aCommitTheApplierWouldRefuseIsRefusedBeforeItsAnswer(@TempDir final Path root) {
        final TableDef notes = new TableDef("notes", List.of(
                new ColumnDef("note", CdbType.VARCHAR),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
        try (final Store store = Store.open(root)) {
            store.create(notes, false);
            store.textLimitBytes = 64L;
            note(store, notes, "x".repeat(10), TS);
            try (final TableReader reader = store.reader(notes.name())) {
                assertThat(rows(reader)).hasSize(1);
            }
            final long logBytes = store.walStatus().getFirst().logBytes();
            assertThatThrownBy(() -> note(store, notes, "y".repeat(30), TS + 1L)).isInstanceOf(CdbException.class)
                    .hasMessageContaining("refuse commit over a partition's text capacity");
            final WalStatus after = store.walStatus().getFirst();
            assertThat(after.loggedTxn()).isEqualTo(1L);
            assertThat(after.logBytes()).isEqualTo(logBytes);
            assertThat(after.suspended()).isFalse();
        }
    }

    /// A writer that keeps the table's lease across 400 commits does not keep the log from being cut: it shrinks back
    /// under four checkpoints' bytes while the writer is still open, and every row reads. The cut comes from a
    /// checkpoint that found no record after it or from the drain under the log's monitor, whichever came first: the
    /// test bounds the log, not which path cut it.
    @Test
    void aLogAWriterKeepsGrowingIsDrainedAndCut(@TempDir final Path root) throws IOException {
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            store.checkpointBytes = 512L;
            store.checkpointRecords = Integer.MAX_VALUE;
            final Path wal = root.resolve(M.name()).resolve(Wal.FILE);
            long most = 0L;
            try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
                for (int i = 0; i < 400; i++) {
                    row(writer, i, TS + i);
                    writer.commit();
                    most = Math.max(most, Files.size(wal));
                }
                assertThat(most).as("log grew past the drain bound").isGreaterThan(4L * store.checkpointBytes);
                assertEventually(() -> sizeOf(wal) < 4L * store.checkpointBytes + 512L, "log cut under the lease");
            }
            assertThat(read(store)).hasSize(400);
        }
    }

    /// A writer left open past the store's close, which stops waiting for it after 10 s, cannot have a commit
    /// answered: its record may be in the log, but no applier would publish it.
    @Test
    void aCommitAfterTheStoreClosedIsRefused(@TempDir final Path root) throws InterruptedException {
        final Store store = Store.open(root);
        store.create(M, false);
        final TableWriter writer = store.writer(M.name(), WAIT_MILLIS);
        try {
            final Thread closer = Thread.ofVirtual().name("store-apply-test-closer").start(store::close);
            assertThat(closer.join(Duration.ofSeconds(30L))).isTrue();
            row(writer, 1.0, TS);
            assertThatThrownBy(writer::commit).isInstanceOf(CdbException.class);
        } finally {
            writer.close();
        }
    }

    /// An `Error` thrown while the applier writes one table's batch suspends that table and nothing else: the thread
    /// keeps applying the other table, whose lag-0 reads see their commits.
    @Test
    void anErrorInOneTablesBatchSuspendsItAlone(@TempDir final Path root) {
        final TableDef other = new TableDef("other", M.columns(), 1, PartitionBy.DAY, List.of(), 0L);
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            store.create(other, false);
            final Path broken = root.resolve(M.name());
            StoreFiles.recorder = new Silent() {
                @Override
                public void wrote(final Path file, final long position, final ByteBuffer bytes) {
                    if (file.startsWith(broken) && file.getFileName().toString().endsWith(".d")) {
                        throw new OutOfMemoryError("injected");
                    }
                }
            };
            try {
                commit(store, 1.0, TS);
                assertEventually(() -> suspended(store, M.name()), "table m suspended");
                try (final TableWriter writer = store.writer(other.name(), WAIT_MILLIS)) {
                    row(writer, 2.0, TS);
                    writer.commit();
                }
                try (final TableReader reader = store.reader(other.name())) {
                    assertThat(rows(reader)).hasSize(1);
                }
                assertThat(suspended(store, other.name())).isFalse();
                assertThat(applierThreads("cdb-applier-" + root.getFileName())).hasSize(1);
            } finally {
                StoreFiles.recorder = null;
            }
        }
    }

    // polls `condition` with a doubling back-off until it holds or EVENTUALLY_NANOS pass
    private static void assertEventually(final BooleanSupplier condition, final String what) {
        final long start = System.nanoTime();
        long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start - EVENTUALLY_NANOS > 0L) {
                fail("condition not reached [what=" + what + ", waitMillis="
                        + TimeUnit.NANOSECONDS.toMillis(EVENTUALLY_NANOS) + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(2L * backoffNanos, TimeUnit.MILLISECONDS.toNanos(50L));
        }
    }

    private static boolean suspended(final Store store, final String table) {
        for (final WalStatus status : store.walStatus()) {
            if (status.table().equals(table)) {
                return status.suspended();
            }
        }
        throw new AssertionError("no table [table=" + table + ']');
    }

    private static long sizeOf(final Path file) {
        try {
            return Files.size(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void note(final Store store, final TableDef def, final String text, final long ts) {
        try (final TableWriter writer = store.writer(def.name(), WAIT_MILLIS)) {
            writer.newRow(ts);
            writer.putText(0, text);
            writer.endRow();
            writer.commit();
        }
    }

    private static List<Thread> applierThreads(final String name) {
        final List<Thread> found = new ArrayList<>();
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(name)) {
                found.add(thread);
            }
        }
        return found;
    }

    private static WalStatus status(final Store store) {
        final List<WalStatus> statuses = store.walStatus();
        assertThat(statuses).hasSize(1);
        return statuses.getFirst();
    }

    private static long txnNumber(final Path root) throws IOException {
        return TableFiles.decodeTxn(StoreFiles.readText(root.resolve(M.name()).resolve(TableFiles.TXN)), "_txn")
                .number();
    }

    private static void commit(final Store store, final double value, final long ts) {
        try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
            row(writer, value, ts);
            writer.commit();
        }
    }

    private static void row(final TableWriter writer, final double value, final long ts) {
        writer.newRow(ts);
        writer.putDouble(0, value);
        writer.endRow();
    }

    private static List<String> read(final Store store) {
        try (final TableReader reader = store.reader(M.name())) {
            return rows(reader);
        }
    }

    // a store's files as a kill leaves them in the page cache: the open store's lock file left out
    private static void copyTree(final Path from, final Path to) throws IOException {
        try (final Stream<Path> paths = Files.walk(from)) {
            for (final Path path : paths.toList()) {
                if (path.getFileName().toString().equals("_lock")) {
                    continue;
                }
                final Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    /// A recorder that sees nothing; a test overrides the operation it fails.
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
