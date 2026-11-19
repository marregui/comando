package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.io.Serial;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The tables under one root folder: {@code <root>/<table>/} holds {@code _meta} (the {@link TableDef}), {@code _txn}
 * (the last checkpoint: each partition's folder name and row count, replaced atomically by rename), {@code _wal} (the
 * {@link Wal} of the commits since) and one folder per partition version, {@code <partition>.<txn>/}, with one file
 * per column. Opening a table replays its log; closing the store checkpoints every table. Thread-safe.
 */
public final class Store implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Store.class.getName());
    // how long close waits for each table's open writer, and for an apply holding its apply lock; past it the close
    // fails and keeps the root's lock
    private static final long CLOSE_WAIT_MILLIS = 10_000L;
    // how long a checkpoint waits for the table's writer; as CLOSE_WAIT_MILLIS
    private static final long CHECKPOINT_WAIT_MILLIS = 10_000L;
    // the root's lock file; a store holds it from open to close, so two never write one root
    private static final String LOCK = "_lock";
    // the process's limit on memory mappings, on Linux; absent elsewhere, so no limit is watched
    private static final Path MAX_MAP_COUNT = Path.of("/proc/sys/vm/max_map_count");
    // a partition version no read used this long has its mappings released, the next read maps it again: 60 s, set
    // with the lazy mapping and not measured, long enough that a dashboard refreshing each minute keeps its maps
    private static final long MAP_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60L);
    // how often the applier looks for idle versions: 10 s, set with MAP_IDLE_NANOS, so a version is released at most
    // 70 s after its last read
    static final long MAP_SWEEP_NANOS = TimeUnit.SECONDS.toNanos(10L);

    /** Run by a commit right before it replaces {@code _txn}: a crash test stops the process there. */
    @TestOnly
    Runnable beforeTxnReplace = () -> {
    };

    /** False makes every commit rewrite the partitions it touches, never append in place: the tests' reference. */
    @TestOnly
    boolean appendInPlace = true;

    /** Run by a logged commit right before it appends its record to the table's log. */
    @TestOnly
    Runnable beforeWalAppend = () -> {
    };

    /** Run by a logged commit right after its record is forced, before the commit is visible. */
    @TestOnly
    Runnable afterWalForce = () -> {
    };

    /**
     * The applier checkpoints the table once this many commits were applied since the last checkpoint: a recovery
     * replays about 256 commits plus those queued. Not measured; set for the plan of the log.
     */
    @TestOnly
    int checkpointRecords = 256;

    /** The applier checkpoints the table once its log holds this many bytes: 16 MiB, as {@link #checkpointRecords}. */
    @TestOnly
    long checkpointBytes = 16L << 20;

    /**
     * A commit of at least this many rows is written as a checkpoint, not logged: as {@code CommitQueue}'s largest
     * group, so a bulk statement is not written twice nor held twice in memory. 0 checkpoints every commit, as before
     * the log.
     */
    @TestOnly
    int directRows = 100_000;

    /**
     * The heap estimate of the commits queued in every table past which a commit waits for the applier: 64 MiB, about
     * a quarter of the 256 MiB heap cdb's server runs in. Not measured; set for the plan of the applier.
     */
    @TestOnly
    long queuedBudgetBytes = 64L << 20;

    /**
     * True makes a logged commit apply its own record, and checkpoint when the log holds enough, on its thread before
     * it returns, as before the applier: the reference of the tests that check files right after a commit.
     */
    // written by a test before it commits, read by the committing threads
    @TestOnly
    volatile boolean applyOnCommit;

    /**
     * The applier checkpoints a table whose log holds anything this long after its last checkpoint: 30 s, so a quiet
     * table's recovery replays little. Not measured; set for the plan of the applier.
     */
    @TestOnly
    long checkpointIdleNanos = TimeUnit.SECONDS.toNanos(30L);

    /**
     * The longest a read waits for the applier to publish the commits answered before it, and a commit over the queued
     * budget for it to catch up: 10 s, as the store's wait for an open writer.
     */
    // written by a test before it reads, read by readers and by commits over the queued budget
    @TestOnly
    volatile long readWaitNanos = TimeUnit.SECONDS.toNanos(10L);

    /** True holds the applier thread back: commits queue and stay queued, the tests' way to build one batch. */
    // written by a test, read by the applier thread
    @TestOnly
    volatile boolean applierPaused;

    /**
     * The most UTF-8 text one VARCHAR column of one partition takes, as a commit checks before it is answered: the
     * apply refuses a partition past 2 GB, an {@code int} offset. Lowered by tests only.
     */
    // written by a test before it commits, read by the committing threads
    @TestOnly
    volatile long textLimitBytes = Integer.MAX_VALUE;

    /**
     * How long close waits for each table's open writer, and for an apply holding its apply lock:
     * {@code CLOSE_WAIT_MILLIS}, lowered by tests only.
     */
    @TestOnly
    long closeWaitMillis = CLOSE_WAIT_MILLIS;

    /**
     * The most a read may lag the answered commits, in milliseconds, for the reads of the scope it is bound in: 0, a
     * read sees every commit answered before it began; above 0, a read waits for the applier only when a commit older
     * than that is not yet applied. Unbound, the store's {@link #readMaxLagMillis(long)} holds.
     */
    public static final ScopedValue<Long> READ_MAX_LAG_MILLIS = ScopedValue.newInstance();

    /**
     * The definitions a statement was planned under, bound for the statement's run: a snapshot or a writer of a table
     * it planned whose columns no longer begin with the planned ones (a column dropped or renamed, the table made anew
     * with other columns) is refused with {@link DefinitionChanged}, so a plan never reads or writes columns by the
     * indexes of a definition it was not planned under; the statement is planned again. A column added at the end, as
     * the line protocol adds one, keeps the planned indexes and is not refused.
     */
    public static final ScopedValue<PlannedDefs> PLANNED_UNDER = ScopedValue.newInstance();

    /**
     * The most tables a store holds, created by SQL or by the line protocol: 1,000. Not measured; set with the server's
     * limits so a client cannot fill the root with tables, each a log channel and its own folders.
     */
    public static final int MAX_TABLES = 1_000;

    /** The most columns a table has: 1,024, as {@link #MAX_TABLES}, so a client cannot grow one row without end. */
    public static final int MAX_COLUMNS = 1_024;

    /** The longest table or column name, in characters: 127, as QuestDB's default limit on a name's length. */
    public static final int MAX_NAME_LENGTH = 127;

    private final Path root;
    private final StoreOptions options;
    private final Map<String, TableState> tables = new ConcurrentHashMap<>();
    // written by the launcher before it serves, read by every reader
    private volatile long readMaxLagMillis;
    private final Object createLock = new Object();
    // the folders of dropped tables not yet deleted: added under createLock at the drop, removed once the delete ends
    // (a failed one leaves the drop marker, which freeFolder also skips); read by freeFolder under createLock
    private final Set<Path> dying = ConcurrentHashMap.newKeySet();
    // serialises close: a close that kept the lock is finished by a later one
    private final Object closeLock = new Object();
    // the tables a close left unclosed, guarded by closeLock: a writer open past the wait, or an apply holding the
    // table's apply lock past it; the root's lock is kept while any is
    private final List<Unclosed> unclosedAtClose = new ArrayList<>();
    // guarded by closeLock: a close got past the log forcer's end and ran its pass over the tables
    private boolean tablesClosed;
    // written by the closing thread, read by every caller and the applier's sweep: once set, no table opens a reader
    // or writer, and a sweep in progress stops
    private volatile boolean closed;
    // the mappings of every table's versions, counted against the process's limit
    private final Mappings mappings;
    // the channel holding the root's exclusive lock: one store per root, across processes; null until open has it
    private FileChannel lock;
    // started once every table is loaded; null before
    private Applier applier;
    // started after the applier when options are timed; null before, and for a store that forces each commit
    private LogForcer forcer;

    private Store(final Path root, final StoreOptions options) {
        this.root = root;
        this.options = options;
        this.mappings = new Mappings(MAX_MAP_COUNT, root.toString());
    }

    /** Opens the store at {@code root}, creating the folder when it is missing, forcing each commit. */
    public static Store open(final Path root) {
        return open(root, StoreOptions.DEFAULT);
    }

    /**
     * Opens the store at {@code root} as {@link #open(Path)} does, its commits made durable as {@code options} says:
     * timed, a {@link LogForcer} thread forces each table's log within the interval, started after the applier.
     */
    public static Store open(final Path root, final StoreOptions options) {
        final Store store = new Store(root, options);
        try {
            Files.createDirectories(root);
            store.lock = StoreFiles.lockExclusively(root.resolve(LOCK));
            if (store.lock == null) {
                throw new CdbException("refuse to open store another store holds [root=" + root + ']', -1);
            }
            try (final DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (final Path dir : dirs) {
                    if (Files.exists(dir.resolve(TableFiles.DROPPED))) {
                        // a drop answered before the stop, its folder not yet deleted: a reader held it, or a crash
                        LOG.log(System.Logger.Level.INFO, "deleted dropped table [dir=" + dir + ']');
                        StoreFiles.deleteTable(dir, TableFiles.DROPPED);
                    } else if (Files.exists(dir.resolve(TableFiles.META))) {
                        final TableState state = load(store, dir);
                        store.tables.put(state.key, state);
                    }
                }
            }
            final String name = root.getFileName() == null ? "store" : root.getFileName().toString();
            store.applier = new Applier(store, name);
            store.applier.start();
            if (options.isTimed()) {
                store.forcer = new LogForcer(name, options.logForceMillis());
                store.forcer.start();
            }
            return store;
        } catch (final IOException | RuntimeException e) {
            store.close();
            if (e instanceof final CdbException cdb) {
                throw cdb;
            }
            throw new CdbException("cannot open store [root=" + root + ']', e);
        }
    }

    /** The folder this store keeps its tables in, as {@link #open(Path)} was given it. */
    public Path root() {
        return root;
    }

    /**
     * Forces {@code folder}'s entries to the device, so a rename into it survives a power cut once this returns: how a
     * backup or a restore makes its copy's rename into place durable.
     */
    public static void forceFolder(final Path folder) throws IOException {
        StoreFiles.forceFolder(folder);
    }

    /** A table lookup to plan a statement through, recording each definition it answers for {@link #PLANNED_UNDER}. */
    public PlannedDefs plannedDefs() {
        return new PlannedDefs(this, new ConcurrentHashMap<>());
    }

    // true when a statement planned table under a definition whose columns current's do not begin with
    private boolean planStale(final String table, final TableDef current) {
        if (!PLANNED_UNDER.isBound()) {
            return false;
        }
        final PlannedDefs planned = PLANNED_UNDER.get();
        if (planned.store() != this) {
            return false;
        }
        final TableDef def = planned.defs().get(TableState.key(table));
        if (def == null) {
            return false;
        }
        final List<ColumnDef> columns = def.columns();
        return current.columns().size() < columns.size()
                || !current.columns().subList(0, columns.size()).equals(columns)
                || current.timestampIndex() != def.timestampIndex();
    }

    /** The tables' names, sorted. */
    public List<String> tables() {
        checkOpen();
        final List<String> names = new ArrayList<>(tables.size());
        for (final TableState state : tables.values()) {
            final TableState.Snapshot snapshot = state.current();
            if (snapshot == null) {
                // a close running past checkOpen
                throw closedException();
            }
            names.add(snapshot.def().name());
        }
        names.sort(null);
        return names;
    }

    /** The table's committed definition; null when there is no such table (names match without regard to case). */
    public TableDef def(final String table) {
        checkOpen();
        final TableState state = tables.get(TableState.key(table));
        if (state == null) {
            return null;
        }
        final TableState.Snapshot snapshot = state.current();
        return snapshot == null ? null : snapshot.def();
    }

    /** Creates {@code def}; false when a table of that name exists, which is an error unless {@code ifNotExists}. */
    public boolean create(final TableDef def, final boolean ifNotExists) {
        checkOpen();
        check(def);
        synchronized (createLock) {
            checkOpen();
            if (tables.containsKey(TableState.key(def.name()))) {
                if (ifNotExists) {
                    return false;
                }
                throw new CdbException("refuse to create existing table [table=" + def.name() + ']', -1);
            }
            if (tables.size() >= MAX_TABLES) {
                throw new CdbException("cannot create table: too many tables [table=" + def.name() + ", limit="
                        + MAX_TABLES + ']', -1);
            }
            final Path dir = freeFolder(def.name());
            Wal wal = null;
            try {
                StoreFiles.createFolder(dir);
                // a folder force that fails after a rename leaves the create in doubt: a power cut may keep _meta
                // without _txn, or lose both, so the create is refused rather than answered
                if (!StoreFiles.replaceText(dir.resolve(TableFiles.TXN), TableFiles.encodeTxn(0L, List.of()))) {
                    throw new IOException("cannot force table folder after writing _txn [dir=" + dir + ']');
                }
                // _meta last: open takes a folder with _meta for a table
                if (!StoreFiles.replaceText(dir.resolve(TableFiles.META), TableFiles.encodeMeta(def))) {
                    throw new IOException("cannot force table folder after writing _meta [dir=" + dir + ']');
                }
                wal = Wal.open(dir);
                // the table folder's entry: without it a power cut loses the table
                StoreFiles.forceFolder(root);
            } catch (final IOException e) {
                if (wal != null) {
                    wal.close();
                }
                final CdbException refused = new CdbException("cannot create table [table=" + def.name() + ", dir="
                        + dir + ']', e);
                removeFolder(dir, refused);
                throw refused;
            }
            final TableState state = new TableState(dir, def, new long[0], wal, mappings);
            state.init(new TableState.Snapshot(0L, def, List.of()));
            tables.put(state.key, state);
            LOG.log(System.Logger.Level.DEBUG, "created table [table=" + def.name() + ", dir=" + dir + ']');
            return true;
        }
    }

    /**
     * Drops the table, waiting up to {@code waitMillis} for its writer as {@link #writer} does; false when there is no
     * such table. Once it returns, {@link #def} and {@link #reader} no longer find the table and {@link #create} makes
     * a new one, while a reader that already held a snapshot reads it until it closes. The table's commits are gone,
     * the queued ones included; its folder is deleted when the last such reader closes, or at the next open. The drop
     * holds once its marker is in the folder: a crash before leaves the table whole, one after leaves it gone.
     */
    public boolean dropTable(final String table, final long waitMillis) {
        checkOpen();
        final TableState state = tables.get(TableState.key(table));
        if (state == null || !state.leaseUnlessClosed(waitMillis)) {
            return false;
        }
        synchronized (createLock) {
            state.applyLock.lock();
            try {
                if (!StoreFiles.replaceText(state.dir.resolve(TableFiles.DROPPED), "")) {
                    // the drop is in doubt: a power cut may bring the table back, a later open deletes it otherwise
                    LOG.log(System.Logger.Level.WARNING, "dropped table, a folder force left the drop in doubt [table="
                            + table + ", dir=" + state.dir + ']');
                }
                tables.remove(state.key);
                dying.add(state.dir);
                applier.forget(state);
                if (forcer != null) {
                    forcer.forget(state);
                }
            } catch (final IOException | RuntimeException e) {
                state.applyLock.unlock();
                state.unlease();
                throw new CdbException("cannot drop table [table=" + table + ", dir=" + state.dir + ']', e);
            }
            state.applyLock.unlock();
        }
        state.drop(() -> dying.remove(state.dir));
        LOG.log(System.Logger.Level.INFO, "dropped table [table=" + table + ", dir=" + state.dir + ']');
        return true;
    }

    /**
     * The table's committed partitions, in time order, as of the commits answered before the call (as {@link #reader}
     * waits for them): the last is the newest, the one that takes writes.
     */
    public List<PartitionInfo> partitions(final String table) {
        checkOpen();
        final TableState state = state(table);
        awaitFresh(state, readLagMillis());
        final TableState.Snapshot snapshot = state.acquire();
        try {
            final PartitionBy partitionBy = snapshot.def().partitionBy();
            final List<Version> versions = snapshot.partitions();
            final List<PartitionInfo> out = new ArrayList<>(versions.size());
            for (int i = 0; i < versions.size(); i++) {
                final Version v = versions.get(i);
                out.add(new PartitionInfo(TableFiles.partitionName(v.lowerBound(), partitionBy), v.minTimestamp(),
                        v.maxTimestamp(), v.rowCount(), v.mappedBytes(), i == versions.size() - 1));
            }
            return out;
        } finally {
            state.release(snapshot);
        }
    }

    /**
     * A snapshot of the table's committed state: waits for the applier to publish the commits answered before the call
     * when one of them is older than the read's lag ({@link #READ_MAX_LAG_MILLIS}), all of them when the lag is 0.
     */
    public TableReader reader(final String table) {
        checkOpen();
        final TableState state = state(table);
        awaitFresh(state, readLagMillis());
        final DefaultReader reader = new DefaultReader(state, state.acquire());
        if (planStale(table, reader.def())) {
            reader.close();
            throw new DefinitionChanged(table);
        }
        return reader;
    }

    /**
     * As {@link #reader}, with every answered commit applied whatever read lag is bound, the queued ones by the calling
     * thread, and not checked against {@link #PLANNED_UNDER}: the snapshot a rewrite reads under the table's writer, so
     * that every commit its truncate removes is in it and no definition can change between its plan's writer and it;
     * or a backup's, which reads each table's own definition.
     */
    public TableReader freshReader(final String table) {
        checkOpen();
        final TableState state = state(table);
        state.applyLock.lock();
        try {
            // dropped since it was looked up: its folder takes no more writes
            if (state.forgotten()) {
                throw new CdbException("refuse unknown table [table=" + table + ']', -1);
            }
            applier.applyAllHeld(state, false);
        } finally {
            state.applyLock.unlock();
        }
        return new DefaultReader(state, state.acquire());
    }

    /**
     * The table's writer, waiting up to {@code waitMillis} for another to be closed; a table that is absent, or that a
     * drop removes before its writer is taken, is refused as one that cannot be found.
     */
    public TableWriter writer(final String table, final long waitMillis) {
        final TableWriter writer = writerUnlessDropped(table, waitMillis);
        if (writer == null) {
            // a drop or a close of the store let the lease go: a closed store says so first
            checkOpen();
            throw new CdbException("cannot find table [table=" + table + ']', -1);
        }
        return writer;
    }

    /** As {@link #writer}, null, without a writer, when the table is absent or a drop removes it first. */
    TableWriter writerUnlessDropped(final String table, final long waitMillis) {
        checkOpen();
        final TableState state = tables.get(TableState.key(table));
        if (state == null || !state.leaseUnlessClosed(waitMillis)) {
            return null;
        }
        final DefaultWriter writer;
        try {
            writer = new DefaultWriter(this, state);
        } catch (final RuntimeException e) {
            state.unlease();
            throw e;
        }
        if (planStale(table, writer.def())) {
            writer.close();
            throw new DefinitionChanged(table);
        }
        return writer;
    }

    /**
     * Sets the most a read may lag the answered commits when no {@link #READ_MAX_LAG_MILLIS} is bound; 0, the default,
     * keeps every read up to date with every answered commit.
     */
    public void readMaxLagMillis(final long millis) {
        checkOpen();
        if (millis < 0L) {
            throw new CdbException("refuse negative read lag [millis=" + millis + ']', -1);
        }
        readMaxLagMillis = millis;
    }

    /** Each table's log and its application, by table name. */
    public List<WalStatus> walStatus() {
        checkOpen();
        final List<WalStatus> out = new ArrayList<>(tables.size());
        for (final TableState state : tables.values()) {
            final TableState.Snapshot snapshot = state.current();
            if (snapshot != null) {
                out.add(state.status(snapshot.def().name(), System.nanoTime(), applier.memoryPressure()));
            }
        }
        out.sort(Comparator.comparing(WalStatus::table));
        return out;
    }

    // the read lag in force: the bound one, else the store's
    private long readLagMillis() {
        return READ_MAX_LAG_MILLIS.isBound() ? READ_MAX_LAG_MILLIS.get() : readMaxLagMillis;
    }

    // the read's wait for the queued commits its lag does not allow
    private void awaitFresh(final TableState state, final long lagMillis) {
        final long logged = state.loggedTxn();
        if (state.appliedTxn() >= logged) {
            return;
        }
        if (lagMillis > 0L) {
            final long nowNanos = System.nanoTime();
            final long oldestNanos = state.oldestQueuedNanos(nowNanos);
            if (nowNanos - oldestNanos <= TimeUnit.MILLISECONDS.toNanos(lagMillis)) {
                return;
            }
        }
        // the demand is counted before the wake, so the woken applier sees a reader waiting
        state.demand();
        applier.wake();
        if (!state.awaitApplied(logged, readWaitNanos)) {
            throw Applier.notApplied(state, logged);
        }
    }

    // applyOnCommit's apply, on the committing thread
    void applyNow(final TableState state) {
        state.applyLock.lock();
        try {
            applier.applyAllHeld(state, false);
            applier.checkpointIfDue(state);
        } finally {
            state.applyLock.unlock();
        }
    }

    /** The tables' states, for the applier. */
    Iterable<TableState> states() {
        return tables.values();
    }

    /** The timed force, null for a store that forces each commit. */
    LogForcer forcer() {
        return forcer;
    }

    /** How the store makes its commits durable. */
    @TestOnly
    StoreOptions options() {
        return options;
    }

    /** The store's applier. */
    Applier applier() {
        return applier;
    }

    /** A line-protocol appender over this store (tables and columns created on first write). */
    public CdbAppender appender() {
        checkOpen();
        return new IlpAppender(this);
    }

    /**
     * Checkpoints {@code table}: forces what its commits since the last checkpoint wrote, replaces {@code _txn} and
     * empties its log. Waits for the table's writer like {@link #writer}.
     */
    @TestOnly
    void checkpoint(final String table) {
        checkOpen();
        final TableState state = state(table);
        state.lease(CHECKPOINT_WAIT_MILLIS);
        try {
            if (state.poisoned()) {
                throw new CdbException("refuse checkpoint of failed table [table=" + table + ']', -1);
            }
            state.applyLock.lock();
            try {
                applier.applyAllHeld(state, false);
                if (!Checkpoint.run(this, state, state.current())) {
                    throw new CdbException("cannot force folder after checkpoint, log kept [table=" + table + ']', -1);
                }
            } finally {
                state.applyLock.unlock();
            }
        } catch (final IOException e) {
            throw new CdbException("cannot checkpoint table [table=" + table + ']', e);
        } finally {
            state.unlease();
        }
    }

    /**
     * Waits for open writers to close, then releases every file; idempotent. A writer still open past the wait may
     * still replace its table's {@code _txn}, an apply still holding a table's apply lock past it may still write its
     * files, and a log force still running past it may still use a table's log: the close then fails naming what it
     * waited for and keeps the root's lock, so no store opens the root under them. A later close waits for them again
     * and, once they ended, closes what is left and releases the lock.
     */
    @Override
    public void close() {
        synchronized (closeLock) {
            if (!closed) {
                // under the create lock: a create either published its table before this, so the loop below closes
                // it, or sees the store closed and refuses, never adding an open log after the root lock is released
                synchronized (createLock) {
                    closed = true;
                }
                if (applier != null) {
                    applier.stop();
                }
            }
            // a last force of every dirty log; a commit of a writer still open is forced on its own thread. A force
            // still running past the wait may use any table's log, so no table is closed under it
            if (forcer != null && !forcer.stop()) {
                throw new CdbException("cannot close store, log force still running, root lock kept [root=" + root
                        + ", waitMillis=" + forcer.joinMillis + ']', -1);
            }
            // every table is tried, a failing one recorded as unclosed: a later close then finds each table it must
            // still close, and never releases the lock over an open log
            final List<CloseFailure> failures = new ArrayList<>();
            if (!tablesClosed) {
                tablesClosed = true;
                for (final TableState state : tables.values()) {
                    closeAtFirst(state, failures);
                }
            } else {
                for (final ListIterator<Unclosed> it = unclosedAtClose.listIterator(); it.hasNext(); ) {
                    final Unclosed table = it.next();
                    try {
                        if (finishClose(table)) {
                            it.remove();
                        }
                    } catch (final Throwable t) {
                        it.set(new Unclosed(table.state(), table.leased(), true));
                        failures.add(closeFailed(table.state(), t));
                    }
                }
            }
            if (!unclosedAtClose.isEmpty()) {
                throw unclosedFailure(failures);
            }
            final FileChannel held = lock;
            lock = null;
            if (held != null) {
                try {
                    held.close();
                } catch (final IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot release store lock [root=" + root + ']', e);
                }
            }
        }
    }

    /**
     * Releases, in every open table, the mappings of the versions no read used for {@code MAP_IDLE_NANOS} before
     * {@code nowNanos} (a {@link System#nanoTime()} reading): the applier's sweep every {@code MAP_SWEEP_NANOS}, which
     * a test drives with its own clock. A table that fails is logged and the others swept.
     */
    void sweepIdle(final long nowNanos) {
        for (final TableState state : tables.values()) {
            if (closed) {
                return;
            }
            try {
                state.releaseIdle(nowNanos, MAP_IDLE_NANOS);
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot release idle mappings [root=" + root + ", table="
                        + state.key + ']', e);
            }
        }
    }

    /** The mappings of every table's versions. */
    @TestOnly
    Mappings mappings() {
        return mappings;
    }

    // closes a table at the store's close, after its checkpoint when the close holds its lease and no earlier close
    // of it threw (an Error may have left its files half-written: the next open replays its log instead); false when
    // an apply held the table's apply lock past the wait: the table is left open, and a later close tries again
    private boolean closeTable(final Unclosed table) {
        final TableState state = table.state();
        return (!table.leased() || table.threw() || checkpointOnClose(state))
                && state.close(table.leased(), closeWaitMillis);
    }

    // a later close's pass over a table the first left: closed once its apply let the lock go, its log closed once
    // its writer did
    private boolean finishClose(final Unclosed table) {
        final TableState state = table.state();
        if (!state.closed() && !closeTable(table)) {
            return false;
        }
        if (!table.leased() && !state.leaseForClose(closeWaitMillis)) {
            return false;
        }
        state.wal.close();
        return true;
    }

    // the first close's pass over a table: closed, or recorded as unclosed for a later close, also when it throws
    private void closeAtFirst(final TableState state, final List<CloseFailure> failures) {
        boolean leased = false;
        try {
            leased = state.leaseForClose(closeWaitMillis);
            final Unclosed table = new Unclosed(state, leased, false);
            if (closeTable(table) && leased) {
                state.wal.close();
            } else {
                unclosedAtClose.add(table);
            }
        } catch (final Throwable t) {
            unclosedAtClose.add(new Unclosed(state, leased, true));
            failures.add(closeFailed(state, t));
        }
    }

    private CloseFailure closeFailed(final TableState state, final Throwable error) {
        LOG.log(System.Logger.Level.WARNING, "cannot close table, kept for the next close [root=" + root + ", table="
                + state.key + ']', error);
        return new CloseFailure(state.key, error);
    }

    // the close's refusal, naming each cause and its tables: a writer it holds no lease from, an apply it could not
    // close the table under, a table whose close threw. The throws are suppressed in it; an Error among them is
    // thrown as itself, the refusal suppressed in it, so the caller still sees an Error
    private RuntimeException unclosedFailure(final List<CloseFailure> failures) {
        final List<String> failed = new ArrayList<>(failures.size());
        for (final CloseFailure failure : failures) {
            failed.add(failure.table());
        }
        final List<String> tables = new ArrayList<>(unclosedAtClose.size());
        final List<String> applying = new ArrayList<>();
        boolean writerOpen = false;
        for (final Unclosed table : unclosedAtClose) {
            tables.add(table.state().key);
            writerOpen |= !table.leased();
            if (!table.state().closed() && !failed.contains(table.state().key)) {
                applying.add(table.state().key);
            }
        }
        final List<String> causes = new ArrayList<>(3);
        if (writerOpen) {
            causes.add("writer still open");
        }
        if (!applying.isEmpty()) {
            causes.add("apply still running");
        }
        if (!failed.isEmpty()) {
            causes.add("table close failed");
        }
        final CdbException refused = new CdbException("cannot close store, " + String.join(", ", causes)
                + ", root lock kept [root=" + root + ", tables=" + tables
                + (applying.isEmpty() ? "" : ", applying=" + applying) + (failed.isEmpty() ? "" : ", failed=" + failed)
                + ", waitMillis=" + closeWaitMillis + ']', -1);
        Error error = null;
        for (final CloseFailure failure : failures) {
            if (error == null && failure.error() instanceof final Error e) {
                error = e;
            } else {
                refused.addSuppressed(failure.error());
            }
        }
        if (error != null) {
            error.addSuppressed(refused);
            throw error;
        }
        return refused;
    }

    // the closing thread holds the lease: a clean shutdown applies the queued commits and leaves every log empty, else
    // the next open replays it; false when an apply held the table's apply lock past the close's wait
    private boolean checkpointOnClose(final TableState state) {
        if (state.poisoned() || state.suspended() || state.current() == null || state.wal.records() == 0) {
            return true;
        }
        if (!state.tryApplyLock(closeWaitMillis)) {
            return false;
        }
        try {
            applier.applyAllHeld(state, false);
            final TableState.Snapshot snapshot = state.current();
            if (!Checkpoint.run(this, state, snapshot)) {
                LOG.log(System.Logger.Level.WARNING, "cannot force folder after checkpoint, log kept [table="
                        + state.key + ", txn=" + snapshot.txn() + ']');
            }
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot checkpoint table on close, log kept [table=" + state.key
                    + ", txn=" + state.loggedTxn() + ']', e);
        } finally {
            state.applyLock.unlock();
        }
        return true;
    }

    // a refused create's folder, best effort: once gone, and its absence forced, no open takes it for a table; a
    // failure is added to the refusal, and an open that finds the folder with _meta takes it as an empty table
    private void removeFolder(final Path dir, final CdbException refused) {
        try {
            StoreFiles.deleteFolder(dir);
            StoreFiles.forceFolder(root);
        } catch (final IOException e) {
            refused.addSuppressed(e);
        }
    }

    private TableState state(final String table) {
        final TableState state = tables.get(TableState.key(table));
        if (state == null) {
            throw new CdbException("refuse unknown table [table=" + table + ']', -1);
        }
        return state;
    }

    private void checkOpen() {
        if (closed) {
            throw closedException();
        }
    }

    private CdbException closedException() {
        return new CdbException("refuse use of closed store [root=" + root + ']', -1);
    }

    // the folder of a new table: its name, unless a dropped table's folder is there, still being deleted (its last
    // reader deletes the marker, then the folder, outside the create lock) or kept by a failed delete with its marker;
    // then its name and the first free ~n, since an open takes the name from _meta. A stray folder a cut create left
    // without _meta is taken as before
    private Path freeFolder(final String name) {
        Path dir = root.resolve(name);
        for (int n = 1; dying.contains(dir) || Files.exists(dir.resolve(TableFiles.DROPPED)); n++) {
            dir = root.resolve(name + '~' + n);
        }
        return dir;
    }

    private static void check(final TableDef def) {
        TableFiles.checkName(def.name(), "table");
        if (def.columns().isEmpty()) {
            throw new CdbException("refuse table without columns [table=" + def.name() + ']', -1);
        }
        if (def.name().length() > MAX_NAME_LENGTH) {
            throw new CdbException("cannot create table: name too long [table=" + def.name() + ", length="
                    + def.name().length() + ", limit=" + MAX_NAME_LENGTH + ']', -1);
        }
        if (def.columns().size() > MAX_COLUMNS) {
            throw new CdbException("cannot create table: too many columns [table=" + def.name() + ", columns="
                    + def.columns().size() + ", limit=" + MAX_COLUMNS + ']', -1);
        }
        final Set<String> names = new HashSet<>();
        for (final ColumnDef column : def.columns()) {
            TableFiles.checkName(column.name(), "column");
            if (column.name().length() > MAX_NAME_LENGTH) {
                throw new CdbException("cannot create table: column name too long [table=" + def.name() + ", column="
                        + column.name() + ", length=" + column.name().length() + ", limit=" + MAX_NAME_LENGTH + ']',
                        -1);
            }
            if (!names.add(TableState.key(column.name()))) {
                throw new CdbException("refuse duplicate column [table=" + def.name() + ", column=" + column.name()
                        + ']', -1);
            }
        }
        final int ts = def.timestampIndex();
        if (ts >= def.columns().size() || ts >= 0 && def.columns().get(ts).type() != CdbType.TIMESTAMP) {
            throw new CdbException("refuse designated timestamp that is not a TIMESTAMP column [table=" + def.name()
                    + ", index=" + ts + ']', -1);
        }
        if (ts < 0 && def.partitionBy() != PartitionBy.NONE) {
            throw new CdbException("refuse partitioning without designated timestamp [table=" + def.name()
                    + ", partitionBy=" + def.partitionBy() + ']', -1);
        }
        boolean timestampKey = false;
        for (final String key : def.dedupKeys()) {
            final int c = def.columnIndex(key);
            if (c < 0) {
                throw new CdbException("refuse unknown dedup key [table=" + def.name() + ", column=" + key + ']', -1);
            }
            timestampKey |= c == ts;
        }
        if (!def.dedupKeys().isEmpty() && !timestampKey) {
            throw new CdbException("refuse dedup keys without the designated timestamp [table=" + def.name() + ']', -1);
        }
        if (def.ttlMicros() < 0L) {
            throw new CdbException("refuse negative TTL [table=" + def.name() + ", ttlMicros=" + def.ttlMicros() + ']',
                    -1);
        }
    }

    /**
     * Reads a table folder: {@code _meta} (trimmed to the definition {@code _txn} names), {@code _txn} (none:
     * transaction 0), the versions it names mapped; deletes the folders and temporary files a crashed commit left that
     * {@code _txn} does not name and the symbol files of no SYMBOL column of the definition it names, cuts the bytes
     * appended past the committed ends of the folders it does, then replays the log and checkpoints what it replayed.
     * A {@code _meta.prev} whose column change {@code _txn} does not reach is moved back over {@code _meta} first.
     */
    private static TableState load(final Store store, final Path dir) throws IOException {
        final Path meta = dir.resolve(TableFiles.META);
        final Path txnFile = dir.resolve(TableFiles.TXN);
        final TableFiles.Txn txn = Files.exists(txnFile)
                ? TableFiles.decodeTxn(StoreFiles.readText(txnFile), txnFile.toString())
                : new TableFiles.Txn(0L, List.of());
        final TableDef stored = TableFiles.decodeMeta(storedMeta(dir, txn), meta.toString());
        final TableDef def = trim(stored, txn, txnFile);
        final Set<String> named = new HashSet<>();
        for (final TableFiles.Entry entry : txn.partitions()) {
            named.add(entry.folder());
        }
        // the dictionaries of the definition's SYMBOL columns, in lower case: on a file system that ignores case a
        // file keeps the name it was created with, which a column may have had in another case before
        final Set<String> symbolFiles = new HashSet<>();
        for (final ColumnDef column : def.columns()) {
            if (column.type() == CdbType.SYMBOL) {
                symbolFiles.add(TableState.key(column.name() + ".sym"));
            }
        }
        try (final DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && !named.contains(name)) {
                    LOG.log(System.Logger.Level.INFO, "deleted partition of an unfinished commit [dir=" + entry + ']');
                    StoreFiles.deleteFolder(entry);
                } else if (name.endsWith(".tmp")
                        || name.endsWith(".sym") && !symbolFiles.contains(TableState.key(name))) {
                    // a column a checkpoint did not reach (its _meta written, its _txn not, its log record the
                    // replay's), or one a column change dropped or renamed away
                    StoreFiles.delete(entry);
                }
            }
        }
        // the _txn read here may be a rename a kill left unforced: forced before the log's records it covers are cut
        StoreFiles.forceFolder(dir);
        final Wal wal = Wal.open(dir);
        final TableState state = new TableState(dir, def, txn.symbolBytes(), wal, store.mappings);
        final List<Version> versions = new ArrayList<>(txn.partitions().size());
        try {
            for (final TableFiles.Entry entry : txn.partitions()) {
                final Path partition = dir.resolve(entry.folder());
                TableFiles.checkName(entry.folder(), "partition");
                cutToCommitted(partition, def, entry.rows());
                versions.add(Version.open(partition, def, entry.rows(), state.symbols(), entry.columns(),
                        state.mappings));
            }
        } catch (final RuntimeException e) {
            for (final Version v : versions) {
                v.unmap();
            }
            wal.close();
            throw e;
        }
        state.init(new TableState.Snapshot(txn.number(), def, versions));
        try {
            replay(store, state, wal.scan(txn.number(), def));
        } catch (final IOException | RuntimeException e) {
            state.close(false);
            wal.close();
            throw e;
        }
        return state;
    }

    /**
     * The text of {@code dir}'s definition as {@code txn} names it. A column change writes {@code _meta.prev}, then
     * {@code _meta}, then its {@code _txn}: a {@code _txn} older than the change pairs with the definition before it,
     * which is moved back over {@code _meta} (forced) before {@code _meta.prev} is deleted, so commits replayed or
     * made after this open, whose transactions reach the change's number, never pair with the dropped definition.
     */
    private static String storedMeta(final Path dir, final TableFiles.Txn txn) throws IOException {
        final Path meta = dir.resolve(TableFiles.META);
        final Path prevFile = dir.resolve(TableFiles.META_PREV);
        if (!Files.exists(prevFile)) {
            return StoreFiles.readText(meta);
        }
        final TableFiles.Prev prev = TableFiles.decodePrev(StoreFiles.readText(prevFile), prevFile.toString());
        if (txn.number() < prev.txn()) {
            if (!StoreFiles.replaceText(meta, prev.meta())) {
                throw new IOException("cannot force folder after restoring definition [file=" + meta + ']');
            }
            LOG.log(System.Logger.Level.INFO, "restored definition of an unfinished column change [dir=" + dir
                    + ", txn=" + txn.number() + ", changeTxn=" + prev.txn() + ']');
        }
        // forced with the folder after the cleanup that follows, before anything is replayed
        StoreFiles.delete(prevFile);
        return StoreFiles.readText(meta);
    }

    /** {@code stored} as of the checkpoint {@code txn} names: its first columns and its TTL. */
    private static TableDef trim(final TableDef stored, final TableFiles.Txn txn, final Path txnFile) {
        if (!txn.checkpointed()) {
            return stored;
        }
        if (txn.columns() > stored.columns().size()) {
            throw new CdbException("refuse transaction naming more columns than the definition [file=" + txnFile
                    + ", columns=" + txn.columns() + ", defined=" + stored.columns().size() + ']', -1);
        }
        return new TableDef(stored.name(), stored.columns().subList(0, txn.columns()), stored.timestampIndex(),
                stored.partitionBy(), stored.dedupKeys(), txn.ttlMicros());
    }

    /**
     * Commits the log's {@code entries} again through a writer that neither logs nor checkpoints, in batches as the
     * applier does, each published as the last transaction it holds, then checkpoints the table once: the log is empty
     * when the store opens.
     */
    private static void replay(final Store store, final TableState state, final List<Wal.Entry> entries)
            throws IOException {
        if (entries.isEmpty()) {
            if (state.wal.records() > 0) {
                // records at or before the durable _txn, left by a checkpoint that stopped before its reset: cut, so
                // the next record follows _txn's transaction
                state.wal.reset();
            }
            return;
        }
        final String table = state.current().def().name();
        long last = state.current().txn();
        int batched = 0;
        try {
            try (final DefaultWriter writer = new DefaultWriter(store, state, true)) {
                for (final Wal.Entry entry : entries) {
                    if (entry.txn() != last + 1L) {
                        throw new CdbException("refuse log entry out of order [table=" + table + ", txn="
                                + entry.txn() + ", expected=" + (last + 1L) + ']', -1);
                    }
                    try {
                        // plain commits in a row are applied as one batch, as the applier does; a truncate or a
                        // definition change ends it and is a commit of its own
                        if (entry.truncate() || entry.def() != null) {
                            if (batched > 0) {
                                writer.commitAs(last);
                                batched = 0;
                            }
                            apply(writer, entry);
                            writer.commitAs(entry.txn());
                        } else {
                            writer.addRows(entry.rows());
                            batched += entry.rows().size;
                            if (batched >= Applier.MAX_BATCH_ROWS) {
                                writer.commitAs(entry.txn());
                                batched = 0;
                            }
                        }
                    } catch (final RuntimeException e) {
                        throw new CdbException("cannot replay log [table=" + table + ", txn=" + entry.txn() + ']',
                                e);
                    }
                    last = entry.txn();
                }
                if (batched > 0) {
                    try {
                        writer.commitAs(last);
                    } catch (final RuntimeException e) {
                        throw new CdbException("cannot replay log [table=" + table + ", txn=" + last + ']', e);
                    }
                }
            }
        } finally {
            // the scan's blocks, copied into the writer: freed here, on a failure too
            for (final Wal.Entry entry : entries) {
                if (entry.rows() != null) {
                    entry.rows().close();
                }
            }
        }
        final TableState.Snapshot replayed = state.current();
        if (!Checkpoint.run(store, state, replayed)) {
            // as after a failed checkpoint of a commit: a retry could take a failed force for good and cut the log
            state.poison();
            LOG.log(System.Logger.Level.ERROR, "refuse further commits, a folder force failed in the checkpoint after"
                    + " replay, log kept [table=" + table + ", txn=" + replayed.txn() + ']');
        }
        LOG.log(System.Logger.Level.INFO, "replayed log [table=" + table + ", entries=" + entries.size() + ", txn="
                + replayed.txn() + ']');
    }

    private static void apply(final DefaultWriter writer, final Wal.Entry entry) {
        if (entry.truncate()) {
            writer.truncate();
        }
        final TableDef logged = entry.def();
        if (logged != null) {
            for (int c = writer.def().columns().size(); c < logged.columns().size(); c++) {
                writer.addColumn(logged.columns().get(c));
            }
            if (logged.ttlMicros() != writer.def().ttlMicros()) {
                writer.setTtl(logged.ttlMicros());
            }
        }
        final RowBlock rows = entry.rows();
        final int ts = writer.def().timestampIndex();
        for (int r = 0; r < rows.size; r++) {
            writer.newRow(ts >= 0 ? rows.getLong(ts, r) : Long.MIN_VALUE);
            for (int c = 0; c < rows.types.length; c++) {
                if (c == ts) {
                    continue;
                }
                switch (rows.types[c]) {
                    case LONG, TIMESTAMP -> writer.putLong(c, rows.getLong(c, r));
                    case DOUBLE -> writer.putDouble(c, rows.getDouble(c, r));
                    case BOOLEAN -> writer.putBoolean(c, rows.getBoolean(c, r));
                    case SYMBOL, VARCHAR -> writer.putText(c, rows.textAt(c, r));
                }
            }
            writer.endRow();
        }
    }

    /**
     * Cuts each column file of the partition folder {@code partition} to what its {@code rows} committed rows take
     * (VARCHAR {@code .d}: to the end offset of the last committed row): a commit that failed or crashed before
     * {@code _txn} left its rows past there. Runs before anything maps the files; a file shorter than its rows need
     * is left for the mapping to refuse.
     */
    private static void cutToCommitted(final Path partition, final TableDef def, final long rows) throws IOException {
        for (final ColumnDef column : def.columns()) {
            final Path data = partition.resolve(column.name() + ".d");
            if (!Files.exists(data)) {
                continue;
            }
            if (column.type() == CdbType.VARCHAR) {
                final Path index = partition.resolve(column.name() + ".i");
                if (Files.exists(index) && Files.size(index) >= rows * Long.BYTES) {
                    cut(index, rows * Long.BYTES);
                    cut(data, Version.decodeEnd(StoreFiles.readLong(index, (rows - 1L) * Long.BYTES)));
                }
            } else {
                cut(data, rows * TableFiles.width(column.type()));
            }
        }
    }

    private static void cut(final Path file, final long committedBytes) throws IOException {
        final long bytes = Files.size(file);
        if (bytes > committedBytes) {
            StoreFiles.truncate(file, committedBytes);
            LOG.log(System.Logger.Level.INFO, "cut partition file past its committed rows [file=" + file + ", bytes="
                    + bytes + ", committedBytes=" + committedBytes + ']');
        }
    }

    /** The definitions of {@code store}'s tables a statement was planned under, by table key, as it looked them up. */
    public record PlannedDefs(Store store, Map<String, TableDef> defs) implements Function<String, TableDef> {

        /**
         * The table's definition, as {@link Store#def} answers it, recorded when there is one; a table looked up again
         * answers the definition first recorded, so every part of one plan holds one definition, which the run's
         * check then compares the table's snapshots and writers with.
         */
        @Override
        public TableDef apply(final String table) {
            final TableDef def = store.def(table);
            if (def == null) {
                return null;
            }
            final TableDef first = defs.putIfAbsent(TableState.key(table), def);
            return first == null ? def : first;
        }
    }

    /**
     * A snapshot or a writer refused because its table's columns changed after the statement was planned; the
     * statement is planned again by its runner, which turns a refusal that keeps recurring into a {@link CdbException}.
     */
    public static final class DefinitionChanged extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        DefinitionChanged(final String table) {
            super("refuse a plan made before a definition change [table=" + table + ']', null, false, false);
        }
    }

    // a table the store's close left: leased when the close holds its writer's lease, threw when a close of it threw
    private record Unclosed(TableState state, boolean leased, boolean threw) {
    }

    // a table's close that threw, for the store close's refusal
    private record CloseFailure(String table, Throwable error) {
    }
}
