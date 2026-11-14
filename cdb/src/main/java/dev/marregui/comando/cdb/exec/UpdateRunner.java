package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

/**
 * UPDATE: rewrites the table in one transaction, the assigned columns replaced where the filter holds. The column
 * indexes of the update and of its expressions are the table's. A value of LONG or TIMESTAMP is assigned to a DOUBLE
 * column as a number and the other way round truncated, NULL staying NULL. The designated timestamp cannot be assigned.
 * An update that matches no row commits nothing.
 */
final class UpdateRunner {

    /**
     * How long an UPDATE waits for another writer of its table to close before it fails. Not measured: long enough for
     * an append batch to commit, short enough that a stuck writer surfaces as an error.
     */
    private static final long WAIT_MILLIS = 30_000;

    private UpdateRunner() {
    }

    /** The number of rows changed. */
    static long run(final Plan.Update update, final Store store, final long nowMicros) {
        // the writer first: no commit can land between the snapshot read and the rewrite
        try (TableWriter writer = store.writer(update.table(), WAIT_MILLIS)) {
            return rewrite(update, store.reader(update.table()), writer, nowMicros);
        }
    }

    /**
     * Rewrites every row of {@code reader}'s snapshot through {@code writer} and commits, or rolls back when no row
     * matched; closes {@code reader}. Returns the rows changed.
     */
    static long rewrite(
            final Plan.Update update,
            final TableReader reader,
            final TableWriter writer,
            final long nowMicros
    ) {
        final Plan.Scan all;
        try {
            all = scanOf(update.table(), reader.def());
        } catch (final RuntimeException | Error e) {
            try {
                reader.close();
            } catch (final RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        try (Cursor scan = new ScanCursor(all, reader)) {
            final List<Field> fields = all.fields();
            final int timestamp = reader.def().timestampIndex();
            final VectorFn[] assigned = assignments(update, fields, timestamp, nowMicros);
            final VectorFn where = update.where() == null ? null : Functions.compile(update.where(), nowMicros);
            final boolean[] hit = new boolean[Page.CAPACITY];
            final Vector[] values = new Vector[fields.size()];
            writer.truncate();
            long changed = 0;
            Page page;
            while ((page = scan.next()) != null) {
                final int size = page.size;
                if (where == null) {
                    Arrays.fill(hit, 0, size, true);
                } else {
                    final Vector mask = where.eval(page);
                    if (mask.booleans == null) {
                        throw new CdbException("cannot UPDATE where a condition is not BOOLEAN [table="
                                + update.table() + ", type=" + mask.type + "]", -1);
                    }
                    System.arraycopy(mask.booleans, 0, hit, 0, size);
                }
                for (int c = 0; c < values.length; c++) {
                    values[c] = assigned[c] == null ? null : assigned[c].eval(page);
                }
                for (int r = 0; r < size; r++) {
                    final boolean change = hit[r];
                    if (change) {
                        changed++;
                    }
                    writer.newRow(timestamp < 0 ? Vector.NULL_LONG : page.columns[timestamp].longs[r]);
                    for (int c = 0; c < values.length; c++) {
                        if (c != timestamp) {
                            put(writer, c, fields.get(c).type(), change && values[c] != null ? values[c]
                                    : page.columns[c], r);
                        }
                    }
                    writer.endRow();
                }
            }
            if (changed == 0) {
                writer.rollback();
            } else {
                writer.commit();
            }
            return changed;
        }
    }

    private static Plan.Scan scanOf(final String table, final TableDef def) {
        final List<ColumnDef> columns = def.columns();
        final int[] indexes = new int[columns.size()];
        final List<Field> fields = new ArrayList<>(columns.size());
        for (int c = 0; c < indexes.length; c++) {
            indexes[c] = c;
            fields.add(new Field(columns.get(c).name(), columns.get(c).type()));
        }
        return new Plan.Scan(table, indexes, Long.MIN_VALUE, Long.MAX_VALUE, fields);
    }

    /** One compiled value per column of the table, null for a column the update leaves as it is. */
    private static VectorFn[] assignments(
            final Plan.Update update,
            final List<Field> fields,
            final int timestamp,
            final long nowMicros
    ) {
        final int[] columns = update.columns();
        final List<Bound> values = update.values();
        if (columns.length != values.size()) {
            throw new CdbException("cannot UPDATE with a value count unlike the column count [table=" + update.table()
                    + ", columns=" + columns.length + ", values=" + values.size() + "]", -1);
        }
        final VectorFn[] assigned = new VectorFn[fields.size()];
        for (int i = 0; i < columns.length; i++) {
            final int c = columns[i];
            if (c < 0 || c >= fields.size()) {
                throw new CdbException("cannot UPDATE a column the table lacks [table=" + update.table() + ", column="
                        + c + "]", -1);
            }
            final Field field = fields.get(c);
            if (c == timestamp) {
                throw new CdbException("cannot UPDATE the designated timestamp [table=" + update.table() + ", column="
                        + field.name() + "]", -1);
            }
            if (assigned[c] != null) {
                throw new CdbException("cannot UPDATE a column twice [table=" + update.table() + ", column="
                        + field.name() + "]", -1);
            }
            final CdbType type = values.get(i).type();
            if (!Rows.sameStorage(type, field.type()) && !(numeric(type) && numeric(field.type()))) {
                throw new CdbException("cannot UPDATE a column with a value of another type [table=" + update.table()
                        + ", column=" + field.name() + ", type=" + field.type() + ", value=" + type + "]", -1);
            }
            assigned[c] = Functions.compile(values.get(i), nowMicros);
        }
        return assigned;
    }

    private static boolean numeric(final CdbType type) {
        return type == CdbType.LONG || type == CdbType.TIMESTAMP || type == CdbType.DOUBLE;
    }

    /** Writes value {@code row} of {@code v} to {@code column}; NULL is left unset, which reads as NULL. */
    private static void put(
            final TableWriter writer,
            final int column,
            final CdbType type,
            final Vector v,
            final int row
    ) {
        if (v.isNull(row)) {
            return;
        }
        switch (type) {
            case LONG, TIMESTAMP -> writer.putLong(column, v.longs != null ? v.longs[row] : (long) v.doubles[row]);
            case DOUBLE -> writer.putDouble(column, v.doubles != null ? v.doubles[row] : (double) v.longs[row]);
            case BOOLEAN -> writer.putBoolean(column, v.booleans[row]);
            case SYMBOL, VARCHAR -> writer.putText(column, v.texts[row]);
        }
    }
}
