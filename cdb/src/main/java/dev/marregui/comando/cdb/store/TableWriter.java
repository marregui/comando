package dev.marregui.comando.cdb.store;

/**
 * The one writer of a table: rows appended in any timestamp order, invisible to readers until {@link #commit()}, which
 * publishes them atomically (sorted into their partitions, deduplicated on the table's upsert keys, the last row of a
 * key winning, and the table's TTL applied). A crash before the commit's last step leaves the previous transaction.
 * One thread at a time; {@link Store#writer} hands out one writer per table and waits for it to be closed.
 */
public interface TableWriter extends AutoCloseable {

    TableDef def();

    /** Adds {@code column} at the end of the table; existing rows read it as NULL. Returns its index. */
    int addColumn(ColumnDef column);

    /**
     * Sets the table's TTL to {@code ttlMicrosOrMonths}, as {@link TableDef#ttlMicrosOrMonths} holds it; the commit
     * stores it and drops what it expires.
     */
    void setTtl(long ttlMicrosOrMonths);

    /**
     * Drops the column {@code name} as the table names it (any case), not the designated timestamp: from the next
     * commit, one direct transaction, the table has no such column; a reader holding an older snapshot reads it on.
     */
    void dropColumn(String name);

    /**
     * Renames the column {@code from} (any case) to {@code to}, a name no other column has: from the next commit, one
     * direct transaction; a reader holding an older snapshot reads the old name.
     */
    void renameColumn(String from, String to);

    /** Starts a row whose designated timestamp is {@code micros}; every column not set reads as NULL. */
    void newRow(long micros);

    /** LONG and TIMESTAMP columns. */
    void putLong(int column, long value);

    void putDouble(int column, double value);

    void putBoolean(int column, boolean value);

    /** SYMBOL and VARCHAR columns; null for NULL. */
    void putText(int column, String value);

    /** Ends the row started by {@link #newRow}. */
    void endRow();

    /**
     * The durability of this writer's last answered commit ({@link ForceTicket#DONE} before any): what a durable
     * request waits on. Correct as a default for a store that forces each commit.
     */
    default ForceTicket ticket() {
        return ForceTicket.DONE;
    }

    /** Drops every row, committed and pending, at the next commit: with appends after it, a rewrite of the table. */
    void truncate();

    /**
     * Drops, at the next commit, every committed partition whose whole range lies in {@code [fromMicros, toMicros)},
     * and the pending rows that fall in such a range: the rows appended after the call stay, in new partitions.
     */
    void dropPartitions(long fromMicros, long toMicros);

    /** Publishes every pending change as one transaction. */
    void commit();

    /** Drops every change since the last commit. */
    void rollback();

    /** Rolls back what is pending and releases the table. */
    @Override
    void close();
}
