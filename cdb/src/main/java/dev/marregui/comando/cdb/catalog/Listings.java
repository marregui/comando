package dev.marregui.comando.cdb.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            new Field("matView", CdbType.BOOLEAN),
            new Field("directoryName", CdbType.VARCHAR),
            new Field("maxUncommittedRows", CdbType.LONG),
            new Field("o3MaxLag", CdbType.LONG),
            new Field("table_suspended", CdbType.BOOLEAN),
            new Field("table_type", CdbType.VARCHAR),
            new Field("table_row_count", CdbType.LONG),
            new Field("table_min_timestamp", CdbType.TIMESTAMP),
            new Field("table_max_timestamp", CdbType.TIMESTAMP),
            new Field("table_last_write_timestamp", CdbType.TIMESTAMP),
            new Field("table_txn", CdbType.LONG),
            new Field("table_memory_pressure_level", CdbType.LONG),
            new Field("table_write_amp_count", CdbType.LONG),
            new Field("table_write_amp_p50", CdbType.DOUBLE),
            new Field("table_write_amp_p90", CdbType.DOUBLE),
            new Field("table_write_amp_p99", CdbType.DOUBLE),
            new Field("table_write_amp_max", CdbType.DOUBLE),
            new Field("table_merge_rate_count", CdbType.LONG),
            new Field("table_merge_rate_p50", CdbType.LONG),
            new Field("table_merge_rate_p90", CdbType.LONG),
            new Field("table_merge_rate_p99", CdbType.LONG),
            new Field("table_merge_rate_max", CdbType.LONG),
            new Field("wal_pending_row_count", CdbType.LONG),
            new Field("wal_dedup_row_count_since_start", CdbType.LONG),
            new Field("wal_txn", CdbType.LONG),
            new Field("wal_max_timestamp", CdbType.TIMESTAMP),
            new Field("wal_tx_count", CdbType.LONG),
            new Field("wal_tx_size_p50", CdbType.LONG),
            new Field("wal_tx_size_p90", CdbType.LONG),
            new Field("wal_tx_size_p99", CdbType.LONG),
            new Field("wal_tx_size_max", CdbType.LONG),
            new Field("replica_batch_count", CdbType.LONG),
            new Field("replica_batch_size_p50", CdbType.LONG),
            new Field("replica_batch_size_p90", CdbType.LONG),
            new Field("replica_batch_size_p99", CdbType.LONG),
            new Field("replica_batch_size_max", CdbType.LONG),
            new Field("replica_more_pending", CdbType.BOOLEAN)
    );

    static final List<Field> COLUMNS = List.of(
            new Field("column", CdbType.VARCHAR),
            new Field("type", CdbType.VARCHAR),
            new Field("indexed", CdbType.BOOLEAN),
            new Field("indexBlockCapacity", CdbType.LONG),
            new Field("symbolCached", CdbType.BOOLEAN),
            new Field("symbolCapacity", CdbType.LONG),
            new Field("symbolTableSize", CdbType.LONG),
            new Field("designated", CdbType.BOOLEAN),
            new Field("upsertKey", CdbType.BOOLEAN),
            new Field("indexType", CdbType.VARCHAR),
            new Field("indexInclude", CdbType.VARCHAR)
    );

    static final List<Field> SHOW_TABLES = List.of(new Field("table_name", CdbType.VARCHAR));

    static final List<Field> SHOW_CREATE_TABLE = List.of(new Field("ddl", CdbType.VARCHAR));

    /** QuestDB's {@code cairo.max.uncommitted.rows} and {@code cairo.o3.max.lag}, its defaults (live server). */
    private static final long MAX_UNCOMMITTED_ROWS = 500_000;
    private static final long O3_MAX_LAG_MICROS = 600_000_000;

    /** The index type QuestDB names for an index created without {@code TYPE}. */
    private static final String BITMAP = "BITMAP";

    /** Microseconds in an hour, a day and a week: the units of a TTL as QuestDB's {@code ttlUnit} names them. */
    private static final long HOUR_MICROS = 3_600_000_000L;
    private static final long DAY_MICROS = 24 * HOUR_MICROS;
    private static final long WEEK_MICROS = 7 * DAY_MICROS;

    private Listings() {
    }

    /**
     * {@code tables()}: one row per table, in name order; {@code id} is the row's position, from 1. A table is WAL in
     * QuestDB's sense when it is partitioned; the columns of QuestDB's runtime statistics hold what QuestDB reports for
     * a table written once: 0 for the WAL, merge and replica figures, the newest row's timestamp for the last write.
     */
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
                final boolean wal = isWal(def);
                rows.integer(0, ++id);
                rows.text(1, def.name());
                rows.text(2, def.timestampIndex() < 0 ? null : def.columns().get(def.timestampIndex()).name());
                rows.text(3, def.partitionBy().name());
                rows.bool(4, wal);
                rows.bool(5, !def.dedupKeys().isEmpty());
                final long ttlUnitMicros = ttlUnitMicros(def.ttlMicros());
                rows.integer(6, (def.ttlMicros() + ttlUnitMicros - 1) / ttlUnitMicros);
                rows.text(7, ttlUnitName(ttlUnitMicros));
                rows.bool(8, false);
                rows.text(9, wal ? def.name() + '~' + id : def.name());
                rows.integer(10, MAX_UNCOMMITTED_ROWS);
                rows.integer(11, O3_MAX_LAG_MICROS);
                rows.bool(12, false);
                rows.text(13, "T");
                rows.integer(14, reader.rowCount());
                // QuestDB tracks no lower bound for a table it writes without WAL (its own qdb_server).
                rows.integer(15, wal ? minTimestamp : Vector.NULL_LONG);
                rows.integer(16, maxTimestamp);
                rows.integer(17, maxTimestamp);
                rows.integer(18, Vector.NULL_LONG);
                rows.integer(19, wal ? 0 : Vector.NULL_LONG);
                rows.integer(20, 0);
                for (int i = 21; i <= 24; i++) {
                    rows.real(i, 0.0);
                }
                for (int i = 25; i <= 31; i++) {
                    rows.integer(i, 0);
                }
                rows.integer(32, Vector.NULL_LONG);
                rows.integer(33, wal ? maxTimestamp : Vector.NULL_LONG);
                for (int i = 34; i <= 43; i++) {
                    rows.integer(i, 0);
                }
                rows.bool(44, false);
                rows.endRow();
            }
        }
        return new PagesCursor(TABLES, rows.finish());
    }

    /**
     * {@code table_columns(table)} and {@code SHOW COLUMNS FROM table}: one row per column, in table order. A SYMBOL's
     * {@code symbolTableSize} counts the distinct values its rows hold, NULL counted once as QuestDB counts it.
     */
    static Cursor columns(final Store store, final String table) {
        final TableDef def = existing(store, table);
        final PageBuilder rows = new PageBuilder(COLUMNS);
        final List<ColumnDef> columns = def.columns();
        try (final TableReader reader = store.reader(def.name())) {
            for (int i = 0; i < columns.size(); i++) {
                final ColumnDef column = columns.get(i);
                rows.text(0, column.name());
                rows.text(1, column.type().sqlName());
                rows.bool(2, column.indexed());
                rows.integer(3, column.indexBlockCapacity());
                rows.bool(4, column.symbolCached());
                rows.integer(5, column.symbolCapacity());
                rows.integer(6, column.type() == CdbType.SYMBOL ? symbolCount(reader, i) : 0);
                rows.bool(7, i == def.timestampIndex());
                rows.bool(8, isUpsertKey(def, column.name()));
                rows.text(9, column.indexed() ? BITMAP : "");
                rows.text(10, "");
                rows.endRow();
            }
        }
        return new PagesCursor(COLUMNS, rows.finish());
    }

    /**
     * {@code SHOW CREATE TABLE table}: the statement as QuestDB writes it, its layout included; a SYMBOL's capacity is
     * left out as QuestDB leaves it out.
     */
    static Cursor showCreateTable(final Store store, final String table) {
        final TableDef def = existing(store, table);
        final StringBuilder sb = new StringBuilder(64 + 32 * def.columns().size());
        sb.append("CREATE TABLE '").append(def.name()).append("' ( \n");
        final List<ColumnDef> columns = def.columns();
        for (int i = 0; i < columns.size(); i++) {
            final ColumnDef column = columns.get(i);
            if (i > 0) {
                sb.append(",\n");
            }
            sb.append('\t').append(column.name()).append(' ').append(column.type().sqlName());
            if (column.type() == CdbType.SYMBOL && !column.symbolCached()) {
                sb.append(" NOCACHE");
            }
            if (column.indexed()) {
                sb.append(" INDEX CAPACITY ").append(column.indexBlockCapacity());
            }
        }
        sb.append("\n)");
        if (def.timestampIndex() >= 0) {
            sb.append(" timestamp(").append(columns.get(def.timestampIndex()).name()).append(')');
            sb.append(" PARTITION BY ").append(def.partitionBy().name());
            if (def.ttlMicros() > 0) {
                final long unitMicros = ttlUnitMicros(def.ttlMicros());
                final long count = (def.ttlMicros() + unitMicros - 1) / unitMicros;
                sb.append(" TTL ").append(count).append(' ').append(ttlUnitName(unitMicros));
                if (count > 1) {
                    sb.append('S');
                }
            }
            if (!isWal(def)) {
                sb.append(" BYPASS WAL");
            }
        }
        if (!def.dedupKeys().isEmpty()) {
            sb.append("\nDEDUP UPSERT KEYS(");
            boolean first = true;
            for (final ColumnDef column : columns) {
                if (isUpsertKey(def, column.name())) {
                    sb.append(first ? "" : ",").append(column.name());
                    first = false;
                }
            }
            sb.append(')');
        }
        sb.append(';');
        final PageBuilder rows = new PageBuilder(SHOW_CREATE_TABLE);
        rows.text(0, sb.toString());
        rows.endRow();
        return new PagesCursor(SHOW_CREATE_TABLE, rows.finish());
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

    private static TableDef existing(final Store store, final String table) {
        final TableDef def = store.def(table);
        if (def == null) {
            throw new CdbException("table does not exist [table=" + table + "]", -1);
        }
        return def;
    }

    /** cdb keeps no WAL; a table is reported as QuestDB would have made it: WAL when partitioned. */
    private static boolean isWal(final TableDef def) {
        return def.partitionBy() != PartitionBy.NONE;
    }

    /** The distinct values of SYMBOL column {@code column} over the snapshot, a NULL among them counted once. */
    private static long symbolCount(final TableReader reader, final int column) {
        final Set<String> values = new HashSet<>();
        boolean hasNull = false;
        final String[] chunk = new String[Page.CAPACITY];
        for (final PartitionReader partition : reader.partitions()) {
            for (long from = 0; from < partition.rowCount(); from += chunk.length) {
                final int n = (int) Math.min(chunk.length, partition.rowCount() - from);
                partition.texts(column, from, n, chunk);
                for (int i = 0; i < n; i++) {
                    if (chunk[i] == null) {
                        hasNull = true;
                    } else {
                        values.add(chunk[i]);
                    }
                }
            }
        }
        return values.size() + (hasNull ? 1 : 0);
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
