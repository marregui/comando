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

    /** Drops every row, committed and pending, at the next commit: with appends after it, a rewrite of the table. */
    void truncate();

    /** Publishes every pending change as one transaction. */
    void commit();

    /** Drops every change since the last commit. */
    void rollback();

    /** Rolls back what is pending and releases the table. */
    @Override
    void close();
}
