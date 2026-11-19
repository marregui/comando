package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

/**
 * Runs {@code ALTER TABLE DROP COLUMN} and {@code RENAME COLUMN} as UPDATE runs: the table's writer first, then its
 * snapshot, the change, then every row of the snapshot appended again under the new definition and committed as one
 * direct transaction. Each costs a rewrite of the whole table, O(rows), for a change a power cut leaves done or undone;
 * a reader holding an older snapshot reads on under the old definition.
 */
final class ColumnRunner {

    private static final String STATEMENT = "ALTER TABLE";

    private ColumnRunner() {
    }

    /**
     * Drops the column; answers 0. A table without it is left untouched: a plan under IF EXISTS, or a column dropped
     * since the plan.
     */
    static long dropColumn(final Plan.DropColumn drop, final Store store) {
        try (TableWriter writer = store.writer(drop.table(), UpdateRunner.WAIT_MILLIS)) {
            final int dropped = writer.def().columnIndex(drop.column());
            if (dropped >= 0) {
                rewrite(drop.table(), store, writer, () -> writer.dropColumn(drop.column()), dropped);
            }
        }
        return 0L;
    }

    /** Renames the column; answers 0. */
    static long renameColumn(final Plan.RenameColumn rename, final Store store) {
        try (TableWriter writer = store.writer(rename.table(), UpdateRunner.WAIT_MILLIS)) {
            final TableDef def = writer.def();
            final int c = def.columnIndex(rename.from());
            if (c >= 0 && c == def.timestampIndex()) {
                throw new CdbException("cannot rename the designated timestamp [table=" + def.name() + ", column="
                        + def.columns().get(c).name() + ']', -1);
            }
            rewrite(rename.table(), store, writer, () -> writer.renameColumn(rename.from(), rename.to()), -1);
        }
        return 0L;
    }

    /**
     * Applies {@code change} to {@code writer}, then rewrites the table's snapshot through it: column {@code n} of the
     * new definition is column {@code n} of the snapshot, or {@code n + 1} from {@code dropped} on (-1: none dropped).
     */
    private static void rewrite(
            final String table,
            final Store store,
            final TableWriter writer,
            final Runnable change,
            final int dropped
    ) {
        // the writer is held: no commit lands between this snapshot and the rewrite
        final TableReader reader = store.freshReader(table);
        try {
            change.run();
        } catch (final RuntimeException | Error e) {
            try {
                reader.close();
            } catch (final RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        UpdateRunner.rewriteAll(STATEMENT, table, reader, writer, (_, _) -> {
            final TableDef next = writer.def();
            final int[] from = new int[next.columns().size()];
            for (int n = 0; n < from.length; n++) {
                from[n] = dropped >= 0 && n >= dropped ? n + 1 : n;
            }
            final int timestamp = next.timestampIndex();
            return (page, _) -> {
                for (int r = 0; r < page.size; r++) {
                    writer.newRow(timestamp < 0 ? Vector.NULL_LONG : page.columns[from[timestamp]].getLong(r));
                    for (int n = 0; n < from.length; n++) {
                        if (n != timestamp) {
                            UpdateRunner.put(writer, n, next.columns().get(n).type(), page.columns[from[n]], r);
                        }
                    }
                    writer.endRow();
                }
            };
        });
    }
}
