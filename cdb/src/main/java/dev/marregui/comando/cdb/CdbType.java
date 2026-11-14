package dev.marregui.comando.cdb;

/**
 * The column and value types cdb stores and computes. NULL is a sentinel per type, as in QuestDB: {@link Long#MIN_VALUE}
 * for LONG and TIMESTAMP, NaN for DOUBLE, a null reference for SYMBOL and VARCHAR; BOOLEAN has no NULL (false).
 * TIMESTAMP values are microseconds since the epoch, UTC. INT columns of a {@code CREATE TABLE} are stored as LONG.
 */
public enum CdbType {
    BOOLEAN,
    LONG,
    DOUBLE,
    TIMESTAMP,
    SYMBOL,
    VARCHAR;

    /** The type's name in QuestDB's {@code /exec} JSON and in {@code table_columns()}. */
    public String sqlName() {
        return name();
    }

    /** True for the types held as text in pages and results (SYMBOL, VARCHAR). */
    public boolean isText() {
        return this == SYMBOL || this == VARCHAR;
    }
}
