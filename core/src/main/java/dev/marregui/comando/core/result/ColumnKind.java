package dev.marregui.comando.core.result;

/** Storage class of a result column. */
public enum ColumnKind {
    /** Integer types, stored as {@code long}. */
    LONG,
    /** Floating point types, stored as {@code double}. */
    DOUBLE,
    BOOLEAN,
    /** Date and time types, stored as epoch microseconds UTC. */
    TIMESTAMP,
    /** Everything else, stored as text. */
    TEXT
}
