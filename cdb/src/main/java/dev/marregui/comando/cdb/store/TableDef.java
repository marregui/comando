package dev.marregui.comando.cdb.store;

import java.util.ArrayList;
import java.util.List;

/**
 * A table's definition: its columns in order, the index of its designated timestamp column, its partitioning, the
 * upsert keys of its dedup (empty when it has none; the designated timestamp is always one of them when present) and
 * its retention ({@code ttlMicrosOrMonths}: 0 for none, a positive span in microseconds, or a negative count of
 * calendar months, as QuestDB's {@code ttlHoursOrMonths}; a partition goes once its end lies the TTL before the newest
 * row or now, whichever is earlier).
 */
public record TableDef(
        String name,
        List<ColumnDef> columns,
        int timestampIndex,
        PartitionBy partitionBy,
        List<String> dedupKeys,
        long ttlMicrosOrMonths
) {

    /** A copy of the lists; a negative {@code timestampIndex} only for a table without a designated timestamp. */
    public TableDef {
        columns = List.copyOf(columns);
        dedupKeys = List.copyOf(dedupKeys);
    }

    /** The index of the column named {@code column}, matched without regard to case as QuestDB does; -1 when none. */
    public int columnIndex(final String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    /** The same table with {@code column} added last. */
    public TableDef withColumn(final ColumnDef column) {
        final List<ColumnDef> more = new ArrayList<>(columns);
        more.add(column);
        return new TableDef(name, more, timestampIndex, partitionBy, dedupKeys, ttlMicrosOrMonths);
    }

    /**
     * The same table without column {@code c}, not the designated timestamp nor a dedup key: the designated
     * timestamp's index moves down when it came after {@code c}.
     */
    TableDef withoutColumn(final int c) {
        final List<ColumnDef> fewer = new ArrayList<>(columns);
        fewer.remove(c);
        return new TableDef(name, fewer, timestampIndex > c ? timestampIndex - 1 : timestampIndex, partitionBy,
                dedupKeys, ttlMicrosOrMonths);
    }

    /** The same table with column {@code c} named {@code to}, a dedup key of its name renamed with it. */
    TableDef withColumnRenamed(final int c, final String to) {
        final ColumnDef old = columns.get(c);
        final List<ColumnDef> renamed = new ArrayList<>(columns);
        renamed.set(c, new ColumnDef(to, old.type(), old.symbolCapacity(), old.symbolCached(), old.indexed(),
                old.indexBlockCapacity()));
        final List<String> keys = new ArrayList<>(dedupKeys.size());
        for (final String key : dedupKeys) {
            keys.add(key.equalsIgnoreCase(old.name()) ? to : key);
        }
        return new TableDef(name, renamed, timestampIndex, partitionBy, keys, ttlMicrosOrMonths);
    }

    /** The same table with its TTL set to {@code ttlMicrosOrMonths}, as {@link #ttlMicrosOrMonths} holds it. */
    public TableDef withTtlMicrosOrMonths(final long ttlMicrosOrMonths) {
        return new TableDef(name, columns, timestampIndex, partitionBy, dedupKeys, ttlMicrosOrMonths);
    }
}
