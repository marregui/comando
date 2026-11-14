package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.CdbType;

/// The process [StoreCrashTest] kills: commits transaction 1, then starts transaction 2 and stops right before
/// `_txn` is replaced, printing [#READY] and waiting there to be killed.
final class StoreCrashChild {

    /// The line the child prints once transaction 2's version folders are written.
    static final String READY = "ready-to-replace-txn";
    static final long DAY1 = 1_793_520_000_000_000L;
    static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    // the parent kills the child within seconds of READY; the wait only bounds an orphaned child
    private static final long ORPHAN_WAIT_SECONDS = 120L;

    private StoreCrashChild() {
    }

    /// Runs the two transactions on the store at `args[0]`.
    static void main(final String[] args) throws InterruptedException {
        final Store store = Store.open(Path.of(args[0]));
        store.create(new TableDef("m", List.of(
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("s", CdbType.SYMBOL),
                new ColumnDef("ts", CdbType.TIMESTAMP)), 2, PartitionBy.DAY, List.of(), 0L), false);
        try (final TableWriter w = store.writer("m", 1_000L)) {
            row(w, DAY1, 1L, "one");
            row(w, DAY1 + 1L, 2L, "two");
            w.commit();
            final CountDownLatch never = new CountDownLatch(1);
            store.beforeTxnReplace = () -> {
                System.out.println(READY);
                System.out.flush();
                try {
                    never.await(ORPHAN_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.exit(2);
            };
            row(w, DAY1 + 2L, 3L, "three");
            row(w, DAY2, 4L, "four");
            w.commit();
        }
    }

    private static void row(final TableWriter w, final long ts, final long n, final String s) {
        w.newRow(ts);
        w.putLong(0, n);
        w.putText(1, s);
        w.endRow();
    }
}
