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
 * The writer of one table. Pending rows are held by column in a {@link RowBlock}; a commit orders and deduplicates the
 * rows of each partition they fall in, then appends them in place to the partition's column files when they all come
 * after its committed rows, else rewrites the partition as a new version folder (its committed rows merged with the
 * new), all unforced, its symbols interned in memory. Then its commit point: a commit appends its pending rows to the
 * table's log and forces it, and is visible once that returns; a bulk commit, a definition change or a truncate is
 * named by a {@link Checkpoint} instead, as every commit was before the log. Once the log holds enough, a commit
 * checkpoints the table: what the commits since the last one wrote is forced and {@code _txn} names it; closing the
 * writer does not, the store's close and {@link Store#checkpoint} do. A crash leaves
 * the last checkpoint and the log: the store's next open cuts the bytes appended past the committed ends, deletes the
 * orphan folders and replays the log.
 */
final class DefaultWriter implements TableWriter {

    private static final System.Logger LOG = System.getLogger(DefaultWriter.class.getName());
    private static final int INITIAL_ROWS = 1024;

    private final Store store;
    private final TableState state;
    // the recovery's writer: applies and publishes the log's commits, never logs nor checkpoints
    private final boolean replay;
    private TableDef def;
    private TableDef committedDef;
    private RowBlock pending;
    private int capacity;
    private boolean truncated;
    private boolean inRow;
    private boolean closed;

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
        state.addSymbols(column);
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
        if (state.poisoned()) {
            throw new CdbException("refuse commit to failed table [table=" + def.name() + ']', -1);
        }
        final TableState.Snapshot current = state.current();
        final long txn = current.txn() + 1L;
        // definition changes stay off the log: rare, and a checkpoint writes _meta before the _txn that needs it
        final boolean direct = !replay && (pending.size >= store.directRows || defChanged || truncated
                || logBytesAtLeast(store.checkpointBytes));
        // versions in new folders, and versions of a committed folder its rows were appended to
        final List<Version> written = new ArrayList<>();
        final List<Version> appended = new ArrayList<>();
        final List<Version> next = new ArrayList<>(truncated ? List.of() : current.partitions());
        final TableState.Snapshot snapshot;
        try {
            if (pending.size > 0) {
                writePartitions(txn, next, written, appended);
            }
            expire(next, written, appended);
            state.wrote(written, true);
            state.wrote(appended, false);
            snapshot = new TableState.Snapshot(txn, def, next);
            if (direct) {
                if (!directCheckpoint(snapshot)) {
                    // no log record holds this commit: lost if the rename of _txn does not survive a power cut
                    state.poison();
                    LOG.log(System.Logger.Level.ERROR, "refuse further commits, a folder force left a commit in"
                            + " doubt [table=" + def.name() + ", txn=" + txn + ']');
                }
            } else if (!replay) {
                log(txn);
            }
        } catch (final IOException | RuntimeException e) {
            final CdbException failure = new CdbException("cannot commit [table=" + def.name() + ", txn=" + txn
                    + ']', e);
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
            throw failure;
        }
        if (!direct && !replay) {
            // the commit is durable from here: a failure of this hook leaves it to the log's replay
            store.afterWalForce.run();
        }
        state.publish(snapshot);
        committedDef = def;
        pending.size = 0;
        truncated = false;
        if (!direct && !replay && (state.wal.records() >= store.checkpointRecords
                || state.wal.bytes() >= store.checkpointBytes)) {
            checkpoint();
        }
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
            state.unlease();
        }
    }

    /**
     * Appends the pending rows to the log as commit {@code txn} and forces it. A failure poisons the table: the log is
     * cut back or in doubt, and after a failed force the page cache cannot be trusted.
     */
    private void log(final long txn) throws IOException {
        store.beforeWalAppend.run();
        // an interrupt would close the log's channel mid-force and fail a commit nobody cancelled
        final boolean interrupted = Thread.interrupted();
        try {
            state.wal.append(txn, false, null, pending);
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
     * Checkpoints the published snapshot. A failure leaves the commits standing, in the log, and refuses the table's
     * further commits until the store reopens: after a failed force the page cache cannot be trusted, so a retry could
     * report a force that lost data and then cut the log that still holds it.
     */
    private void checkpoint() {
        final TableState.Snapshot published = state.current();
        if (published == null) {
            // the store closed under the writer: its next open replays the log
            return;
        }
        try {
            if (!runCheckpoint(published)) {
                state.poison();
                LOG.log(System.Logger.Level.ERROR, "refuse further commits, a folder force failed in a checkpoint,"
                        + " log kept [table=" + def.name() + ", txn=" + published.txn() + ']');
            }
        } catch (final IOException | RuntimeException e) {
            state.poison();
            LOG.log(System.Logger.Level.ERROR, "refuse further commits, cannot checkpoint table, log kept [table="
                    + def.name() + ", txn=" + published.txn() + ']', e);
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
                v = Version.open(old.dir, def, old.rowCount() + order.length, state.symbols());
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
            state.trimSymbols(def.columns().size());
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
