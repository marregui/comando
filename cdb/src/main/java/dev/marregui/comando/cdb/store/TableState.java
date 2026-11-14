package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * One table of a {@link Store}: its folder, its symbol dictionaries, its committed snapshot and the one-writer lease.
 * {@link #lock} guards the snapshot, every {@link Version#refs} of the table and the lease; files are freed outside it.
 */
final class TableState {

    private static final System.Logger LOG = System.getLogger(TableState.class.getName());

    final Path dir;
    final String key;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition writerFree = lock.newCondition();
    // guarded by lock
    private Snapshot current;
    // guarded by lock
    private boolean writerOpen;
    // the writer's alone, and the opening thread's before it: one dictionary per column index, null for non-SYMBOL
    private final List<SymbolTable> symbols = new ArrayList<>();

    /** A committed transaction: its number, the definition and the partitions in time order. */
    record Snapshot(long txn, TableDef def, List<Version> partitions) {

        Snapshot {
            partitions = List.copyOf(partitions);
        }

        long rowCount() {
            long rows = 0L;
            for (final Version v : partitions) {
                rows += v.rowCount();
            }
            return rows;
        }
    }

    TableState(final Path dir, final TableDef def) {
        this.dir = dir;
        this.key = key(def.name());
        for (final ColumnDef column : def.columns()) {
            addSymbols(column);
        }
    }

    static String key(final String table) {
        return table.toLowerCase(Locale.ROOT);
    }

    /** The symbol dictionaries by column index, null for a column that is not SYMBOL. */
    List<SymbolTable> symbols() {
        return symbols;
    }

    /** Adds the dictionary of {@code column}, the next column, opening its file. */
    void addSymbols(final ColumnDef column) {
        symbols.add(column.type() == CdbType.SYMBOL ? SymbolTable.open(dir.resolve(column.name() + ".sym")) : null);
    }

    /** Drops the dictionaries past {@code columns}, those of columns rolled back. */
    void trimSymbols(final int columns) {
        while (symbols.size() > columns) {
            symbols.removeLast();
        }
    }

    /** Publishes the first snapshot, read from the folder at open. */
    void init(final Snapshot snapshot) {
        lock.lock();
        try {
            for (final Version v : snapshot.partitions()) {
                v.refs++;
            }
            current = snapshot;
        } finally {
            lock.unlock();
        }
    }

    /** The committed snapshot, without holding it; for the writer and for {@link Store#def}. */
    Snapshot current() {
        lock.lock();
        try {
            return current;
        } finally {
            lock.unlock();
        }
    }

    /** The committed snapshot, its versions held until {@link #release}. */
    Snapshot acquire() {
        lock.lock();
        try {
            if (current == null) {
                throw new CdbException("refuse read of closed table [table=" + key + ']', -1);
            }
            for (final Version v : current.partitions()) {
                v.refs++;
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    /** Lets go of a snapshot taken by {@link #acquire}; frees the versions nobody holds any more. */
    void release(final Snapshot snapshot) {
        final List<Version> free = new ArrayList<>();
        lock.lock();
        try {
            for (final Version v : snapshot.partitions()) {
                if (--v.refs == 0) {
                    free.add(v);
                }
            }
        } finally {
            lock.unlock();
        }
        free(free);
    }

    /**
     * Makes {@code next} the committed snapshot; the versions only the previous one held are freed. Called after
     * {@code _txn} names {@code next}.
     */
    void publish(final Snapshot next) {
        final List<Version> free = new ArrayList<>();
        final boolean closed;
        lock.lock();
        try {
            closed = current == null;
            if (closed) {
                // the store closed under the writer: _txn names the new versions, which nothing holds, so they are
                // unmapped and kept; those carried over from the snapshot close released are its already. Folders
                // _txn no longer names are the orphans the next open deletes
                for (final Version v : next.partitions()) {
                    if (!v.committed) {
                        v.committed = true;
                        free.add(v);
                    }
                }
            } else {
                for (final Version v : next.partitions()) {
                    v.refs++;
                }
                for (final Version v : current.partitions()) {
                    if (--v.refs == 0) {
                        free.add(v);
                    }
                }
                current = next;
            }
        } finally {
            lock.unlock();
        }
        free(free);
        if (closed) {
            throw new CdbException("refuse commit to closed table [table=" + key + ']', -1);
        }
    }

    /** Takes the writer lease, waiting up to {@code waitMillis} for the holder to let it go. */
    void lease(final long waitMillis) {
        lock.lock();
        try {
            long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
            while (writerOpen) {
                if (remainingNanos <= 0L) {
                    throw new CdbException("cannot open writer held by another [table=" + key + ", waitMillis="
                            + waitMillis + ']', -1);
                }
                remainingNanos = writerFree.awaitNanos(remainingNanos);
            }
            if (current == null) {
                throw new CdbException("refuse writer of closed table [table=" + key + ']', -1);
            }
            writerOpen = true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdbException("cannot open writer, interrupted [table=" + key + ']', e);
        } finally {
            lock.unlock();
        }
    }

    /** Lets go of the writer lease and wakes one waiter. */
    void unlease() {
        lock.lock();
        try {
            writerOpen = false;
            writerFree.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to {@code waitMillis} for the writer to close, then lets go of the committed snapshot: the versions no
     * reader holds are freed now, the others when their reader closes. True when no writer was left open.
     */
    boolean close(final long waitMillis) {
        final Snapshot last;
        final boolean drained;
        lock.lock();
        try {
            drained = awaitWriterClosed(waitMillis);
            last = current;
            current = null;
            if (last != null) {
                for (final Version v : last.partitions()) {
                    v.committed = true;
                }
            }
        } finally {
            lock.unlock();
        }
        if (last != null) {
            release(last);
        }
        return drained;
    }

    // called with lock held
    private boolean awaitWriterClosed(final long waitMillis) {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(waitMillis);
        try {
            while (writerOpen && remainingNanos > 0L) {
                remainingNanos = writerFree.awaitNanos(remainingNanos);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !writerOpen;
    }

    // the versions were taken under lock, so their committed flag is read as the lock left it
    private void free(final List<Version> versions) {
        for (final Version v : versions) {
            try {
                v.unmap();
                if (!v.committed) {
                    StoreFiles.deleteFolder(v.dir);
                }
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot delete partition version [dir=" + v.dir + ']', e);
            }
        }
    }
}
