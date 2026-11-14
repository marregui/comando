package dev.marregui.comando.cdb;

/**
 * Rows written as QuestDB's line protocol writes them: a table, its symbols and fields by name, then the row's
 * timestamp. A table or column not there yet is created, as ILP creates it (a new table: designated timestamp
 * {@code timestamp}, partitioned by DAY; a symbol column SYMBOL, a string field VARCHAR, an integer LONG, a float
 * DOUBLE, a boolean BOOLEAN). Rows are visible to readers after {@link #flush()}, which commits every table touched.
 * One appender per thread; not thread-safe.
 */
public interface CdbAppender extends AutoCloseable {

    /** Starts a row of {@code table}. */
    CdbAppender table(String table);

    /** Sets a SYMBOL column of the current row. */
    CdbAppender symbol(String column, String value);

    /** Sets a VARCHAR column of the current row. */
    CdbAppender stringColumn(String column, String value);

    /** Sets a LONG column of the current row. */
    CdbAppender longColumn(String column, long value);

    /** Sets a DOUBLE column of the current row. */
    CdbAppender doubleColumn(String column, double value);

    /** Sets a BOOLEAN column of the current row. */
    CdbAppender booleanColumn(String column, boolean value);

    /** Sets a TIMESTAMP column (not the designated one) of the current row, in microseconds. */
    CdbAppender timestampColumn(String column, long micros);

    /** Ends the current row at {@code micros}, its designated timestamp. */
    void at(long micros);

    /** Commits every table this appender wrote since the last flush. */
    void flush();

    /** Flushes, then releases the tables' writers. */
    @Override
    void close();
}
