package dev.marregui.comando.cdb.catalog;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableReader;

/**
 * The store-backed listings: their fields, as QuestDB names and types them (QuestDB's INT is LONG here, STRING and CHAR
 * are VARCHAR), and their rows.
 */
final class Listings {

    static final List<Field> TABLES = List.of(
            new Field("id", CdbType.LONG),
            new Field("table_name", CdbType.VARCHAR),
            new Field("designatedTimestamp", CdbType.VARCHAR),
            new Field("partitionBy", CdbType.VARCHAR),
            new Field("walEnabled", CdbType.BOOLEAN),
            new Field("dedup", CdbType.BOOLEAN),
            new Field("ttlValue", CdbType.LONG),
            new Field("ttlUnit", CdbType.VARCHAR),
            new Field("table_row_count", CdbType.LONG),
            new Field("table_min_timestamp", CdbType.TIMESTAMP),
            new Field("table_max_timestamp", CdbType.TIMESTAMP)
    );

    static final List<Field> COLUMNS = List.of(
            new Field("column", CdbType.VARCHAR),
            new Field("type", CdbType.VARCHAR),
            new Field("indexed", CdbType.BOOLEAN),
            new Field("indexBlockCapacity", CdbType.LONG),
            new Field("symbolCached", CdbType.BOOLEAN),
            new Field("symbolCapacity", CdbType.LONG),
            new Field("designated", CdbType.BOOLEAN),
            new Field("upsertKey", CdbType.BOOLEAN)
    );

    static final List<Field> SHOW_TABLES = List.of(new Field("table_name", CdbType.VARCHAR));

    /** What QuestDB reports for every SYMBOL column of a table created without options (read from the live server). */
    private static final long SYMBOL_CAPACITY = 256;

    /** Microseconds in an hour, a day and a week: the units of a TTL as QuestDB's {@code ttlUnit} names them. */
    private static final long HOUR_MICROS = 3_600_000_000L;
    private static final long DAY_MICROS = 24 * HOUR_MICROS;
    private static final long WEEK_MICROS = 7 * DAY_MICROS;

    private Listings() {
    }

    /** {@code tables()}: one row per table, in name order; {@code id} is the row's position, from 1. */
    static Cursor tables(final Store store) {
        final PageBuilder rows = new PageBuilder(TABLES);
        long id = 0;
        for (final String name : store.tables()) {
            try (TableReader reader = store.reader(name)) {
                final TableDef def = reader.def();
                long minTimestamp = Vector.NULL_LONG;
                long maxTimestamp = Vector.NULL_LONG;
                for (final PartitionReader partition : reader.partitions()) {
                    if (partition.rowCount() == 0) {
                        continue;
                    }
                    if (minTimestamp == Vector.NULL_LONG || partition.minTimestamp() < minTimestamp) {
                        minTimestamp = partition.minTimestamp();
                    }
                    if (maxTimestamp == Vector.NULL_LONG || partition.maxTimestamp() > maxTimestamp) {
                        maxTimestamp = partition.maxTimestamp();
                    }
                }
                rows.integer(0, ++id);
                rows.text(1, def.name());
                rows.text(2, def.timestampIndex() < 0 ? null : def.columns().get(def.timestampIndex()).name());
                rows.text(3, def.partitionBy().name());
                rows.bool(4, def.partitionBy() != PartitionBy.NONE);
                rows.bool(5, !def.dedupKeys().isEmpty());
                final long ttlUnitMicros = ttlUnitMicros(def.ttlMicros());
                rows.integer(6, (def.ttlMicros() + ttlUnitMicros - 1) / ttlUnitMicros);
                rows.text(7, ttlUnitName(ttlUnitMicros));
                rows.integer(8, reader.rowCount());
                rows.integer(9, minTimestamp);
                rows.integer(10, maxTimestamp);
                rows.endRow();
            }
        }
        return new PagesCursor(TABLES, rows.finish());
    }

    /** {@code table_columns(table)} and {@code SHOW COLUMNS FROM table}: one row per column, in table order. */
    static Cursor columns(final Store store, final String table) {
        final TableDef def = store.def(table);
        if (def == null) {
            throw new CdbException("table does not exist [table=" + table + "]", -1);
        }
        final PageBuilder rows = new PageBuilder(COLUMNS);
        final List<ColumnDef> columns = def.columns();
        for (int i = 0; i < columns.size(); i++) {
            final ColumnDef column = columns.get(i);
            final boolean symbol = column.type() == CdbType.SYMBOL;
            rows.text(0, column.name());
            rows.text(1, column.type().sqlName());
            rows.bool(2, false);
            rows.integer(3, symbol ? SYMBOL_CAPACITY : 0);
            rows.bool(4, symbol);
            rows.integer(5, symbol ? SYMBOL_CAPACITY : 0);
            rows.bool(6, i == def.timestampIndex());
            rows.bool(7, isUpsertKey(def, column.name()));
            rows.endRow();
        }
        return new PagesCursor(COLUMNS, rows.finish());
    }

    /** {@code SHOW TABLES}: the tables' names, sorted. */
    static Cursor showTables(final Store store) {
        final PageBuilder rows = new PageBuilder(SHOW_TABLES);
        for (final String name : store.tables()) {
            rows.text(0, name);
            rows.endRow();
        }
        return new PagesCursor(SHOW_TABLES, rows.finish());
    }

    /** A single row with no column: the source of a SELECT without FROM. */
    static Cursor oneRow() {
        return new PagesCursor(List.of(), List.of(new Page(new Vector[0], 1)));
    }

    /** The largest of week, day and hour that divides the TTL; hour for none (QuestDB reports 0 HOUR then). */
    private static long ttlUnitMicros(final long ttlMicros) {
        if (ttlMicros > 0 && ttlMicros % WEEK_MICROS == 0) {
            return WEEK_MICROS;
        }
        if (ttlMicros > 0 && ttlMicros % DAY_MICROS == 0) {
            return DAY_MICROS;
        }
        return HOUR_MICROS;
    }

    private static String ttlUnitName(final long unitMicros) {
        if (unitMicros == WEEK_MICROS) {
            return "WEEK";
        }
        return unitMicros == DAY_MICROS ? "DAY" : "HOUR";
    }

    private static boolean isUpsertKey(final TableDef def, final String column) {
        for (final String key : def.dedupKeys()) {
            if (key.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }
}
