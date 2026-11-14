package dev.marregui.comando.cdb.store;

import java.util.List;

/**
 * A snapshot of one table at the transaction committed when it was opened: commits after that are not seen, and the
 * files it reads are kept until it is closed. Any number of readers per table, on any threads; one reader is used by
 * one thread at a time.
 */
public interface TableReader extends AutoCloseable {

    /** The definition at the snapshot's transaction. */
    TableDef def();

    /** The snapshot's partitions in time order. */
    List<PartitionReader> partitions();

    /** The sum of the partitions' row counts. */
    long rowCount();

    /** Releases the snapshot's files; idempotent. */
    @Override
    void close();
}
