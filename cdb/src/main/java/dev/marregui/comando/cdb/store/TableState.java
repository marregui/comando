package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * One table of a {@link Store}: its folder, its symbol dictionaries, its committed snapshot and the one-writer lease.
 * {@link #lock} guards the snapshot, every {@link Version#refs} of the table, the holders of each folder and the lease;
 * files are freed outside it. Snapshots may hold several versions of one folder, each at its own row count (a commit
 * appends in place): a version is unmapped when no snapshot holds it, and its folder deleted when no held version maps
 * it and {@code _txn} no longer names it.
 */
final class TableState {

    private static final System.Logger LOG = System.getLogger(TableState.class.getName());

    final Path dir;
    final String key;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition writerFree = lock.newCondition();
    // guarded by lock
    private Snapshot current;
    // guarded by lock: the snapshot _txn names, current while the table is open and the last one after close
    private Snapshot named;
    // guarded by lock: per folder, the versions mapping it that some snapshot holds
    private final Map<String, Integer> holders = new HashMap<>();
    // guarded by lock
    private boolean writerOpen;
    // the writer's alone, and the opening thread's before it: one dictionary per column index, null for non-SYMBOL
    private final List<SymbolTable> symbols = new ArrayList<>();
    // the writer's alone: folders a failed commit left bytes in past their committed ends, cut only at the next open
    private final Set<String> torn = new HashSet<>();

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

    /**
     * The folders a failed commit appended to: their files run past the committed ends until the store opens again and
     * cuts them, so a commit rewrites them rather than append, which a shorter append would leave a stale tail in.
     */
    Set<String> torn() {
        return torn;
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
                hold(v);
            }
            current = snapshot;
            named = snapshot;
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
                hold(v);
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    /** Lets go of a snapshot taken by {@link #acquire}; frees the versions nobody holds any more. */
    void release(final Snapshot snapshot) {
        final List<Version> unmap = new ArrayList<>();
        final List<Path> delete = new ArrayList<>();
        lock.lock();
        try {
            for (final Version v : snapshot.partitions()) {
                letGo(v, unmap, delete);
            }
        } finally {
            lock.unlock();
        }
        free(unmap, delete);
    }

    /**
     * Makes {@code next} the committed snapshot; the versions only the previous one held are freed. Called after
     * {@code _txn} names {@code next}.
     */
    void publish(final Snapshot next) {
        final List<Version> unmap = new ArrayList<>();
        final List<Path> delete = new ArrayList<>();
        final boolean closed;
        lock.lock();
        try {
            closed = current == null;
            final Snapshot previous = named;
            named = next;
            if (closed) {
                // the store closed under the writer: _txn names the new versions, which nothing holds, so they are
                // unmapped and their folders kept; those carried over from the snapshot close released are its
                // already. A folder _txn no longer names goes with its last holder, or is an orphan the next open
                // deletes
                for (final Version v : next.partitions()) {
                    if (!previous.partitions().contains(v)) {
                        unmap.add(v);
                    }
                }
            } else {
                for (final Version v : next.partitions()) {
                    hold(v);
                }
                for (final Version v : current.partitions()) {
                    letGo(v, unmap, delete);
                }
                current = next;
            }
        } finally {
            lock.unlock();
        }
        free(unmap, delete);
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

    // called with lock held
    private void hold(final Version v) {
        if (v.refs++ == 0) {
            holders.merge(v.folder, 1, Integer::sum);
        }
    }

    // called with lock held: the version goes to unmap when no snapshot holds it, its folder to delete when no held
    // version maps it and _txn does not name it
    private void letGo(final Version v, final List<Version> unmap, final List<Path> delete) {
        if (--v.refs > 0) {
            return;
        }
        unmap.add(v);
        if (holders.merge(v.folder, -1, Integer::sum) == 0) {
            holders.remove(v.folder);
            if (!names(named, v.folder)) {
                delete.add(v.dir);
            }
        }
    }

    private static boolean names(final Snapshot snapshot, final String folder) {
        for (final Version v : snapshot.partitions()) {
            if (v.folder.equals(folder)) {
                return true;
            }
        }
        return false;
    }

    // every version is unmapped before a folder is deleted: a folder to delete is mapped by none of them
    private void free(final List<Version> versions, final List<Path> folders) {
        for (final Version v : versions) {
            try {
                v.unmap();
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot unmap partition version [dir=" + v.dir + ']', e);
            }
        }
        for (final Path folder : folders) {
            try {
                StoreFiles.deleteFolder(folder);
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot delete partition version [dir=" + folder + ']', e);
            }
        }
    }
}
