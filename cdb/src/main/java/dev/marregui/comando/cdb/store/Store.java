package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    // how long close waits for each table's open writer; past it the close fails and keeps the root's lock
    private static final long CLOSE_WAIT_MILLIS = 10_000L;
    // how long a checkpoint waits for the table's writer; as CLOSE_WAIT_MILLIS
    private static final long CHECKPOINT_WAIT_MILLIS = 10_000L;
    // the root's lock file; a store holds it from open to close, so two never write one root
    private static final String LOCK = "_lock";

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

    /** How long close waits for each table's open writer: {@code CLOSE_WAIT_MILLIS}, lowered by tests only. */
    @TestOnly
    long closeWaitMillis = CLOSE_WAIT_MILLIS;

    /**
     * The most a read may lag the answered commits, in milliseconds, for the reads of the scope it is bound in: 0, a
     * read sees every commit answered before it began; above 0, a read waits for the applier only when a commit older
     * than that is not yet applied. Unbound, the store's {@link #readMaxLagMillis(long)} holds.
     */
    public static final ScopedValue<Long> READ_MAX_LAG_MILLIS = ScopedValue.newInstance();

    private final Path root;
    private final Map<String, TableState> tables = new ConcurrentHashMap<>();
    // written by the launcher before it serves, read by every reader
    private volatile long readMaxLagMillis;
    private final Object createLock = new Object();
    // serialises close: a close that kept the lock is finished by a later one
    private final Object closeLock = new Object();
    // the tables whose writer a close left open, guarded by closeLock: the root's lock is kept while any is
    private final List<TableState> writerOpenAtClose = new ArrayList<>();
    // written by the closing thread, read by every caller: once set, no table opens a reader or writer
    private volatile boolean closed;
    // the channel holding the root's exclusive lock: one store per root, across processes; null until open has it
    private FileChannel lock;
    // started once every table is loaded; null before
    private Applier applier;

    private Store(final Path root) {
        this.root = root;
    }

    /** Opens the store at {@code root}, creating the folder when it is missing. */
    public static Store open(final Path root) {
        final Store store = new Store(root);
        try {
            Files.createDirectories(root);
            store.lock = StoreFiles.lockExclusively(root.resolve(LOCK));
            if (store.lock == null) {
                throw new CdbException("refuse to open store another store holds [root=" + root + ']', -1);
            }
            try (final DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (final Path dir : dirs) {
                    if (Files.exists(dir.resolve(TableFiles.META))) {
                        final TableState state = load(store, dir);
                        store.tables.put(state.key, state);
                    }
                }
            }
            store.applier = new Applier(store, root.getFileName() == null ? "store" : root.getFileName().toString());
            store.applier.start();
            return store;
        } catch (final IOException | RuntimeException e) {
            store.close();
            if (e instanceof final CdbException cdb) {
                throw cdb;
            }
            throw new CdbException("cannot open store [root=" + root + ']', e);
        }
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
            if (tables.containsKey(TableState.key(def.name()))) {
                if (ifNotExists) {
                    return false;
                }
                throw new CdbException("refuse to create existing table [table=" + def.name() + ']', -1);
            }
            final Path dir = root.resolve(def.name());
            final Wal wal;
            try {
                StoreFiles.createFolder(dir);
                StoreFiles.replaceText(dir.resolve(TableFiles.TXN), TableFiles.encodeTxn(0L, List.of()));
                // _meta last: open takes a folder with _meta for a table
                StoreFiles.replaceText(dir.resolve(TableFiles.META), TableFiles.encodeMeta(def));
                wal = Wal.open(dir);
                // the table folder's entry: without it a power cut loses the table
                StoreFiles.forceFolder(root);
            } catch (final IOException e) {
                throw new CdbException("cannot create table [table=" + def.name() + ", dir=" + dir + ']', e);
            }
            final TableState state = new TableState(dir, def, new long[0], wal);
            state.init(new TableState.Snapshot(0L, def, List.of()));
            tables.put(state.key, state);
            LOG.log(System.Logger.Level.DEBUG, "created table [table=" + def.name() + ", dir=" + dir + ']');
            return true;
        }
    }

    /**
     * A snapshot of the table's committed state: waits for the applier to publish the commits answered before the call
     * when one of them is older than the read's lag ({@link #READ_MAX_LAG_MILLIS}), all of them when the lag is 0.
     */
    public TableReader reader(final String table) {
        checkOpen();
        final TableState state = state(table);
        awaitFresh(state);
        return new DefaultReader(state, state.acquire());
    }

    /** The table's writer, waiting up to {@code waitMillis} for another to be closed. */
    public TableWriter writer(final String table, final long waitMillis) {
        checkOpen();
        final TableState state = state(table);
        state.lease(waitMillis);
        try {
            return new DefaultWriter(this, state);
        } catch (final RuntimeException e) {
            state.unlease();
            throw e;
        }
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

    // the read's wait for the queued commits its lag does not allow
    private void awaitFresh(final TableState state) {
        final long logged = state.loggedTxn();
        if (state.appliedTxn() >= logged) {
            return;
        }
        final long lagMillis = READ_MAX_LAG_MILLIS.isBound() ? READ_MAX_LAG_MILLIS.get() : readMaxLagMillis;
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
     * still replace its table's {@code _txn}, so the close then fails naming its tables and keeps the root's lock: no
     * store opens the root under that writer. A later close waits for those writers again and, once they are closed,
     * releases the lock.
     */
    @Override
    public void close() {
        synchronized (closeLock) {
            if (!closed) {
                closed = true;
                if (applier != null) {
                    applier.stop();
                }
                for (final TableState state : tables.values()) {
                    final boolean drained = state.leaseForClose(closeWaitMillis);
                    if (drained) {
                        checkpointOnClose(state);
                    }
                    state.close(drained);
                    if (!drained) {
                        writerOpenAtClose.add(state);
                    }
                }
            } else {
                writerOpenAtClose.removeIf(state -> state.leaseForClose(closeWaitMillis));
            }
            if (!writerOpenAtClose.isEmpty()) {
                final List<String> open = new ArrayList<>(writerOpenAtClose.size());
                for (final TableState state : writerOpenAtClose) {
                    open.add(state.key);
                }
                throw new CdbException("cannot close store, writer still open, root lock kept [root=" + root
                        + ", tables=" + open + ", waitMillis=" + closeWaitMillis + ']', -1);
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

    // the closing thread holds the lease: a clean shutdown applies the queued commits and leaves every log empty, else
    // the next open replays it
    private void checkpointOnClose(final TableState state) {
        if (state.poisoned() || state.suspended() || state.current() == null || state.wal.records() == 0) {
            return;
        }
        state.applyLock.lock();
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

    private static void check(final TableDef def) {
        TableFiles.checkName(def.name(), "table");
        if (def.columns().isEmpty()) {
            throw new CdbException("refuse table without columns [table=" + def.name() + ']', -1);
        }
        final Set<String> names = new HashSet<>();
        for (final ColumnDef column : def.columns()) {
            TableFiles.checkName(column.name(), "column");
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
     * {@code _txn} does not name and the symbol files of columns it does not name, cuts the bytes appended past the
     * committed ends of the folders it does, then replays the log and checkpoints what it replayed.
     */
    private static TableState load(final Store store, final Path dir) throws IOException {
        final Path meta = dir.resolve(TableFiles.META);
        final TableDef stored = TableFiles.decodeMeta(StoreFiles.readText(meta), meta.toString());
        final Path txnFile = dir.resolve(TableFiles.TXN);
        final TableFiles.Txn txn = Files.exists(txnFile)
                ? TableFiles.decodeTxn(StoreFiles.readText(txnFile), txnFile.toString())
                : new TableFiles.Txn(0L, List.of());
        final TableDef def = trim(stored, txn, txnFile);
        final Set<String> named = new HashSet<>();
        for (final TableFiles.Entry entry : txn.partitions()) {
            named.add(entry.folder());
        }
        // a column a checkpoint did not reach: its _meta was written, its _txn not, and its log record is the replay's
        final Set<String> unnamedSymbols = new HashSet<>();
        for (int c = def.columns().size(); c < stored.columns().size(); c++) {
            unnamedSymbols.add(stored.columns().get(c).name() + ".sym");
        }
        try (final DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (final Path entry : entries) {
                final String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && !named.contains(name)) {
                    LOG.log(System.Logger.Level.INFO, "deleted partition of an unfinished commit [dir=" + entry + ']');
                    StoreFiles.deleteFolder(entry);
                } else if (name.endsWith(".tmp") || unnamedSymbols.contains(name)) {
                    StoreFiles.delete(entry);
                }
            }
        }
        // the _txn read here may be a rename a kill left unforced: forced before the log's records it covers are cut
        StoreFiles.forceFolder(dir);
        final Wal wal = Wal.open(dir);
        final TableState state = new TableState(dir, def, txn.symbolBytes(), wal);
        final List<Version> versions = new ArrayList<>(txn.partitions().size());
        try {
            for (final TableFiles.Entry entry : txn.partitions()) {
                final Path partition = dir.resolve(entry.folder());
                TableFiles.checkName(entry.folder(), "partition");
                cutToCommitted(partition, def, entry.rows());
                versions.add(Version.open(partition, def, entry.rows(), state.symbols()));
            }
        } catch (final RuntimeException e) {
            for (final Version v : versions) {
                v.unmap();
            }
            throw e;
        }
        state.init(new TableState.Snapshot(txn.number(), def, versions));
        try {
            replay(store, state, wal.scan(txn.number(), def));
        } catch (final IOException | RuntimeException e) {
            state.close(false);
            throw e;
        }
        return state;
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
        try (final DefaultWriter writer = new DefaultWriter(store, state, true)) {
            for (final Wal.Entry entry : entries) {
                if (entry.txn() != last + 1L) {
                    throw new CdbException("refuse log entry out of order [table=" + table + ", txn=" + entry.txn()
                            + ", expected=" + (last + 1L) + ']', -1);
                }
                try {
                    // plain commits in a row are applied as one batch, as the applier does; a truncate or a definition
                    // change ends it and is a commit of its own
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
                    throw new CdbException("cannot replay log [table=" + table + ", txn=" + entry.txn() + ']', e);
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
            writer.newRow(ts >= 0 ? rows.longs[ts][r] : Long.MIN_VALUE);
            for (int c = 0; c < rows.types.length; c++) {
                if (c == ts) {
                    continue;
                }
                switch (rows.types[c]) {
                    case LONG, TIMESTAMP -> writer.putLong(c, rows.longs[c][r]);
                    case DOUBLE -> writer.putDouble(c, rows.doubles[c][r]);
                    case BOOLEAN -> writer.putBoolean(c, rows.booleans[c][r]);
                    case SYMBOL, VARCHAR -> writer.putText(c, rows.texts[c][r]);
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
}
