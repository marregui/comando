package dev.marregui.comando.cdb.store;

/**
 * One committed partition of a table, as {@link Store#partitions(String)} lists it: its name as QuestDB names it
 * ({@code 2026-11-01} by day), the range of its rows' designated timestamps, its rows, the bytes of its column files,
 * and whether it is the table's newest, the one that takes writes. Timestamps are {@code Long.MIN_VALUE} for a table
 * without a designated timestamp.
 */
public record PartitionInfo(String name, long minTimestampMicros, long maxTimestampMicros, long rows, long diskBytes,
        boolean active) {
}
