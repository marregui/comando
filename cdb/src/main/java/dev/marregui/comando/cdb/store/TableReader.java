package dev.marregui.comando.cdb.store;

import java.util.List;

/**
 * A snapshot of one table at the transaction committed when it was opened: commits after that are not seen, and the
 * files it reads are kept until it is closed. Any number of readers per table, on any threads. One reader may be read
 * by several threads at once (a query's frames share their segment's reader), so an implementation keeps its reads
 * safe for concurrent use; it is closed once, after every thread reading it is done.
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
