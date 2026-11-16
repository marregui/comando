package dev.marregui.comando.cdb.exec;

import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ForceTicket;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableWriter;

/**
 * INSERT: appends every row of the sources through the table's writer and commits once, so the rows are deduplicated
 * and expired as an append is; a failure on any row rolls back the whole statement. Columns the INSERT leaves out read
 * as NULL. Within {@link #DURABLE} bound true, an INSERT returns once its rows are durable.
 */
public final class InsertRunner {

    /**
     * Bound true, an INSERT of the scope returns only once the log force covering its commit held, waiting at most
     * {@link #DURABLE_WAIT_MILLIS} after it committed; unbound or false, once it is committed, as before. A wait that
     * runs out, or a force that failed, fails the statement with its rows written.
     */
    public static final ScopedValue<Boolean> DURABLE = ScopedValue.newInstance();

    /**
     * The longest a durable write waits for its force after its commit: 10 s, as long as the store's close waits for a
     * table's writer. A durable waiter asks the store's log forcer to force its log now, so the wait is one force of
     * one file; not measured, a disk that cannot force one file in 10 s is failing.
     */
    public static final long DURABLE_WAIT_MILLIS = 10_000L;

    private InsertRunner() {
    }

    /** The number of rows inserted. */
    static long run(final Plan.Insert insert, final Store store, final long nowMicros) {
        final long count;
        final ForceTicket ticket;
        try (TableWriter writer = store.writer(insert.table(), UpdateRunner.WAIT_MILLIS)) {
            final int[] columns = insert.columns();
            final int timestamp = timestampField(columns, writer.def().timestampIndex());
            long inserted = 0;
            for (final Plan source : insert.sources()) {
                try (Cursor cursor = Executor.open(source, store, nowMicros)) {
                    final List<Field> fields = cursor.fields();
                    Page page;
                    while ((page = cursor.next()) != null) {
                        for (int r = 0; r < page.size; r++) {
                            writer.newRow(timestamp < 0 ? Vector.NULL_LONG : page.columns[timestamp].longs[r]);
                            for (int i = 0; i < columns.length; i++) {
                                if (i != timestamp) {
                                    UpdateRunner.put(writer, columns[i], fields.get(i).type(), page.columns[i], r);
                                }
                            }
                            writer.endRow();
                        }
                        inserted += page.size;
                    }
                }
            }
            writer.commit();
            count = inserted;
            ticket = writer.ticket();
        }
        // the writer is released first: the wait holds no lease, so the table's other writers go on meanwhile
        if (DURABLE.isBound() && DURABLE.get()) {
            awaitDurable(ticket, insert.table());
        }
        return count;
    }

    // the commit is answered and visible; a wait that runs out or is interrupted leaves its rows written, not durable,
    // and a failed force throws the store's "commit in doubt"
    private static void awaitDurable(final ForceTicket ticket, final String table) {
        try {
            if (!ticket.await(TimeUnit.MILLISECONDS.toNanos(DURABLE_WAIT_MILLIS))) {
                throw new CdbException("wrote rows, not yet durable [table=" + table + ", waitMillis="
                        + DURABLE_WAIT_MILLIS + ']', -1);
            }
        } catch (final InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new CdbException("wrote rows, not yet durable, wait interrupted [table=" + table + ']', -1);
        }
    }

    /** The position of the designated timestamp {@code timestamp} among {@code columns}; -1 for a table without one. */
    private static int timestampField(final int[] columns, final int timestamp) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i] == timestamp) {
                return i;
            }
        }
        return -1;
    }
}
