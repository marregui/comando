package dev.marregui.comando.cdb.serve;

/** What a presented secret lets a client do. */
enum Role {
    /** No secret, or a wrong one: nothing. */
    NONE,
    /** The read-only secret: SELECT, SHOW and EXPLAIN only. */
    READ_ONLY,
    /** The owner's secret: every statement and line-protocol writes. */
    OWNER
}
