package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// The applier serves the tables its commits queued and the tables whose deadline came, nothing else:
///
/// - an idle store's thread, woken for nothing, serves no table
/// - a commit on one table of many serves that table alone
/// - a table nothing signals is applied when its schedule is due and checkpointed when its idle bound is
///
/// No read runs while the turns are counted: a reader's wake serves every table once, by design.
class ApplierQueueTest {

    // as many tables as the soak that measured 225 passes a second over them
    private static final int TABLES = 48;
    private static final long TS = micros("2026-11-01T00:00:00Z");
    // no other writer exists in these tests: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // the bound of a wait for the applier thread to act: the schedule's 5 s longest interval, with room
    private static final long EVENTUALLY_NANOS = TimeUnit.SECONDS.toNanos(30L);

    /// With every table applied and its idle checkpoint an hour away, ten wake-ups that signal nothing make ten passes
    /// of the thread's loop and serve no table.
    @Test
    void anIdleStoreServesNoTableWhenWoken(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.checkpointIdleNanos = TimeUnit.HOURS.toNanos(1L);
            final List<String> tables = createAndCommit(store);
            awaitApplied(store);
            final Applier applier = store.applier();
            final Thread thread = applierThread(root);
            awaitParked(applier, thread);
            final long turns = applier.turns();
            for (int i = 0; i < 10; i++) {
                final long passes = applier.passes();
                LockSupport.unpark(thread);
                assertEventually(() -> applier.passes() > passes, "a pass after the wake-up");
                awaitParked(applier, thread);
            }
            assertThat(applier.turns() - turns).as("turns of an idle store over %d tables", tables.size()).isZero();
        }
    }

    /// With 48 tables applied and idle, a commit on one is applied by at most three turns (its queued turn, its
    /// schedule's deadline, the deadline of its checkpoint), not a pass over the 48.
    @Test
    void aCommitServesItsTableOnly(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            store.checkpointIdleNanos = TimeUnit.HOURS.toNanos(1L);
            final List<String> tables = createAndCommit(store);
            awaitApplied(store);
            final Applier applier = store.applier();
            final Thread thread = applierThread(root);
            awaitParked(applier, thread);
            final long turns = applier.turns();
            commit(store, tables.getFirst(), 2.0, TS + 1L);
            awaitApplied(store);
            awaitParked(applier, thread);
            assertThat(applier.turns() - turns).as("turns for one commit").isBetween(1L, 3L);
        }
    }

    /// A commit nothing reads is applied once its schedule is due (1 s) and its table checkpointed once its idle bound
    /// (3 s, after the apply) passes, each by a deadline: no commit or reader signals the thread after the commit.
    @Test
    void aTableIsServedAtItsDeadlines(@TempDir final Path root) {
        try (final Store store = Store.open(root)) {
            // past the schedule's 1 s base interval, so the apply's turn finds the checkpoint not yet due
            store.checkpointIdleNanos = TimeUnit.SECONDS.toNanos(3L);
            final TableDef def = def("d");
            store.create(def, false);
            commit(store, def.name(), 1.0, TS);
            final TableState state = store.states().iterator().next();
            assertEventually(() -> state.appliedTxn() == 1L, "the commit applied at its schedule");
            assertEventually(() -> state.checkpointed() != null && state.checkpointed().txn() == 1L,
                    "the table checkpointed at its idle bound");
        }
    }

    private static List<String> createAndCommit(final Store store) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < TABLES; i++) {
            final TableDef def = def("t" + i);
            store.create(def, false);
            commit(store, def.name(), i, TS);
            names.add(def.name());
        }
        return names;
    }

    private static TableDef def(final String name) {
        return new TableDef(name, List.of(
                new ColumnDef("value", CdbType.DOUBLE),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    }

    private static void commit(final Store store, final String table, final double value, final long ts) {
        try (final TableWriter writer = store.writer(table, WAIT_MILLIS)) {
            writer.newRow(ts);
            writer.putDouble(0, value);
            writer.endRow();
            writer.commit();
        }
    }

    // every table's answered commits applied, by the thread's own schedule: no read demands them
    private static void awaitApplied(final Store store) {
        assertEventually(() -> {
            for (final TableState state : store.states()) {
                if (state.appliedTxn() != state.loggedTxn()) {
                    return false;
                }
            }
            return true;
        }, "every table applied");
    }

    // the thread parked in its loop, its blocker the applier
    private static void awaitParked(final Applier applier, final Thread thread) {
        assertEventually(() -> thread.getState() == Thread.State.TIMED_WAITING
                && LockSupport.getBlocker(thread) == applier, "the applier parked");
    }

    private static Thread applierThread(final Path root) {
        final String name = "cdb-applier-" + root.getFileName();
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals(name)) {
                return thread;
            }
        }
        return fail("no applier thread [name=" + name + ']');
    }

    private static void assertEventually(final BooleanSupplier condition, final String what) {
        final long start = System.nanoTime();
        long backoffNanos = TimeUnit.MICROSECONDS.toNanos(100L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - start - EVENTUALLY_NANOS > 0L) {
                fail("condition not reached [what=" + what + ", waitMillis="
                        + TimeUnit.NANOSECONDS.toMillis(EVENTUALLY_NANOS) + ']');
            }
            LockSupport.parkNanos(backoffNanos);
            backoffNanos = Math.min(backoffNanos * 2L, TimeUnit.MILLISECONDS.toNanos(10L));
        }
    }
}
