package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

/** TRUNCATE TABLE, ALTER TABLE DROP PARTITION and DROP TABLE, each one transaction of the table's writer. */
final class DropRunner {

    private DropRunner() {
    }

    /** The number of rows removed. */
    static long truncate(final Plan.Truncate truncate, final Store store) {
        try (TableWriter writer = store.writer(truncate.table(), UpdateRunner.WAIT_MILLIS)) {
            final long rows = rowCount(store, truncate.table());
            writer.truncate();
            writer.commit();
            return rows;
        }
    }

    /**
     * The number of partitions dropped: the table's partitions before the commit less those after it, which the held
     * writer keeps any other change from touching.
     */
    static long dropPartitions(final Plan.DropPartitions drop, final Store store) {
        final long[] from = drop.fromMicros();
        final long[] to = drop.toMicros();
        if (from.length != to.length) {
            throw new CdbException("cannot drop partitions with unpaired range bounds [table=" + drop.table()
                    + ", from=" + from.length + ", to=" + to.length + ']', -1);
        }
        try (TableWriter writer = store.writer(drop.table(), UpdateRunner.WAIT_MILLIS)) {
            final long before = partitionCount(store, drop.table());
            for (int i = 0; i < from.length; i++) {
                writer.dropPartitions(from[i], to[i]);
            }
            writer.commit();
            return before - partitionCount(store, drop.table());
        }
    }

    /** 0, as for every DDL statement; a missing table is refused unless the statement says IF EXISTS. */
    static long dropTable(final Plan.DropTable drop, final Store store) {
        if (!store.dropTable(drop.table(), UpdateRunner.WAIT_MILLIS) && !drop.ifExists()) {
            throw new CdbException("cannot find table [table=" + drop.table() + ']', -1);
        }
        return 0;
    }

    // read fresh, as a lagged reader misses the commits the held writer answered and the applier has not published
    private static long rowCount(final Store store, final String table) {
        try (TableReader reader = store.freshReader(table)) {
            return reader.rowCount();
        }
    }

    private static long partitionCount(final Store store, final String table) {
        try (TableReader reader = store.freshReader(table)) {
            return reader.partitions().size();
        }
    }
}
