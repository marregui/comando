package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

/**
 * DELETE: rewrites the table in one transaction without the rows where the condition is TRUE. A condition is never
 * NULL, as in QuestDB: where x is NULL, {@code x > 3} is FALSE while {@code x != 3} and {@code NOT (x > 3)} are TRUE,
 * so those two delete the rows where x is NULL. A delete that matches no row commits nothing.
 */
final class DeleteRunner {

    private DeleteRunner() {
    }

    /** The number of rows removed. */
    static long run(final Plan.Delete delete, final Store store, final long nowMicros) {
        // the writer first: no commit can land between the snapshot read and the rewrite
        try (TableWriter writer = store.writer(delete.table(), UpdateRunner.WAIT_MILLIS)) {
            final TableReader reader = store.freshReader(delete.table());
            if (delete.where() == null) {
                final long rows;
                try (reader) {
                    rows = reader.rowCount();
                }
                if (rows == 0) {
                    writer.rollback();
                } else {
                    writer.truncate();
                    writer.commit();
                }
                return rows;
            }
            return UpdateRunner.rewrite("DELETE", delete.table(), delete.where(), reader, writer, nowMicros,
                    (fields, timestamp) -> (page, hit) -> {
                        for (int r = 0; r < page.size; r++) {
                            if (!hit[r]) {
                                UpdateRunner.append(writer, fields, timestamp, page, r, null);
                            }
                        }
                    });
        }
    }
}
