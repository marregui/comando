package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableWriter;

/** ALTER TABLE: one transaction through the table's writer; rows written before an added column read it as NULL. */
final class AlterRunner {

    private AlterRunner() {
    }

    /** Adds the columns in order and commits; none added changes nothing. Returns 0, the rows changed. */
    static long addColumns(final Plan.AddColumns add, final Store store) {
        if (add.columns().isEmpty()) {
            return 0;
        }
        try (TableWriter writer = store.writer(add.table(), UpdateRunner.WAIT_MILLIS)) {
            for (final ColumnDef column : add.columns()) {
                writer.addColumn(column);
            }
            writer.commit();
        }
        return 0;
    }

    /** Sets the TTL and commits, which drops the partitions the new TTL expires. Returns 0, the rows changed. */
    static long setTtl(final Plan.SetTtl set, final Store store) {
        try (TableWriter writer = store.writer(set.table(), UpdateRunner.WAIT_MILLIS)) {
            writer.setTtl(set.ttlMicros());
            writer.commit();
        }
        return 0;
    }
}
