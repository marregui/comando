package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A table's checkpoint: makes durable what its commits since the last one wrote unforced, then names their snapshot in
 * {@code _txn}, so the log can be cut back to its header. In order: the column files of the folders written that the
 * snapshot names, the entries of those of them created, the symbol dictionaries, {@code _meta} when the definition
 * changed, the table's folder when it holds new partition folders {@code _meta}'s replace did not force, then
 * {@code _txn}. Runs under the table's apply lock.
 */
final class Checkpoint {

    private static final System.Logger LOG = System.getLogger(Checkpoint.class.getName());

    private Checkpoint() {
    }

    /**
     * Checkpoints {@code snapshot}, the table's published one or the one a commit is about to publish. Throws only
     * before {@code _txn} is moved, which leaves the previous checkpoint and the log as they were. False when
     * {@code _txn} names the snapshot but the force of its folder failed: the log, the folders the previous
     * checkpoint named and the snapshot's are kept, so either {@code _txn} a power cut leaves recovers the same state.
     */
    static boolean run(final Store store, final TableState state, final TableState.Snapshot snapshot)
            throws IOException {
        for (final String folder : state.written()) {
            if (TableState.names(snapshot, folder)) {
                try (final DirectoryStream<Path> files = Files.newDirectoryStream(state.dir.resolve(folder))) {
                    for (final Path file : files) {
                        StoreFiles.force(file);
                    }
                }
            }
        }
        boolean entries = false;
        for (final String folder : state.created()) {
            if (TableState.names(snapshot, folder)) {
                StoreFiles.forceFolder(state.dir.resolve(folder));
                entries = true;
            }
        }
        final TableDef def = snapshot.def();
        final List<ColumnDef> columns = def.columns();
        final long[] symbolBytes = new long[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            final SymbolTable symbols = state.symbols().get(c);
            if (symbols == null) {
                symbolBytes[c] = -1L;
            } else {
                // a dictionary's first sync creates its file, an entry of the table folder like a new partition's
                entries |= symbols.size() > 0 && !Files.exists(symbols.file());
                symbols.sync();
                symbolBytes[c] = symbols.syncedBytes();
            }
        }
        if (!def.equals(state.checkpointed().def())) {
            final Path meta = state.dir.resolve(TableFiles.META);
            // a _txn naming columns that a lost _meta rename leaves out would not open
            if (!StoreFiles.replaceText(meta, TableFiles.encodeMeta(def))) {
                throw new IOException("cannot force folder after replacing definition [file=" + meta + ']');
            }
            // that folder force made the new entries durable with _meta
            entries = false;
        }
        if (entries) {
            // unforced entries of one folder persist in any order: a _txn naming a folder whose entry a power cut
            // lost would not open
            StoreFiles.forceFolder(state.dir);
        }
        final List<TableFiles.Entry> partitions = new ArrayList<>(snapshot.partitions().size());
        for (final Version v : snapshot.partitions()) {
            partitions.add(new TableFiles.Entry(v.folder, v.rowCount()));
        }
        store.beforeTxnReplace.run();
        if (!StoreFiles.replaceText(state.dir.resolve(TableFiles.TXN), TableFiles.encodeTxn(new TableFiles.Txn(
                snapshot.txn(), partitions, columns.size(), def.ttlMicros(), symbolBytes)))) {
            state.recordInDoubt(snapshot);
            return false;
        }
        state.recordCheckpoint(snapshot);
        state.written().clear();
        state.created().clear();
        state.countCheckpoint(snapshot.txn());
        try {
            // a commit logged after the snapshot keeps the log until a checkpoint with none after it
            state.wal.resetThrough(snapshot.txn());
        } catch (final IOException e) {
            // the records left are at or before _txn's transaction, which a scan skips; the next checkpoint cuts them
            LOG.log(System.Logger.Level.WARNING, "cannot reset log after checkpoint [table=" + def.name() + ", txn="
                    + snapshot.txn() + ']', e);
        }
        LOG.log(System.Logger.Level.DEBUG, "checkpointed table [table=" + def.name() + ", txn=" + snapshot.txn()
                + ", partitions=" + partitions.size() + ']');
        return true;
    }
}
