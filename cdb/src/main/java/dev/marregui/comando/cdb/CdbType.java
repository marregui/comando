package dev.marregui.comando.cdb;

/**
 * The column and value types cdb stores and computes. NULL is a sentinel per type, as in QuestDB: {@link Long#MIN_VALUE}
 * for LONG and TIMESTAMP, NaN for DOUBLE, a null reference for SYMBOL, VARCHAR and JSON; BOOLEAN has no NULL
 * (false). TIMESTAMP values are microseconds since the epoch, UTC. INT columns of a {@code CREATE TABLE} are stored as
 * LONG. JSON is a VARCHAR whose every value is one JSON text (RFC 8259), checked when it is written; it is stored,
 * compared and hashed as its text.
 */
public enum CdbType {
    BOOLEAN,
    LONG,
    DOUBLE,
    TIMESTAMP,
    SYMBOL,
    VARCHAR,
    JSON;

    /** The type's name in QuestDB's {@code /exec} JSON and in {@code table_columns()}. */
    public String sqlName() {
        return name();
    }

    /** True for the types held as text in pages and results (SYMBOL, VARCHAR, JSON). */
    public boolean isText() {
        return this == SYMBOL || this == VARCHAR || this == JSON;
    }

    /** True for the types stored as variable-width UTF-8 text in their own files (VARCHAR, JSON). */
    public boolean isVarchar() {
        return this == VARCHAR || this == JSON;
    }
}
