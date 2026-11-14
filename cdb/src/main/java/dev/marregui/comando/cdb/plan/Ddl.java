package dev.marregui.comando.cdb.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.sql.Ast;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.TableDef;

/** {@code CREATE TABLE} to a {@link TableDef}, and QuestDB's DDL type names to the six cdb types. */
final class Ddl {

    /** QuestDB's {@code MIN_SYMBOL_CAPACITY}. */
    private static final long MIN_SYMBOL_CAPACITY = 2;

    /** QuestDB's {@code MAX_SYMBOL_CAPACITY}, {@code ceilPow2(Integer.MAX_VALUE)}: 2^30. */
    private static final long MAX_SYMBOL_CAPACITY = 1L << 30;

    /** QuestDB's {@code MAX_SYMBOL_CAPACITY_CACHED}, {@code ceilPow2(30_000_000)}. */
    private static final long MAX_SYMBOL_CAPACITY_CACHED = 1L << 25;

    /** QuestDB's {@code MIN_INDEX_VALUE_BLOCK_SIZE}. */
    private static final long MIN_INDEX_BLOCK_CAPACITY = 2;

    /** QuestDB's {@code MAX_INDEX_VALUE_BLOCK_SIZE}, {@code ceilPow2(8 * 1024 * 1024)}. */
    private static final long MAX_INDEX_BLOCK_CAPACITY = 1L << 23;

    private Ddl() {
    }

    /** QuestDB's INT, SHORT and BYTE are LONG here, FLOAT DOUBLE, STRING and CHAR VARCHAR, DATE TIMESTAMP. */
    static CdbType type(final String name, final int pos) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "LONG", "INT", "INTEGER", "SHORT", "BYTE" -> CdbType.LONG;
            case "DOUBLE", "FLOAT" -> CdbType.DOUBLE;
            case "BOOLEAN" -> CdbType.BOOLEAN;
            case "TIMESTAMP", "DATE" -> CdbType.TIMESTAMP;
            case "SYMBOL" -> CdbType.SYMBOL;
            case "VARCHAR", "STRING", "CHAR" -> CdbType.VARCHAR;
            default -> throw new CdbException("cannot map unsupported type [type=" + name + ']', pos);
        };
    }

    /**
     * The definition {@code create} declares: the designated timestamp must be a TIMESTAMP column, a partitioned table
     * must have one, and the dedup keys must name columns and include it.
     */
    static Plan.CreateTable createTable(final Ast.CreateTable create, final Function<String, TableDef> tables) {
        final int pos = create.pos();
        if (!create.ifNotExists() && tables.apply(create.name()) != null) {
            throw new CdbException("cannot create existing table [table=" + create.name() + ']', pos);
        }
        final List<ColumnDef> columns = new ArrayList<>(create.columns().size());
        final Set<String> names = new HashSet<>();
        for (final Ast.ColumnSpec c : create.columns()) {
            if (!names.add(c.name().toLowerCase(Locale.ROOT))) {
                throw new CdbException("cannot repeat column name [name=" + c.name() + ']', pos);
            }
            columns.add(column(c, type(c.type(), pos), pos));
        }
        final TableDef draft = new TableDef(create.name(), columns, -1, PartitionBy.NONE, List.of(), 0);
        int timestamp = -1;
        if (create.timestamp() != null) {
            timestamp = draft.columnIndex(create.timestamp());
            if (timestamp < 0 || columns.get(timestamp).type() != CdbType.TIMESTAMP) {
                throw new CdbException("cannot designate non-timestamp column [column=" + create.timestamp() + ']',
                        pos);
            }
        }
        final PartitionBy partitionBy = partitionBy(create.partitionBy(), pos);
        if (partitionBy != PartitionBy.NONE && timestamp < 0) {
            throw new CdbException("cannot partition table without designated timestamp [table=" + create.name() + ']',
                    pos);
        }
        final List<String> keys = new ArrayList<>(create.dedupKeys().size());
        for (final String k : create.dedupKeys()) {
            final int i = draft.columnIndex(k);
            if (i < 0) {
                throw new CdbException("cannot find dedup key [column=" + k + ']', pos);
            }
            keys.add(columns.get(i).name());
        }
        if (!keys.isEmpty() && (timestamp < 0 || !keys.contains(columns.get(timestamp).name()))) {
            throw new CdbException("cannot dedup without designated timestamp key [table=" + create.name() + ']', pos);
        }
        final long ttl = create.ttl() == null ? 0 : Times.ttlMicros(create.ttl(), pos);
        return new Plan.CreateTable(new TableDef(create.name(), columns, timestamp, partitionBy, keys, ttl),
                create.ifNotExists());
    }

    /**
     * The column {@code spec} declares, its SYMBOL options checked and rounded as QuestDB does: each capacity within
     * its range and raised to a power of two, a cached dictionary's capacity at most
     * {@link #MAX_SYMBOL_CAPACITY_CACHED}.
     */
    private static ColumnDef column(final Ast.ColumnSpec spec, final CdbType type, final int pos) {
        if (type != CdbType.SYMBOL) {
            return new ColumnDef(spec.name(), type);
        }
        int capacity = ColumnDef.DEFAULT_SYMBOL_CAPACITY;
        if (spec.capacity() >= 0) {
            if (spec.capacity() < MIN_SYMBOL_CAPACITY || spec.capacity() > MAX_SYMBOL_CAPACITY) {
                throw new CdbException("symbol capacity out of range [column=" + spec.name() + ", capacity="
                        + spec.capacity() + ", min=" + MIN_SYMBOL_CAPACITY + ", max=" + MAX_SYMBOL_CAPACITY + ']', pos);
            }
            capacity = ceilPow2(spec.capacity());
            if (!spec.noCache() && capacity > MAX_SYMBOL_CAPACITY_CACHED) {
                throw new CdbException("cached symbol capacity out of range [column=" + spec.name() + ", capacity="
                        + capacity + ", max=" + MAX_SYMBOL_CAPACITY_CACHED + ']', pos);
            }
        }
        int indexCapacity = ColumnDef.DEFAULT_INDEX_BLOCK_CAPACITY;
        if (spec.indexCapacity() >= 0) {
            if (spec.indexCapacity() < MIN_INDEX_BLOCK_CAPACITY || spec.indexCapacity() > MAX_INDEX_BLOCK_CAPACITY) {
                throw new CdbException("index block capacity out of range [column=" + spec.name() + ", capacity="
                        + spec.indexCapacity() + ", min=" + MIN_INDEX_BLOCK_CAPACITY + ", max="
                        + MAX_INDEX_BLOCK_CAPACITY + ']', pos);
            }
            indexCapacity = ceilPow2(spec.indexCapacity());
        }
        return new ColumnDef(spec.name(), type, capacity, !spec.noCache(), spec.index(), indexCapacity);
    }

    /** The least power of two at least {@code value}; {@code value} is in [2, 2^30]. */
    private static int ceilPow2(final long value) {
        return (int) (Long.highestOneBit(value - 1) << 1);
    }

    private static PartitionBy partitionBy(final String name, final int pos) {
        if (name == null) {
            return PartitionBy.NONE;
        }
        try {
            return PartitionBy.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException _) {
            throw new CdbException("cannot partition by unknown unit [unit=" + name + ']', pos);
        }
    }
}
