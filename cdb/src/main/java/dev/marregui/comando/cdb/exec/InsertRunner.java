package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableWriter;

/**
 * INSERT: appends every row of the sources through the table's writer and commits once, so the rows are deduplicated
 * and expired as an append is; a failure on any row rolls back the whole statement. Columns the INSERT leaves out read
 * as NULL.
 */
final class InsertRunner {

    private InsertRunner() {
    }

    /** The number of rows inserted. */
    static long run(final Plan.Insert insert, final Store store, final long nowMicros) {
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
            return inserted;
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
