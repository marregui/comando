package dev.marregui.comando.cdb.serve;

import dev.marregui.comando.cdb.CdbType;

/**
 * The answer to one statement as the JSON writer reads it: {@link dev.marregui.comando.cdb.CdbResult}'s accessors, plus
 * what QuestDB's JSON shows and the result does not carry: the designated timestamp's column and whether the
 * statement was DDL. Values follow {@link CdbType}'s NULL sentinels.
 */
interface Rows {

    int columnCount();

    String columnName(int column);

    CdbType type(int column);

    int rowCount();

    /** The rows changed by a statement that changes the store; -1 for a query. */
    long updateCount();

    /** True for a statement that changed the schema (CREATE TABLE), answered {@code {"ddl":"OK"}}. */
    boolean isDdl();

    /** The column of the designated timestamp, -1 when the result has none. */
    int timestampIndex();

    boolean isNull(int row, int column);

    long getLong(int row, int column);

    double getDouble(int row, int column);

    boolean getBoolean(int row, int column);

    String getText(int row, int column);
}
