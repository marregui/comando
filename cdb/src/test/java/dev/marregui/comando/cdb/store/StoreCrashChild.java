package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.CdbType;

/// The process [StoreCrashTest] kills: commits transaction 1, then commits transaction 2 and stops at the [Kill] point
/// `args[1]` names, printing [#READY] and waiting there to be killed.
final class StoreCrashChild {

    /// The line the child prints once it stands at its kill point.
    static final String READY = "ready-to-be-killed";
    static final long DAY1 = 1_793_520_000_000_000L;
    static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    // the parent kills the child within seconds of READY; the wait only bounds an orphaned child
    private static final long ORPHAN_WAIT_SECONDS = 120L;

    private StoreCrashChild() {
    }

    /// Runs the two transactions on the store at `args[0]`, stopping at the kill point `args[1]` names.
    static void main(final String[] args) {
        final Kill kill = Kill.valueOf(args[1]);
        final Store store = Store.open(Path.of(args[0]));
        // the kill points are those of a commit that applies and checkpoints on its own thread
        store.applyOnCommit = true;
        if (kill == Kill.COMMIT_BEFORE_TXN_REPLACE) {
            // every commit a checkpoint: the kill lands in the commit itself, as before the log
            store.directRows = 0;
        }
        store.create(new TableDef("m", List.of(
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("s", CdbType.SYMBOL),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 2, PartitionBy.DAY, List.of(), 0L), false);
        try (final TableWriter w = store.writer("m", 1_000L)) {
            row(w, DAY1, 1L, "one");
            row(w, DAY1 + 1L, 2L, "two");
            w.commit();
            switch (kill) {
                case COMMIT_BEFORE_TXN_REPLACE -> store.beforeTxnReplace = StoreCrashChild::stop;
                case BEFORE_WAL_APPEND -> store.beforeWalAppend = StoreCrashChild::stop;
                case AFTER_WAL_FORCE -> store.afterWalForce = StoreCrashChild::stop;
                case CHECKPOINT_BEFORE_TXN_REPLACE -> {
                    // both commits are logged first; the checkpoint after them stops
                }
            }
            row(w, DAY1 + 2L, 3L, "three");
            row(w, DAY2, 4L, "four");
            w.commit();
            if (kill == Kill.CHECKPOINT_BEFORE_TXN_REPLACE) {
                store.beforeTxnReplace = StoreCrashChild::stop;
            }
        }
        // no commit stopped: a writer's close does not checkpoint, this call does
        store.checkpoint("m");
        System.exit(3);
    }

    private static void row(final TableWriter w, final long ts, final long n, final String s) {
        w.newRow(ts);
        w.putLong(0, n);
        w.putText(1, s);
        w.endRow();
    }

    private static void stop() {
        System.out.println(READY);
        System.out.flush();
        try {
            new CountDownLatch(1).await(ORPHAN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        System.exit(2);
    }

    /// Where the child stops, and the transaction a store reopened after the kill reads.
    enum Kill {

        /// A direct commit of transaction 2 (every commit a checkpoint), right before it replaces `_txn`.
        COMMIT_BEFORE_TXN_REPLACE(1L),
        /// A logged commit of transaction 2, right before its record is appended to the log.
        BEFORE_WAL_APPEND(1L),
        /// A logged commit of transaction 2, right after its record is forced, before it is answered.
        AFTER_WAL_FORCE(2L),
        /// The checkpoint after two logged commits, right before it replaces `_txn`.
        CHECKPOINT_BEFORE_TXN_REPLACE(2L);

        final long txn;

        Kill(final long txn) {
            this.txn = txn;
        }
    }
}
