package dev.marregui.comando.cdb.store;

/** How a table's rows are split into partitions by their designated timestamp, in UTC. */
public enum PartitionBy {
    NONE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR
}
