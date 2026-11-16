package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The writer of one table. Pending rows are held by column in a {@link RowBlock}. A commit appends them to the table's
 * log and forces it, queues them and is answered: the store's {@link Applier} later writes many queued commits at once
 * into the column files and publishes them. In a store with a {@link LogForcer} the record is written unforced and
 * the commit answered before its force, which {@link #ticket()} waits for. A bulk commit, a definition change or a
 * truncate is direct instead: the queue is applied first, then the commit is written and named by a
 * {@link Checkpoint}, as every commit was before the log. Writing a batch (the applier's writer, in replay mode, also
 * the recovery's) orders and deduplicates the rows of each partition they fall in, then appends them in place to the
 * partition's column files when they all come after its committed rows, else rewrites the partition as a new version
 * folder (its committed rows merged with the new), all unforced, its symbols interned in memory. A crash leaves the
 * last checkpoint and the log: the store's next open cuts the bytes appended past the committed ends, deletes the
 * orphan folders and replays the log.
 */
final class DefaultWriter implements TableWriter {

    private static final System.Logger LOG = System.getLogger(DefaultWriter.class.getName());
    private static final int INITIAL_ROWS = 1024;

    private final Store store;
    private final TableState state;
    // the applier's and the recovery's writer: writes and publishes logged commits, never logs nor checkpoints, holds
    // no lease
    private final boolean replay;
    // replay mode: the transaction the next commit publishes as
    private long replayTxn;
    private TableDef def;
    private TableDef committedDef;
    private RowBlock pending;
    private int capacity;
    private boolean truncated;
    private boolean inRow;
    private boolean closed;
    // the durability of the last answered commit: a logged commit's under the timed force, else done
    private ForceTicket ticket = ForceTicket.DONE;

    DefaultWriter(final Store store, final TableState state) {
        this(store, state, false);
    }

    DefaultWriter(final Store store, final TableState state, final boolean replay) {
        this.store = store;
        this.state = state;
        this.replay = replay;
        this.committedDef = state.current().def();
        this.def = committedDef;
        this.pending = new RowBlock(def.columns(), INITIAL_ROWS);
        this.capacity = INITIAL_ROWS;
    }

    @Override
    public TableDef def() {
        return def;
    }

    @Override
    public int addColumn(final ColumnDef column) {
        checkOpen();
        TableFiles.checkName(column.name(), "column");
        if (def.columnIndex(column.name()) >= 0) {
            throw new CdbException("refuse duplicate column [table=" + def.name() + ", column=" + column.name() + ']',
                    -1);
        }
        def = def.withColumn(column);
        pending = resize(pending, def.columns(), capacity, capacity);
        final int c = def.columns().size() - 1;
        fillNull(pending, c, 0, inRow ? pending.size + 1 : pending.size);
        // the applier reads the dictionaries of the columns before it
        state.applyLock.lock();
        try {
            state.addSymbols(column);
        } finally {
            state.applyLock.unlock();
        }
        return c;
    }

    @Override
    public void setTtl(final long ttlMicros) {
        checkOpen();
        def = def.withTtlMicros(ttlMicros);
    }

    @Override
    public void newRow(final long micros) {
        checkOpen();
        if (inRow) {
            throw new CdbException("refuse new row before the current one ended [table=" + def.name() + ']', -1);
        }
        final int ts = def.timestampIndex();
        if (ts >= 0 && micros == Long.MIN_VALUE) {
            throw new CdbException("refuse row without designated timestamp [table=" + def.name() + ']', -1);
        }
        final int row = pending.size;
        if (row == capacity) {
            if (row >= Integer.MAX_VALUE / 2) {
                throw new CdbException("refuse pending rows over capacity [table=" + def.name() + ", rows=" + row + ']',
                        -1);
            }
            pending = resize(pending, def.columns(), capacity, row * 2);
            capacity = row * 2;
        }
        for (int c = 0; c < pending.types.length; c++) {
            fillNull(pending, c, row, row + 1);
        }
        if (ts >= 0) {
            pending.longs[ts][row] = micros;
        }
        inRow = true;
    }

    @Override
    public void putLong(final int column, final long value) {
        check(column, CdbType.LONG, CdbType.TIMESTAMP);
        pending.longs[column][pending.size] = value;
    }

    @Override
    public void putDouble(final int column, final double value) {
        check(column, CdbType.DOUBLE, CdbType.DOUBLE);
        pending.doubles[column][pending.size] = value;
    }

    @Override
    public void putBoolean(final int column, final boolean value) {
        check(column, CdbType.BOOLEAN, CdbType.BOOLEAN);
        pending.booleans[column][pending.size] = value;
    }

    @Override
    public void putText(final int column, final String value) {
        check(column, CdbType.SYMBOL, CdbType.VARCHAR);
        pending.texts[column][pending.size] = value;
    }

    @Override
    public void endRow() {
        checkOpen();
        if (!inRow) {
            throw new CdbException("refuse end of row not started [table=" + def.name() + ']', -1);
        }
        pending.size++;
        inRow = false;
    }

    @Override
    public void truncate() {
        checkOpen();
        pending.size = 0;
        inRow = false;
        truncated = true;
    }

    @Override
    public void commit() {
        checkOpen();
        if (inRow) {
            throw new CdbException("refuse commit inside a row [table=" + def.name() + ']', -1);
        }
        final boolean defChanged = def != committedDef;
        if (!truncated && pending.size == 0 && !defChanged) {
            return;
        }
        if (replay) {
            // the applier publishes the commits a poisoned table answered before its poison: they are in the log
            write(replayTxn, false);
            return;
        }
        if (state.poisoned() || state.suspended()) {
            throw new CdbException("refuse commit to failed table [table=" + def.name() + ']', -1);
        }
        // definition changes stay off the log: rare, and a checkpoint writes _meta before the _txn that needs it
        if (pending.size < store.directRows && !defChanged && !truncated && !logBytesAtLeast(store.checkpointBytes)) {
            logged();
            return;
        }
        // as in log: an interrupt would close a channel mid-write or mid-force
        final boolean interrupted = Thread.interrupted();
        state.applyLock.lock();
        try {
            // a store closed under the writer refuses the commit before its queue is applied
            committed();
            // the queued commits come first, so a batch never spans a definition change or a truncate
            store.applier().applyAllHeld(state, false);
            final long txn = committed().txn() + 1L;
            // a checkpoint that held made it durable; one whose folder force failed left it in doubt
            ticket = write(txn, true) ? ForceTicket.DONE : inDoubt(def.name(), txn);
        } finally {
            state.applyLock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Commits the pending rows as transaction {@code txn}, in replay mode, the caller holding the table's apply lock:
     * the applier's batch of queued commits.
     */
    void commitAs(final long txn) {
        replayTxn = txn;
        commit();
    }

    /** Adds {@code rows}, in this writer's columns, after the pending rows: a queued commit of the applier's batch. */
    void addRows(final RowBlock rows) {
        checkOpen();
        final int n = pending.size + rows.size;
        if (n > capacity) {
            pending = resize(pending, def.columns(), capacity, Math.max(n, capacity * 2));
            capacity = Math.max(n, capacity * 2);
        }
        for (int c = 0; c < pending.types.length; c++) {
            switch (pending.types[c]) {
                case LONG, TIMESTAMP -> System.arraycopy(rows.longs[c], 0, pending.longs[c], pending.size, rows.size);
                case DOUBLE -> System.arraycopy(rows.doubles[c], 0, pending.doubles[c], pending.size, rows.size);
                case BOOLEAN -> System.arraycopy(rows.booleans[c], 0, pending.booleans[c], pending.size, rows.size);
                case SYMBOL, VARCHAR -> System.arraycopy(rows.texts[c], 0, pending.texts[c], pending.size, rows.size);
            }
        }
        pending.size = n;
    }

    /**
     * A logged commit: appends the pending rows to the log as the next transaction, then queues them for the applier;
     * answered once this returns, visible once applied. Durable by then under the force per commit; under the timed
     * force the record is written unforced, the forcer told, and {@link #ticket()} waits for its force.
     */
    private void logged() {
        checkApplicable();
        final long txn = state.loggedTxn() + 1L;
        final LogForcer forcer = store.forcer();
        final ForceTicket answered;
        try {
            answered = log(txn, forcer);
        } catch (final IOException e) {
            throw new CdbException("cannot commit [table=" + def.name() + ", txn=" + txn + ']', e);
        }
        if (forcer != null) {
            forcer.dirty(state);
        }
        final int n = pending.size;
        final long bytes = heapBytes();
        try {
            state.enqueue(new TableState.Queued(txn, resize(pending, def.columns(), capacity, n), System.nanoTime(),
                    bytes));
        } catch (final CdbException refused) {
            retract(txn, refused);
            throw refused;
        }
        pending.size = 0;
        ticket = answered;
        store.applier().committed(state, bytes);
        // the commit is logged and queued from here: a failure of this hook leaves it to the applier
        store.afterWalForce.run();
        if (store.applyOnCommit) {
            store.applyNow(state);
        }
    }

    /**
     * Refuses, before it is logged and answered, a commit the applier could refuse: rows over an {@code int} or text
     * over 2 GB in one partition. Conservative: the largest partition's committed rows and text, plus every queued
     * commit's (text at 3 UTF-8 bytes a char, bounded by 1.5 times their heap estimate), plus this commit's, as if
     * all fell in one partition.
     */
    private void checkApplicable() {
        // a close racing past this check is refused when the commit queues
        final TableState.Snapshot current = committed();
        final List<ColumnDef> columns = def.columns();
        final int committedColumns = current.def().columns().size();
        long rows = 0L;
        final long[] text = new long[columns.size()];
        for (final Version v : current.partitions()) {
            rows = Math.max(rows, v.rowCount());
            for (int c = 0; c < committedColumns; c++) {
                if (columns.get(c).type() == CdbType.VARCHAR) {
                    text[c] = Math.max(text[c], v.dataBytes(c));
                }
            }
        }
        final long queuedText = state.queuedBytes() * 3L / 2L;
        rows += state.queuedRows() + pending.size;
        if (rows > Integer.MAX_VALUE) {
            throw new CdbException("refuse commit over a partition's row capacity [table=" + def.name() + ", rows="
                    + rows + ", limit=" + Integer.MAX_VALUE + ']', -1);
        }
        for (int c = 0; c < columns.size(); c++) {
            if (columns.get(c).type() != CdbType.VARCHAR) {
                continue;
            }
            long bytes = text[c] + queuedText;
            final String[] texts = pending.texts[c];
            for (int r = 0; r < pending.size; r++) {
                bytes += texts[r] == null ? 0L : 3L * texts[r].length();
            }
            if (bytes >= store.textLimitBytes) {
                throw new CdbException("refuse commit over a partition's text capacity [table=" + def.name()
                        + ", column=" + columns.get(c).name() + ", bytes=" + bytes + ", limit=" + store.textLimitBytes
                        + ']', -1);
            }
        }
    }

    // called with the apply lock held, or by the recovery: writes the pending rows as transaction txn and publishes it,
    // named by a checkpoint when direct; false when that checkpoint left the commit in doubt
    private boolean write(final long txn, final boolean direct) {
        final TableState.Snapshot current = committed();
        // versions in new folders, and versions of a committed folder its rows were appended to
        final List<Version> written = new ArrayList<>();
        final List<Version> appended = new ArrayList<>();
        final List<Version> next = new ArrayList<>(truncated ? List.of() : current.partitions());
        final TableState.Snapshot snapshot;
        final boolean held;
        try {
            if (pending.size > 0) {
                writePartitions(txn, next, written, appended);
            }
            expire(next, written, appended);
            state.wrote(written, true);
            state.wrote(appended, false);
            snapshot = new TableState.Snapshot(txn, def, next);
            held = !direct || directCheckpoint(snapshot);
            if (!held) {
                // no log record holds this commit: lost if the rename of _txn does not survive a power cut
                state.poison();
                LOG.log(System.Logger.Level.ERROR, "refuse further commits, a folder force left a commit in"
                        + " doubt [table=" + def.name() + ", txn=" + txn + ']');
            }
        } catch (final IOException | RuntimeException e) {
            final CdbException failure = new CdbException("cannot commit [table=" + def.name() + ", txn=" + txn
                    + ']', e);
            drop(written, appended, failure);
            throw failure;
        } catch (final Error e) {
            // the table's state may be half-written: refused until the store is opened again
            state.poison();
            LOG.log(System.Logger.Level.ERROR, "refuse further commits, an error stopped a commit [table="
                    + def.name() + ", txn=" + txn + ']', e);
            drop(written, appended, e);
            throw e;
        }
        state.publish(snapshot);
        committedDef = def;
        pending.size = 0;
        truncated = false;
        return held;
    }

    // a failed write's versions: unmapped, the new folders deleted; each cleanup failure added to failure
    private void drop(final List<Version> written, final List<Version> appended, final Throwable failure) {
        // the appended bytes stay past the committed ends, where no reader looks; the next commit rewrites these
        for (final Version v : appended) {
            state.torn().add(v.folder);
            try {
                v.unmap();
            } catch (final RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        for (final Version v : written) {
            try {
                v.unmap();
                StoreFiles.deleteFolder(v.dir);
            } catch (final IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    // the table's committed snapshot; refused once the store closed under the writer
    private TableState.Snapshot committed() {
        final TableState.Snapshot current = state.current();
        if (current == null) {
            throw new CdbException("refuse commit to closed table [table=" + def.name() + ']', -1);
        }
        return current;
    }

    @Override
    public ForceTicket ticket() {
        return ticket;
    }

    @Override
    public void rollback() {
        checkOpen();
        discard();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // no checkpoint here: a writer per group commit or statement would pay one per commit; the log keeps them
        try {
            discard();
        } finally {
            if (!replay) {
                state.unlease();
            }
        }
    }

    /**
     * Appends the pending rows to the log as commit {@code txn}, forced when {@code forcer} is null, else written
     * unforced for {@code forcer} to force; returns the durability of the record. A failure poisons the table: the log
     * is cut back or in doubt, and after a failed force the page cache cannot be trusted.
     */
    private ForceTicket log(final long txn, final LogForcer forcer) throws IOException {
        store.beforeWalAppend.run();
        // an interrupt would close the log's channel mid-write or mid-force and fail a commit nobody cancelled
        final boolean interrupted = Thread.interrupted();
        try {
            if (forcer == null) {
                state.wal.append(txn, false, null, pending);
                return ForceTicket.DONE;
            }
            return new Ticket(forcer, state, state.wal.write(txn, false, null, pending));
        } catch (final IOException | RuntimeException e) {
            state.poison();
            throw e;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The force covering record {@code seq} of {@code state}'s log, written unforced by a commit under the timed force:
     * a wait asks {@code forcer} to force the log now rather than at its deadline. Safe to wait on from any thread.
     */
    private record Ticket(LogForcer forcer, TableState state, long seq) implements ForceTicket {

        @Override
        public boolean await(final long timeoutNanos) throws InterruptedException {
            // a record a force already covered costs the forcer no force of its own
            if (state.wal.forcedSeq() >= seq) {
                return true;
            }
            forcer.urgent(state);
            return state.wal.awaitForced(seq, timeoutNanos);
        }
    }

    // the durability of direct commit txn of table, whose checkpoint's folder force failed: no force can settle it
    private static ForceTicket inDoubt(final String table, final long txn) {
        return _ -> {
            throw new CdbException("refuse durable commit, commit in doubt after failed checkpoint [table=" + table
                    + ", txn=" + txn + ']', -1);
        };
    }

    /**
     * Cuts the record of commit {@code txn}, refused by a table the store closed after it was logged, out of the log:
     * the caller sees the commit fail, so the next open must not replay it. Only this writer appends to its table and the
     * store's applier has stopped, so the record is the log's last. A failed cut leaves the commit in doubt.
     */
    private void retract(final long txn, final CdbException refused) {
        // an interrupt would close the cut's channel and leave the refused record to a replay: cleared, then restored
        final boolean interrupted = Thread.interrupted();
        try {
            state.wal.retract(txn);
        } catch (final IOException e) {
            refused.addSuppressed(e);
            state.poison();
            LOG.log(System.Logger.Level.ERROR, "cannot cut refused commit from log, commit in doubt [table="
                    + def.name() + ", txn=" + txn + ']', e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * True when the pending rows' log record would take at least {@code limit} bytes, counting each text char as 3
     * UTF-8 bytes (the most a UTF-16 char encodes to): such a commit is written as a checkpoint, so its text is not
     * copied twice more into a record.
     */
    private boolean logBytesAtLeast(final long limit) {
        final int n = pending.size;
        long bytes = 0L;
        for (int c = 0; c < pending.types.length; c++) {
            final CdbType type = pending.types[c];
            if (type == CdbType.SYMBOL || type == CdbType.VARCHAR) {
                final String[] texts = pending.texts[c];
                for (int r = 0; r < n; r++) {
                    bytes += Integer.BYTES + (texts[r] == null ? 0L : 3L * texts[r].length());
                }
            } else {
                bytes += (long) n * (type == CdbType.BOOLEAN ? 1 : Long.BYTES);
            }
            if (bytes >= limit) {
                return true;
            }
        }
        return false;
    }

    /** The heap the pending rows take, estimated: 8 bytes a fixed value, 1 a boolean, 48 plus 2 a char a text. */
    private long heapBytes() {
        final int n = pending.size;
        long bytes = 0L;
        for (int c = 0; c < pending.types.length; c++) {
            final CdbType type = pending.types[c];
            if (type == CdbType.SYMBOL || type == CdbType.VARCHAR) {
                final String[] texts = pending.texts[c];
                for (int r = 0; r < n; r++) {
                    bytes += Integer.BYTES + (texts[r] == null ? 0L : 48L + 2L * texts[r].length());
                }
            } else {
                bytes += (long) n * (type == CdbType.BOOLEAN ? 1 : Long.BYTES);
            }
        }
        return bytes;
    }

    /**
     * The checkpoint of a direct commit: a failed disk operation refuses the table's further commits, as one after a
     * logged commit does (a later checkpoint would force again the files of the commits still in the log), and is
     * thrown for the commit's cleanup.
     */
    private boolean directCheckpoint(final TableState.Snapshot snapshot) throws IOException {
        try {
            return runCheckpoint(snapshot);
        } catch (final IOException e) {
            state.poison();
            throw e;
        }
    }

    private boolean runCheckpoint(final TableState.Snapshot snapshot) throws IOException {
        // as in log: an interrupt would close a channel mid-force
        final boolean interrupted = Thread.interrupted();
        try {
            return Checkpoint.run(store, state, snapshot);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Writes every partition the pending rows fall in, replacing it in {@code next}: appended in place when the rows
     * come after its committed ones, else rewritten as version {@code txn}.
     */
    private void writePartitions(final long txn, final List<Version> next, final List<Version> written,
            final List<Version> appended) throws IOException {
        final int n = pending.size;
        final int ts = def.timestampIndex();
        final PartitionBy partitionBy = def.partitionBy();
        final long[] floors = new long[n];
        for (int r = 0; r < n; r++) {
            floors[r] = ts < 0 ? Long.MIN_VALUE : Retention.floor(pending.longs[ts][r], partitionBy);
        }
        final int[] byFloor = Dedup.stableOrder(floors, n);
        final int[] keys = keyColumns();
        int from = 0;
        while (from < n) {
            final long floor = floors[byFloor[from]];
            int to = from + 1;
            while (to < n && floors[byFloor[to]] == floor) {
                to++;
            }
            Version old = null;
            for (final Version v : next) {
                if (v.lowerBound() == floor) {
                    old = v;
                    break;
                }
            }
            final RowBlock rows = pendingRows(byFloor, from, to);
            final int[] order = Dedup.order(rows, ts, keys);
            final Version v;
            if (old != null && store.appendInPlace && appendable(old, rows, order[0], ts, keys.length)) {
                append(old, rows, order);
                v = Version.open(old.dir, def, old.rowCount() + order.length, state.symbols(), old.fileColumns);
                appended.add(v);
            } else {
                final Path dir = state.dir.resolve(TableFiles.folder(floor, partitionBy, txn));
                if (old == null) {
                    writeVersion(dir, rows, order);
                    v = Version.open(dir, def, order.length, state.symbols());
                } else {
                    final RowBlock merged = merge(old, rows, order);
                    final int[] mergedOrder = Dedup.mergeOrder(merged, ts, keys, (int) old.rowCount());
                    writeVersion(dir, merged, mergedOrder);
                    v = Version.open(dir, def, mergedOrder.length, state.symbols());
                }
                written.add(v);
            }
            if (old != null) {
                next.remove(old);
            }
            next.add(v);
            from = to;
        }
        next.sort(Comparator.comparingLong(Version::lowerBound));
    }

    /**
     * Drops from {@code next} the partitions the table's TTL expires; a version written by this commit is freed, its
     * folder deleted when it is new.
     */
    private void expire(final List<Version> next, final List<Version> written, final List<Version> appended)
            throws IOException {
        if (def.ttlMicros() <= 0L || next.isEmpty()) {
            return;
        }
        long newest = Long.MIN_VALUE;
        for (final Version v : next) {
            newest = Math.max(newest, v.maxTimestamp());
        }
        final List<Version> kept = new ArrayList<>(next.size());
        for (final Version v : next) {
            if (Retention.expired(v.lowerBound(), def.partitionBy(), def.ttlMicros(), newest)) {
                if (written.remove(v)) {
                    v.unmap();
                    StoreFiles.deleteFolder(v.dir);
                } else if (appended.remove(v)) {
                    v.unmap();
                }
            } else {
                kept.add(v);
            }
        }
        next.clear();
        next.addAll(kept);
    }

    /** The pending rows {@code byFloor[from..to)}, in that order. */
    private RowBlock pendingRows(final int[] byFloor, final int from, final int to) {
        final int k = to - from;
        final RowBlock rows = new RowBlock(def.columns(), k);
        for (int c = 0; c < rows.types.length; c++) {
            for (int i = 0; i < k; i++) {
                final int r = byFloor[from + i];
                switch (rows.types[c]) {
                    case LONG, TIMESTAMP -> rows.longs[c][i] = pending.longs[c][r];
                    case DOUBLE -> rows.doubles[c][i] = pending.doubles[c][r];
                    case BOOLEAN -> rows.booleans[c][i] = pending.booleans[c][r];
                    case SYMBOL, VARCHAR -> rows.texts[c][i] = pending.texts[c][r];
                }
            }
        }
        rows.size = k;
        return rows;
    }

    /**
     * True when the rows {@code order} of {@code rows}, the first at {@code first}, can go after the committed rows of
     * {@code old} unchanged: every column has its files there, no failed commit left bytes past their ends, and every
     * row comes after the committed ones, or at the same timestamp when no dedup key can make it replace one. Without a
     * designated timestamp rows keep their written order, so they always can.
     */
    private boolean appendable(final Version old, final RowBlock rows, final int first, final int ts,
            final int keyCount) {
        final List<ColumnDef> columns = def.columns();
        for (int c = 0; c < columns.size(); c++) {
            final String name = columns.get(c).name();
            if (!Files.exists(old.dir.resolve(name + ".d"))
                    || columns.get(c).type() == CdbType.VARCHAR && !Files.exists(old.dir.resolve(name + ".i"))) {
                return false;
            }
        }
        if (state.torn().contains(old.folder)) {
            return false;
        }
        if (ts < 0) {
            return true;
        }
        final long min = rows.longs[ts][first];
        return min > old.maxTimestamp() || min == old.maxTimestamp() && keyCount == 0;
    }

    /**
     * Writes the rows {@code order} of {@code rows} into {@code old}'s column files from its committed ends, unforced:
     * the commit's log record, or its checkpoint, makes them durable.
     */
    private void append(final Version old, final RowBlock rows, final int[] order) throws IOException {
        final long committed = old.rowCount();
        if (committed > Integer.MAX_VALUE - order.length) {
            throw new CdbException("refuse partition over capacity [table=" + def.name() + ", rows="
                    + (committed + order.length) + ']', -1);
        }
        final List<ColumnDef> columns = def.columns();
        for (int c = 0; c < columns.size(); c++) {
            final Path data = old.dir.resolve(columns.get(c).name() + ".d");
            if (rows.types[c] == CdbType.VARCHAR) {
                final Path indexFile = old.dir.resolve(columns.get(c).name() + ".i");
                final long end = Version.decodeEnd(StoreFiles.readLong(indexFile, (committed - 1L) * Long.BYTES));
                final Text text = varchar(data, rows.texts[c], order, end);
                StoreFiles.writeAt(data, end, text.bytes(), false);
                StoreFiles.writeAt(indexFile, committed * Long.BYTES, text.ends(), false);
            } else {
                StoreFiles.writeAt(data, committed * TableFiles.width(rows.types[c]), fixed(rows, c, order), false);
            }
        }
    }

    /** The committed rows of {@code old}, then the rows {@code order} of {@code rows}. */
    private RowBlock merge(final Version old, final RowBlock rows, final int[] order) {
        final long oldRows = old.rowCount();
        final int k = order.length;
        if (oldRows > Integer.MAX_VALUE - k) {
            throw new CdbException("refuse partition over capacity [table=" + def.name() + ", rows=" + (oldRows + k)
                    + ']', -1);
        }
        final int base = (int) oldRows;
        final RowBlock merged = new RowBlock(def.columns(), base + k);
        for (int c = 0; c < merged.types.length; c++) {
            switch (merged.types[c]) {
                case LONG, TIMESTAMP -> old.longs(c, 0L, base, merged.longs[c]);
                case DOUBLE -> old.doubles(c, 0L, base, merged.doubles[c]);
                case BOOLEAN -> old.booleans(c, 0L, base, merged.booleans[c]);
                case SYMBOL, VARCHAR -> old.texts(c, 0L, base, merged.texts[c]);
            }
            for (int i = 0; i < k; i++) {
                final int r = order[i];
                switch (merged.types[c]) {
                    case LONG, TIMESTAMP -> merged.longs[c][base + i] = rows.longs[c][r];
                    case DOUBLE -> merged.doubles[c][base + i] = rows.doubles[c][r];
                    case BOOLEAN -> merged.booleans[c][base + i] = rows.booleans[c][r];
                    case SYMBOL, VARCHAR -> merged.texts[c][base + i] = rows.texts[c][r];
                }
            }
        }
        merged.size = base + k;
        return merged;
    }

    /** Writes the rows {@code order} of {@code block} as the version folder {@code dir}, unforced, as {@link #append}. */
    private void writeVersion(final Path dir, final RowBlock block, final int[] order) throws IOException {
        // a folder of this name is an orphan of a commit that crashed before naming it in _txn
        StoreFiles.deleteFolder(dir);
        StoreFiles.createFolder(dir);
        final List<ColumnDef> columns = def.columns();
        for (int c = 0; c < columns.size(); c++) {
            final Path data = dir.resolve(columns.get(c).name() + ".d");
            if (block.types[c] == CdbType.VARCHAR) {
                final Text text = varchar(data, block.texts[c], order, 0L);
                StoreFiles.writeNew(dir.resolve(columns.get(c).name() + ".i"), text.ends(), false);
                StoreFiles.writeNew(data, text.bytes(), false);
            } else {
                StoreFiles.writeNew(data, fixed(block, c, order), false);
            }
        }
    }

    /** The rows {@code order} of fixed-width column {@code c} of {@code block}, as its file holds them. */
    private ByteBuffer fixed(final RowBlock block, final int c, final int[] order) {
        final int m = order.length;
        final ByteBuffer buffer = ByteBuffer.allocate(m * TableFiles.width(block.types[c]))
                .order(ByteOrder.LITTLE_ENDIAN);
        switch (block.types[c]) {
            case LONG, TIMESTAMP -> {
                for (final int r : order) {
                    buffer.putLong(block.longs[c][r]);
                }
            }
            case DOUBLE -> {
                for (final int r : order) {
                    buffer.putDouble(block.doubles[c][r]);
                }
            }
            case BOOLEAN -> {
                for (final int r : order) {
                    buffer.put(block.booleans[c][r] ? (byte) 1 : (byte) 0);
                }
            }
            case SYMBOL -> {
                final SymbolTable symbols = state.symbols().get(c);
                for (final int r : order) {
                    buffer.putInt(symbols.intern(block.texts[c][r]));
                }
            }
            case VARCHAR -> throw new IllegalArgumentException("refuse VARCHAR as fixed width [column=" + c + ']');
        }
        return buffer.flip();
    }

    /** A VARCHAR column's rows as its files hold them: the {@code .i} end offsets and the {@code .d} UTF-8 bytes. */
    private record Text(ByteBuffer ends, ByteBuffer bytes) {
    }

    /** The rows {@code order} of {@code texts}, their end offsets counted from {@code base}, where they will start. */
    private static Text varchar(final Path dataFile, final String[] texts, final int[] order, final long base) {
        final ByteBuffer ends = ByteBuffer.allocate(order.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final byte[][] encoded = new byte[order.length][];
        long end = base;
        for (int i = 0; i < order.length; i++) {
            final String text = texts[order[i]];
            if (text == null) {
                ends.putLong(~end);
            } else {
                encoded[i] = text.getBytes(StandardCharsets.UTF_8);
                end += encoded[i].length;
                ends.putLong(end);
            }
        }
        if (end > Integer.MAX_VALUE) {
            throw new CdbException("refuse text column over 2 GB in one partition [file=" + dataFile + ", bytes=" + end
                    + ']', -1);
        }
        final ByteBuffer bytes = ByteBuffer.allocate((int) (end - base));
        for (final byte[] utf8 : encoded) {
            if (utf8 != null) {
                bytes.put(utf8);
            }
        }
        return new Text(ends.flip(), bytes.flip());
    }

    private int[] keyColumns() {
        final List<String> names = def.dedupKeys();
        final int[] keys = new int[names.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = def.columnIndex(names.get(i));
        }
        return keys;
    }

    private void discard() {
        pending.size = 0;
        inRow = false;
        truncated = false;
        if (def != committedDef) {
            def = committedDef;
            pending = new RowBlock(def.columns(), INITIAL_ROWS);
            capacity = INITIAL_ROWS;
            state.applyLock.lock();
            try {
                state.trimSymbols(def.columns().size());
            } finally {
                state.applyLock.unlock();
            }
        }
    }

    private void check(final int column, final CdbType a, final CdbType b) {
        checkOpen();
        if (!inRow) {
            throw new CdbException("refuse value outside a row [table=" + def.name() + ", column=" + column + ']', -1);
        }
        if (column < 0 || column >= pending.types.length) {
            throw new CdbException("refuse unknown column index [table=" + def.name() + ", column=" + column + ']', -1);
        }
        final CdbType type = pending.types[column];
        if (type != a && type != b) {
            throw new CdbException("refuse value of another type [table=" + def.name() + ", column="
                    + def.columns().get(column).name() + ", type=" + type + ", value=" + a + ']', -1);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new CdbException("refuse use of closed writer [table=" + def.name() + ']', -1);
        }
    }

    /**
     * A block of {@code columns} and {@code rows} capacity holding the rows of {@code block}, of {@code oldRows}
     * capacity, the row being written included; columns added are left for the caller to fill.
     */
    private static RowBlock resize(final RowBlock block, final List<ColumnDef> columns, final int oldRows,
            final int rows) {
        final RowBlock out = new RowBlock(columns, rows);
        final int keep = Math.min(block.size + 1, Math.min(oldRows, rows));
        for (int c = 0; c < block.types.length; c++) {
            switch (block.types[c]) {
                case LONG, TIMESTAMP -> System.arraycopy(block.longs[c], 0, out.longs[c], 0, keep);
                case DOUBLE -> System.arraycopy(block.doubles[c], 0, out.doubles[c], 0, keep);
                case BOOLEAN -> System.arraycopy(block.booleans[c], 0, out.booleans[c], 0, keep);
                case SYMBOL, VARCHAR -> System.arraycopy(block.texts[c], 0, out.texts[c], 0, keep);
            }
        }
        out.size = block.size;
        return out;
    }

    private static void fillNull(final RowBlock block, final int c, final int from, final int to) {
        switch (block.types[c]) {
            case LONG, TIMESTAMP -> Arrays.fill(block.longs[c], from, to, Long.MIN_VALUE);
            case DOUBLE -> Arrays.fill(block.doubles[c], from, to, Double.NaN);
            case BOOLEAN -> Arrays.fill(block.booleans[c], from, to, false);
            case SYMBOL, VARCHAR -> Arrays.fill(block.texts[c], from, to, null);
        }
    }
}
