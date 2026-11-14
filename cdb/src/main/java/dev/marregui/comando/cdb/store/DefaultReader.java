package dev.marregui.comando.cdb.store;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A snapshot reader: holds the versions of one committed transaction until closed. */
final class DefaultReader implements TableReader {

    private final TableState state;
    private final TableState.Snapshot snapshot;
    private final List<PartitionReader> partitions;
    private final AtomicBoolean closed = new AtomicBoolean();

    DefaultReader(final TableState state, final TableState.Snapshot snapshot) {
        this.state = state;
        this.snapshot = snapshot;
        this.partitions = List.copyOf(snapshot.partitions());
    }

    @Override
    public TableDef def() {
        return snapshot.def();
    }

    @Override
    public List<PartitionReader> partitions() {
        return partitions;
    }

    @Override
    public long rowCount() {
        return snapshot.rowCount();
    }

    /** The transaction this snapshot shows. */
    long txn() {
        return snapshot.txn();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            state.release(snapshot);
        }
    }
}
