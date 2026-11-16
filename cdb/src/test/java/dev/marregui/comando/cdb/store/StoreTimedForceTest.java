package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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

/// A store opened with a log force interval answers a commit once its record is written, and its log forcer thread
/// forces each table's log within the interval:
///
/// - a commit is answered before its force; a durable waiter's ask forces the log at once
/// - a dirty log is forced no sooner than the interval after its first record, and not much later
/// - close forces every dirty log once more and ends the thread, `cdb-log-force-<root name>`; a record dirtied after
///   the forcer closed is forced on its writer's thread
/// - a store opened on the files after the forces replays the forced records, and a replay skips the records at or
///   before `_txn`'s transaction
/// - a failed force poisons the table, refuses its later commits and is never retried
/// - a store that forces each commit has no forcer
///
/// The commit's writer calls [LogForcer#dirty]; a test calls it again where it needs a log armed, which a second call
/// leaves as it is, and [LogForcer#urgent] as a durable waiter does; it counts the forces with [LogForcer#forceLog].
class StoreTimedForceTest {

    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final TableDef N = new TableDef("n", List.of(
            new ColumnDef("value", CdbType.DOUBLE),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // the bound of a wait for the forcer thread to act: far past any interval here, so only a hang reaches it
    private static final long EVENTUALLY_NANOS = TimeUnit.SECONDS.toNanos(30L);
    // an interval no test waits out: a force before it is the durable waiter's or the close's
    private static final long LONG_INTERVAL_MILLIS = 60_000L;
    // the most a force may come past its deadline on a loaded machine before the test calls it lost
    private static final long LATE_BOUND_MILLIS = 5_000L;

    /// Three commits are answered and read with no force of the log; a durable waiter's ask then forces it once,
    /// covering all three.
    @Test
    void aCommitIsAnsweredBeforeItsForce(@TempDir final Path root) {
        final AtomicInteger forces = new AtomicInteger();
        try (final Store store = Store.open(root, new StoreOptions(LONG_INTERVAL_MILLIS))) {
            store.create(M, false);
            final LogForcer forcer = store.forcer();
            forcer.forceLog = _ -> forces.incrementAndGet();
            final TableState state = state(store, M.name());
            for (int i = 0; i < 3; i++) {
                commit(store, M, i, TS + i);
                forcer.dirty(state);
            }
            assertThat(read(store, M)).hasSize(3);
            assertThat(forces).hasValue(0);
            assertThat(forcer.status(state).forces()).isZero();
            forcer.urgent(state);
            assertEventually(() -> forcer.status(state).forces() == 1L, "the urgent force");
            assertThat(forces).hasValue(1);
            assertThat(forcer.status(state).forcedTxn()).isEqualTo(3L);
            assertThat(forcer.status(state).forceLateMaxMillis()).isZero();
        }
        // the close found no dirty log
        assertThat(forces).hasValue(1);
    }

    /// The first force of a dirty log comes no sooner than the interval after the record that dirtied it, and well
    /// before the bound; its lateness is reported.
    @Test
    void aDirtyLogIsForcedWithinTheInterval(@TempDir final Path root) {
        final long intervalMillis = 50L;
        final AtomicLong forcedNanos = new AtomicLong();
        try (final Store store = Store.open(root, new StoreOptions(intervalMillis))) {
            store.create(M, false);
            final LogForcer forcer = store.forcer();
            assertThat(forcer.intervalMillis()).isEqualTo(intervalMillis);
            forcer.forceLog = _ -> forcedNanos.compareAndSet(0L, System.nanoTime());
            final TableState state = state(store, M.name());
            // the commit's writer dirties the log: the clock starts before it
            final long dirtiedNanos = System.nanoTime();
            commit(store, M, 1.0, TS);
            assertEventually(() -> forcer.status(state).forces() == 1L, "the timed force");
            final long elapsedNanos = forcedNanos.get() - dirtiedNanos;
            assertThat(elapsedNanos).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(intervalMillis))
                    .isLessThan(TimeUnit.MILLISECONDS.toNanos(intervalMillis + LATE_BOUND_MILLIS));
            assertThat(forcer.status(state).forceLateMaxMillis()).isBetween(0L, LATE_BOUND_MILLIS);
            assertThat(forcer.status(state).forcedTxn()).isEqualTo(1L);
        }
    }

    /// The store runs one forcer thread, a daemon named after its root; close forces both dirty logs, one each, and the
    /// thread is gone after it.
    @Test
    void closeForcesEveryDirtyLogAndEndsTheThread(@TempDir final Path root) {
        final String name = "cdb-log-force-" + root.getFileName();
        final List<Wal> forced = new ArrayList<>();
        try (final Store store = Store.open(root, new StoreOptions(LONG_INTERVAL_MILLIS))) {
            store.create(M, false);
            store.create(N, false);
            final LogForcer forcer = store.forcer();
            forcer.forceLog = wal -> {
                synchronized (forced) {
                    forced.add(wal);
                }
            };
            for (final TableDef def : List.of(M, N)) {
                commit(store, def, 1.0, TS);
            }
            final List<Thread> threads = threads(name);
            assertThat(threads).hasSize(1);
            assertThat(threads.getFirst().isDaemon()).isTrue();
            synchronized (forced) {
                assertThat(forced).isEmpty();
            }
        }
        synchronized (forced) {
            assertThat(forced).hasSize(2).doesNotHaveDuplicates();
        }
        assertThat(threads(name)).isEmpty();
    }

    /// A record dirtied after the forcer closed is forced at once on the writer's thread; a second close does
    /// nothing.
    @Test
    void aRecordDirtiedAfterCloseIsForcedOnItsThread(@TempDir final Path root) {
        final AtomicReference<Thread> forcing = new AtomicReference<>();
        try (final Store store = Store.open(root, new StoreOptions(LONG_INTERVAL_MILLIS))) {
            store.create(M, false);
            final LogForcer forcer = store.forcer();
            forcer.forceLog = _ -> forcing.set(Thread.currentThread());
            forcer.close();
            assertThat(threads("cdb-log-force-" + root.getFileName())).isEmpty();
            final TableState state = state(store, M.name());
            // the commit's writer dirties the log, once
            commit(store, M, 1.0, TS);
            assertThat(forcing).hasValue(Thread.currentThread());
            assertThat(forcer.status(state).forces()).isEqualTo(1L);
            forcer.close();
        }
    }

    /// The files copied once the timed forces covered three commits, none applied, open as a store that reads all
    /// three and numbers the next commit 4.
    @Test
    void aRestartReplaysTheForcedRecords(@TempDir final Path dir) throws IOException {
        final Path root = dir.resolve("store");
        final Path copy = dir.resolve("copy");
        try (final Store store = Store.open(root, new StoreOptions(20L))) {
            store.create(M, false);
            final LogForcer forcer = store.forcer();
            final TableState state = state(store, M.name());
            store.applierPaused = true;
            for (int i = 1; i <= 3; i++) {
                commit(store, M, i, TS + i);
                forcer.dirty(state);
            }
            assertEventually(() -> forcer.status(state).forcedTxn() == 3L, "the forces through txn 3");
            assertThat(state.appliedTxn()).isZero();
            copyTree(root, copy);
            store.applierPaused = false;
        }
        try (final Store copied = Store.open(copy)) {
            assertThat(read(copied, M)).hasSize(3);
            commit(copied, M, 4.0, TS + 4L);
            assertThat(status(copied).loggedTxn()).isEqualTo(4L);
            assertThat(read(copied, M)).hasSize(4);
        }
    }

    /// A log holding commits 1 to 3 beside a `_txn` naming 2, the copy a kill leaves, opens with each commit once:
    /// the replay skips the two records `_txn` names, and the next commit is 4.
    @Test
    void aReplaySkipsTheRecordsTheCheckpointNames(@TempDir final Path dir) throws IOException {
        final Path root = dir.resolve("store");
        final Path copy = dir.resolve("copy");
        try (final Store store = Store.open(root)) {
            store.create(M, false);
            final TableState state = state(store, M.name());
            store.applierPaused = true;
            commit(store, M, 1.0, TS + 1L);
            commit(store, M, 2.0, TS + 2L);
            state.applyLock.lock();
            try {
                store.applier().applyAllHeld(state, false);
            } finally {
                state.applyLock.unlock();
            }
            commit(store, M, 3.0, TS + 3L);
            state.applyLock.lock();
            try {
                assertThat(Checkpoint.run(store, state, state.current())).isTrue();
            } finally {
                state.applyLock.unlock();
            }
            assertThat(txnNumber(root)).isEqualTo(2L);
            assertThat(state.wal.records()).isEqualTo(3);
            copyTree(root, copy);
            store.applierPaused = false;
        }
        try (final Store copied = Store.open(copy)) {
            assertThat(read(copied, M)).hasSize(3).doesNotHaveDuplicates();
            final WalStatus status = status(copied);
            assertThat(status.loggedTxn()).isEqualTo(3L);
            assertThat(status.appliedTxn()).isEqualTo(3L);
            commit(copied, M, 4.0, TS + 4L);
            assertThat(status(copied).loggedTxn()).isEqualTo(4L);
        }
        try (final Store reopened = Store.open(copy)) {
            assertThat(read(reopened, M)).hasSize(4).doesNotHaveDuplicates();
        }
    }

    /// A timed force that fails poisons the table: its later commits are refused, its status shows it failed, and the
    /// forcer never tries the log again, not on a new record, an urgent ask or the close.
    @Test
    void aFailedForcePoisonsTheTable(@TempDir final Path root) {
        final AtomicInteger attempts = new AtomicInteger();
        try (final Store store = Store.open(root, new StoreOptions(LONG_INTERVAL_MILLIS))) {
            store.create(M, false);
            final LogForcer forcer = store.forcer();
            forcer.forceLog = _ -> {
                attempts.incrementAndGet();
                throw new IOException("injected");
            };
            final TableState state = state(store, M.name());
            commit(store, M, 1.0, TS);
            // applied first: an apply clears the table's last error
            assertThat(read(store, M)).hasSize(1);
            forcer.dirty(state);
            forcer.urgent(state);
            assertEventually(state::poisoned, "the table poisoned");
            assertThatThrownBy(() -> commit(store, M, 2.0, TS + 1L)).isInstanceOf(CdbException.class);
            final WalStatus status = status(store);
            assertThat(status.suspended()).isTrue();
            assertThat(status.lastError()).isNotNull();
            forcer.dirty(state);
            forcer.urgent(state);
            assertThat(forcer.status(state).forces()).isZero();
        }
        assertThat(attempts).hasValue(1);
    }

    /// A store opened without options, or with the default ones, forces each commit: no forcer and no forcer thread;
    /// a negative interval is refused.
    @Test
    void aStoreThatForcesEachCommitHasNoForcer(@TempDir final Path dir) {
        final Path root = dir.resolve("plain");
        try (final Store store = Store.open(root)) {
            assertThat(store.forcer()).isNull();
            assertThat(store.options()).isEqualTo(StoreOptions.DEFAULT);
            assertThat(threads("cdb-log-force-" + root.getFileName())).isEmpty();
        }
        try (final Store store = Store.open(root, new StoreOptions(0L))) {
            assertThat(store.forcer()).isNull();
        }
        assertThatThrownBy(() -> new StoreOptions(-1L)).isInstanceOf(IllegalArgumentException.class);
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

    private static TableState state(final Store store, final String table) {
        for (final TableState state : store.states()) {
            if (state.key.equals(table)) {
                return state;
            }
        }
        throw new AssertionError("no table [table=" + table + ']');
    }

    private static List<Thread> threads(final String name) {
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

    private static void commit(final Store store, final TableDef def, final double value, final long ts) {
        try (final TableWriter writer = store.writer(def.name(), WAIT_MILLIS)) {
            writer.newRow(ts);
            writer.putDouble(0, value);
            writer.endRow();
            writer.commit();
        }
    }

    private static List<String> read(final Store store, final TableDef def) {
        try (final TableReader reader = store.reader(def.name())) {
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
}
