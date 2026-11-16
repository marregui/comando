package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
    // its own monitor's: the lease holder appends, the applier cuts after a checkpoint
    final Wal wal;

    /**
     * Held by whoever writes the column files, the dictionaries, {@link #torn}, {@link #written} and {@link #created},
     * or checkpoints: the applier, a direct commit, the store's checkpoint and close. Taken before {@link #lock}.
     */
    final ReentrantLock applyLock = new ReentrantLock();
    // the store's mappings, shared by every table's versions
    final Mappings mappings;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition writerFree = lock.newCondition();
    private final Condition applied = lock.newCondition();
    // guarded by lock: the commits answered from the log and not yet applied, in transaction order
    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    // guarded by lock
    private long queuedRows;
    // guarded by lock
    private long queuedBytes;
    // guarded by lock: the bytes of queued commits published and not yet taken by the applier's accounting
    private long releasedBytes;
    // guarded by lock: readers waiting for the applier since it last applied the table
    private int demands;
    // written by the lease holder once a commit is answered, under lock; read by readers and the applier
    private volatile long loggedTxn;
    // written under lock when a snapshot is published; read by readers deciding whether to wait
    private volatile long appliedTxn;
    // written by the applier when it gives up on the table; read by commits and readers
    private volatile boolean suspended;
    // written by the applier under applyLock, read by the status listing
    private volatile String lastError;
    // the applier's, under applyLock; the status listing reads them racily
    private volatile long applies;
    private volatile long demandedApplies;
    private volatile long appliedTxns;
    private volatile long appliedRows;
    private volatile long lastApplyMicros;
    private volatile long applyMicros;
    private volatile long checkpoints;
    private volatile long checkpointNanos = System.nanoTime();
    // the applier thread's alone
    final ApplySchedule schedule = new ApplySchedule();
    // the applier thread's alone: batches failed in a row
    int failures;
    // the applier thread's alone: when a failed batch is retried, by System.nanoTime()
    long retryNanos;
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
    // applyLock's, and the opening thread's before it: one dictionary per column index, null for non-SYMBOL
    private final List<SymbolTable> symbols = new ArrayList<>();
    // applyLock's: folders a failed apply left bytes in past their committed ends, cut only at the next open
    private final Set<String> torn = new HashSet<>();
    // applyLock's: the folders applies wrote to since the last checkpoint, and those of them they created
    private final Set<String> written = new HashSet<>();
    private final Set<String> created = new HashSet<>();
    // written by the lease holder or the applier when a log force failed, or a checkpoint left its _txn in doubt; read
    // by commits; until reopen
    private volatile boolean poisoned;

    /** A commit answered from the log, not yet applied: its rows, when it was queued and its rows' heap estimate. */
    record Queued(long txn, RowBlock rows, long queuedNanos, long bytes) {
    }

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
     * length its symbol file had at the last checkpoint, the file cut to it (-1, or past its end: taken as it is);
     * {@code mappings} is the store's.
     */
    TableState(final Path dir, final TableDef def, final long[] symbolBytes, final Wal wal, final Mappings mappings) {
        this.dir = dir;
        this.key = key(def.name());
        this.wal = wal;
        this.mappings = mappings;
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

    /**
     * True when the applier gave up on the table after repeated failures: its commits are refused and reads that need
     * the queued ones fail, until the store reopens and replays the log.
     */
    boolean suspended() {
        return suspended;
    }

    void suspend(final String error) {
        lastError = error;
        suspended = true;
        signalApplied();
    }

    void failed(final String error) {
        lastError = error;
    }

    /** The last transaction answered: logged, or written by a direct commit. */
    long loggedTxn() {
        return loggedTxn;
    }

    /** The last transaction published to readers. */
    long appliedTxn() {
        return appliedTxn;
    }

    /** Records the direct commit {@code txn}, published without the log. */
    void logged(final long txn) {
        lock.lock();
        try {
            loggedTxn = txn;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Queues {@code commit}, answered once this returns; its transaction becomes the logged one. Refused once the table
     * closed: a writer left open past the store's close must not answer a commit no applier will publish; the writer
     * then cuts the commit's record out of the log.
     */
    void enqueue(final Queued commit) {
        lock.lock();
        try {
            if (current == null) {
                throw new CdbException("refuse commit to closed table [table=" + key + ", txn=" + commit.txn() + ']',
                        -1);
            }
            queue.addLast(commit);
            queuedRows += commit.rows().size;
            queuedBytes += commit.bytes();
            loggedTxn = commit.txn();
        } finally {
            lock.unlock();
        }
    }

    /** The queued commits from the oldest, as many as fit in {@code maxRows} rows and at least one; empty when none. */
    List<Queued> peekQueued(final int maxRows) {
        lock.lock();
        try {
            final List<Queued> batch = new ArrayList<>();
            long rows = 0L;
            for (final Queued q : queue) {
                if (!batch.isEmpty() && rows + q.rows().size > maxRows) {
                    break;
                }
                batch.add(q);
                rows += q.rows().size;
            }
            return batch;
        } finally {
            lock.unlock();
        }
    }

    /** The bytes of the queued commits published since the last call, which {@link #publish} dropped. */
    long takeReleasedBytes() {
        lock.lock();
        try {
            final long released = releasedBytes;
            releasedBytes = 0L;
            return released;
        } finally {
            lock.unlock();
        }
    }

    // called with lock held: drops the queued commits up to txn, published with it, so no reader sees them twice
    private void dequeueThrough(final long txn) {
        while (!queue.isEmpty() && queue.peekFirst().txn() <= txn) {
            final Queued q = queue.removeFirst();
            queuedRows -= q.rows().size;
            queuedBytes -= q.bytes();
            releasedBytes += q.bytes();
        }
    }

    /** When the oldest queued commit was queued, by {@link System#nanoTime()}; {@code none} when nothing is queued. */
    long oldestQueuedNanos(final long none) {
        lock.lock();
        try {
            return queue.isEmpty() ? none : queue.peekFirst().queuedNanos();
        } finally {
            lock.unlock();
        }
    }

    /** The rows of the queued commits. */
    long queuedRows() {
        lock.lock();
        try {
            return queuedRows;
        } finally {
            lock.unlock();
        }
    }

    /** The heap estimate of the queued commits. */
    long queuedBytes() {
        lock.lock();
        try {
            return queuedBytes;
        } finally {
            lock.unlock();
        }
    }

    /** Counts a reader that is about to wait for the applier, before it wakes the applier. */
    void demand() {
        lock.lock();
        try {
            demands++;
        } finally {
            lock.unlock();
        }
    }

    /** True when a reader waited for the applier since the last call; clears it. */
    boolean takeDemand() {
        lock.lock();
        try {
            final boolean demanded = demands > 0;
            demands = 0;
            return demanded;
        } finally {
            lock.unlock();
        }
    }

    /** True when a reader waits for the applier. */
    boolean demanded() {
        lock.lock();
        try {
            return demands > 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits up to {@code timeoutNanos} for transaction {@code txn} to be published; false when it was not: the wait
     * ran out, the table closed or its applier gave up.
     */
    boolean awaitApplied(final long txn, final long timeoutNanos) {
        lock.lock();
        try {
            long remainingNanos = timeoutNanos;
            while (current != null && current.txn() < txn && !suspended) {
                if (remainingNanos <= 0L) {
                    return false;
                }
                remainingNanos = applied.awaitNanos(remainingNanos);
            }
            return current != null && current.txn() >= txn;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdbException("cannot read table, interrupted waiting for its commits [table=" + key + ", txn="
                    + txn + ']', e);
        } finally {
            lock.unlock();
        }
    }

    /** Counts a batch the applier published: {@code txns} commits of {@code rows} rows in {@code micros}. */
    void countApply(final long txns, final long rows, final long micros, final boolean demanded) {
        applies++;
        if (demanded) {
            demandedApplies++;
        }
        appliedTxns += txns;
        appliedRows += rows;
        lastApplyMicros = micros;
        applyMicros += micros;
        lastError = null;
    }

    /** Counts a checkpoint of transaction {@code txn}. */
    void countCheckpoint(final long txn) {
        checkpoints++;
        checkpointNanos = System.nanoTime();
    }

    /** When the last checkpoint ran, by {@link System#nanoTime()}, or the table opened. */
    long checkpointNanos() {
        return checkpointNanos;
    }

    /** The table's log and its application now, under the definition's name {@code table}. */
    WalStatus status(final String table, final long nowNanos, final int memoryPressure) {
        final long txns;
        final long rows;
        final long bytes;
        final long oldest;
        lock.lock();
        try {
            txns = queue.size();
            rows = queuedRows;
            bytes = queuedBytes;
            oldest = queue.isEmpty() ? nowNanos : queue.peekFirst().queuedNanos();
        } finally {
            lock.unlock();
        }
        final Snapshot checkpointedSnapshot = checkpointed();
        return new WalStatus(table, poisoned || suspended, appliedTxn, loggedTxn, txns, rows, bytes,
                TimeUnit.NANOSECONDS.toMillis(nowNanos - oldest),
                TimeUnit.NANOSECONDS.toMillis(schedule.intervalNanos()),
                applies, demandedApplies, appliedTxns, appliedRows, lastApplyMicros, applyMicros, checkpoints,
                checkpointedSnapshot == null ? 0L : checkpointedSnapshot.txn(), wal.bytes(), memoryPressure, lastError);
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
            loggedTxn = snapshot.txn();
            appliedTxn = snapshot.txn();
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

    /**
     * Releases the mappings of the committed snapshot's versions no read used for {@code idleNanos} before
     * {@code nowNanos}; the next read maps them again. The versions are held meanwhile, so none is unmapped under the
     * sweep. A closed table is skipped.
     */
    void releaseIdle(final long nowNanos, final long idleNanos) {
        final Snapshot held;
        lock.lock();
        try {
            if (current == null) {
                return;
            }
            held = current;
            for (final Version v : held.partitions()) {
                hold(v);
            }
        } finally {
            lock.unlock();
        }
        try {
            for (final Version v : held.partitions()) {
                v.releaseIfIdle(nowNanos, idleNanos);
            }
        } finally {
            release(held);
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
                dequeueThrough(next.txn());
                appliedTxn = next.txn();
                if (next.txn() > loggedTxn) {
                    // a direct commit or a replay, published without the queue
                    loggedTxn = next.txn();
                }
                applied.signalAll();
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

    /** Takes the writer lease when nobody holds it; false, without waiting, when somebody does. */
    boolean tryLease() {
        lock.lock();
        try {
            if (writerOpen || current == null) {
                return false;
            }
            writerOpen = true;
            return true;
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
        final List<Queued> unapplied;
        // the apply lock first (the lock order): an apply in progress, the applier's or a direct commit's, still
        // copies queued blocks, so they are freed only once it ends
        applyLock.lock();
        try {
            lock.lock();
            try {
                last = current;
                current = null;
                applied.signalAll();
                if (leased) {
                    writerOpen = false;
                    writerFree.signalAll();
                }
                // commits the applier never published (it stopped first, or the table was suspended): logged, so the
                // next open replays them; their blocks are freed here, they have no other owner
                unapplied = new ArrayList<>(queue);
                queue.clear();
            } finally {
                lock.unlock();
            }
            for (final Queued q : unapplied) {
                q.rows().close();
            }
        } finally {
            applyLock.unlock();
        }
        if (last != null) {
            release(last);
        }
    }

    private void signalApplied() {
        lock.lock();
        try {
            applied.signalAll();
        } finally {
            lock.unlock();
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
