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
 * One table of a {@link Store}: its folder, its log, its symbol dictionaries, its committed snapshot, the snapshot
 * {@code _txn} names (the last checkpoint's) and the one-writer lease. {@link #lock} guards both snapshots, every
 * {@link Version#refs} of the table, the holders of each folder and the lease; files are freed outside it. Snapshots
 * may hold several versions of one folder, each at its own row count (a commit appends in place): a version is unmapped
 * when no snapshot holds it, and its folder deleted when no held version maps it and the checkpointed {@code _txn} does
 * not name it, since a crash brings that transaction back.
 */
final class TableState {

    private static final System.Logger LOG = System.getLogger(TableState.class.getName());

    final Path dir;
    final String key;
    // the writer's (the lease holder's), and the opening thread's before it
    final Wal wal;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition writerFree = lock.newCondition();
    // guarded by lock
    private Snapshot current;
    // guarded by lock: the snapshot the checkpointed _txn names; a folder it names is never deleted
    private Snapshot named;
    // guarded by lock: the snapshots whose _txn was moved into place without its folder force holding since named's,
    // so any of them or named may come back after a power cut; their folders are kept as named's until one holds
    private final List<Snapshot> doubted = new ArrayList<>();
    // guarded by lock: the last published snapshot, current while the table is open and the last one after close
    private Snapshot latest;
    // guarded by lock: per folder, the versions mapping it that some snapshot holds
    private final Map<String, Integer> holders = new HashMap<>();
    // guarded by lock
    private boolean writerOpen;
    // the writer's alone, and the opening thread's before it: one dictionary per column index, null for non-SYMBOL
    private final List<SymbolTable> symbols = new ArrayList<>();
    // the writer's alone: folders a failed commit left bytes in past their committed ends, cut only at the next open
    private final Set<String> torn = new HashSet<>();
    // the lease holder's: the folders commits wrote to since the last checkpoint, and those of them they created
    private final Set<String> written = new HashSet<>();
    private final Set<String> created = new HashSet<>();
    // the lease holder's: set when a log force failed, or a commit's checkpoint left its _txn in doubt; until reopen
    private boolean poisoned;

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

    /**
     * The table in {@code dir} of definition {@code def}, its log {@code wal}; {@code symbolBytes} holds per column the
     * length its symbol file had at the last checkpoint, the file cut to it (-1, or past its end: taken as it is).
     */
    TableState(final Path dir, final TableDef def, final long[] symbolBytes, final Wal wal) {
        this.dir = dir;
        this.key = key(def.name());
        this.wal = wal;
        final List<ColumnDef> columns = def.columns();
        for (int c = 0; c < columns.size(); c++) {
            addSymbols(columns.get(c), c < symbolBytes.length ? symbolBytes[c] : -1L);
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
        addSymbols(column, -1L);
    }

    /**
     * True once a force failed under a commit: its log append, or a checkpoint after a logged commit, of a direct
     * commit or after a replay. The page cache cannot be trusted after it, so commits are refused until reopen.
     */
    boolean poisoned() {
        return poisoned;
    }

    void poison() {
        poisoned = true;
    }

    /** Records {@code versions}, written by a commit, for the next checkpoint to force; {@code fresh} if it made them. */
    void wrote(final List<Version> versions, final boolean fresh) {
        for (final Version v : versions) {
            written.add(v.folder);
            if (fresh) {
                created.add(v.folder);
            }
        }
    }

    /** The folders written since the last checkpoint. */
    Set<String> written() {
        return written;
    }

    /** The folders created since the last checkpoint. */
    Set<String> created() {
        return created;
    }

    /** The snapshot the checkpointed {@code _txn} names. */
    Snapshot checkpointed() {
        lock.lock();
        try {
            return named;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Makes {@code snapshot} the checkpointed one, once {@code _txn} names it durably: the folders only the previous
     * checkpointed snapshot or those in doubt since it named, which no snapshot holds, are deleted.
     */
    void recordCheckpoint(final Snapshot snapshot) {
        final List<Path> delete = new ArrayList<>();
        lock.lock();
        try {
            final List<Version> previous = new ArrayList<>(named.partitions());
            for (final Snapshot d : doubted) {
                previous.addAll(d.partitions());
            }
            named = snapshot;
            doubted.clear();
            for (final Version v : previous) {
                if (!holders.containsKey(v.folder) && !names(snapshot, v.folder) && !delete.contains(v.dir)) {
                    delete.add(v.dir);
                }
            }
        } finally {
            lock.unlock();
        }
        free(List.of(), delete);
    }

    /**
     * Keeps the folders of {@code snapshot} as those of the checkpointed one: its {@code _txn} was moved into place but
     * the folder force failed, so a power cut may bring it or any earlier one back.
     */
    void recordInDoubt(final Snapshot snapshot) {
        lock.lock();
        try {
            doubted.add(snapshot);
        } finally {
            lock.unlock();
        }
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
            latest = snapshot;
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
     * Makes {@code next} the committed snapshot; the versions only the previous one held are freed. Called once
     * {@code next} is durable: its record forced in the log, or {@code _txn} naming it.
     */
    void publish(final Snapshot next) {
        final List<Version> unmap = new ArrayList<>();
        final List<Path> delete = new ArrayList<>();
        final boolean closed;
        lock.lock();
        try {
            closed = current == null;
            final Snapshot previous = latest;
            latest = next;
            if (closed) {
                // the store closed under the writer: the new versions, which nothing holds, are unmapped and their
                // folders kept for the next open, which deletes those the checkpointed _txn does not name and replays
                // the log; those carried over from the snapshot close released are its already
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
     * Waits up to {@code waitMillis} for the writer to close and takes its lease for the closing store, so no writer
     * opens before {@link #close}; true when it could, false when a writer was left open.
     */
    boolean leaseForClose(final long waitMillis) {
        lock.lock();
        try {
            final boolean drained = awaitWriterClosed(waitMillis);
            if (drained) {
                writerOpen = true;
            }
            return drained;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Lets go of the committed snapshot, and of the lease {@link #leaseForClose} took: the versions no reader holds
     * are freed now, the others when their reader closes; a writer waiting for the lease is refused.
     */
    void close(final boolean leased) {
        final Snapshot last;
        lock.lock();
        try {
            last = current;
            current = null;
            if (leased) {
                writerOpen = false;
                writerFree.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (last != null) {
            release(last);
        }
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
    // version maps it and the checkpointed _txn does not name it
    private void letGo(final Version v, final List<Version> unmap, final List<Path> delete) {
        if (--v.refs > 0) {
            return;
        }
        unmap.add(v);
        if (holders.merge(v.folder, -1, Integer::sum) == 0) {
            holders.remove(v.folder);
            if (!checkpointedNames(v.folder)) {
                delete.add(v.dir);
            }
        }
    }

    private void addSymbols(final ColumnDef column, final long committedBytes) {
        symbols.add(column.type() == CdbType.SYMBOL
                ? SymbolTable.open(dir.resolve(column.name() + ".sym"), committedBytes)
                : null);
    }

    // called with lock held: true when a _txn a power cut may bring back names folder
    private boolean checkpointedNames(final String folder) {
        if (names(named, folder)) {
            return true;
        }
        for (final Snapshot d : doubted) {
            if (names(d, folder)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code snapshot} has a version in {@code folder}. */
    static boolean names(final Snapshot snapshot, final String folder) {
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
