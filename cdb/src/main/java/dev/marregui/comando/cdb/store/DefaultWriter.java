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
 * The writer of one table. Pending rows are held by column in a {@link RowBlock}; a commit rewrites each partition
 * the rows fall in as a new version folder (its committed rows merged with the new, ordered and deduplicated), syncs
 * the symbol dictionaries, rewrites {@code _meta} when columns were added, and last replaces {@code _txn}: a crash at
 * any step before leaves the previous transaction, and the orphan folders are deleted when the store next opens.
 */
final class DefaultWriter implements TableWriter {

    private static final int INITIAL_ROWS = 1024;

    private final Store store;
    private final TableState state;
    private TableDef def;
    private TableDef committedDef;
    private RowBlock pending;
    private int capacity;
    private boolean truncated;
    private boolean inRow;
    private boolean closed;

    DefaultWriter(final Store store, final TableState state) {
        this.store = store;
        this.state = state;
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
        final TableState.Snapshot current = state.current();
        final long txn = current.txn() + 1L;
        final List<Version> written = new ArrayList<>();
        final List<Version> next = new ArrayList<>(truncated ? List.of() : current.partitions());
        try {
            if (pending.size > 0) {
                writePartitions(txn, next, written);
            }
            expire(next, written);
            for (final SymbolTable symbols : state.symbols()) {
                if (symbols != null) {
                    symbols.sync();
                }
            }
            if (defChanged) {
                StoreFiles.replaceText(state.dir.resolve(TableFiles.META), TableFiles.encodeMeta(def));
            }
            final List<TableFiles.Entry> entries = new ArrayList<>(next.size());
            for (final Version v : next) {
                entries.add(new TableFiles.Entry(v.folder, v.rowCount()));
            }
            store.beforeTxnReplace.run();
            StoreFiles.replaceText(state.dir.resolve(TableFiles.TXN), TableFiles.encodeTxn(txn, entries));
        } catch (final IOException | RuntimeException e) {
            final CdbException failure = new CdbException("cannot commit [table=" + def.name() + ", txn=" + txn
                    + ']', e);
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
        state.publish(new TableState.Snapshot(txn, def, next));
        committedDef = def;
        pending.size = 0;
        truncated = false;
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
        discard();
        state.unlease();
    }

    /** Rewrites every partition the pending rows fall in as version {@code txn}, replacing it in {@code next}. */
    private void writePartitions(final long txn, final List<Version> next, final List<Version> written)
            throws IOException {
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
            final RowBlock merged = merge(old, byFloor, from, to);
            final int[] order = Dedup.order(merged, ts, keys);
            final Path dir = state.dir.resolve(TableFiles.folder(floor, partitionBy, txn));
            writeVersion(dir, merged, order);
            final Version v = Version.open(dir, def, order.length, state.symbols());
            written.add(v);
            if (old != null) {
                next.remove(old);
            }
            next.add(v);
            from = to;
        }
        next.sort(Comparator.comparingLong(Version::lowerBound));
    }

    /** Drops from {@code next} the partitions the table's TTL expires; a version written by this commit is freed. */
    private void expire(final List<Version> next, final List<Version> written) throws IOException {
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
                }
            } else {
                kept.add(v);
            }
        }
        next.clear();
        next.addAll(kept);
    }

    /** The committed rows of {@code old} (none when null), then the pending rows {@code byFloor[from..to)}. */
    private RowBlock merge(final Version old, final int[] byFloor, final int from, final int to) {
        final long oldRows = old == null ? 0L : old.rowCount();
        final int k = to - from;
        if (oldRows > Integer.MAX_VALUE - k) {
            throw new CdbException("refuse partition over capacity [table=" + def.name() + ", rows=" + (oldRows + k)
                    + ']', -1);
        }
        final int base = (int) oldRows;
        final RowBlock merged = new RowBlock(def.columns(), base + k);
        for (int c = 0; c < merged.types.length; c++) {
            if (old != null) {
                switch (merged.types[c]) {
                    case LONG, TIMESTAMP -> old.longs(c, 0L, base, merged.longs[c]);
                    case DOUBLE -> old.doubles(c, 0L, base, merged.doubles[c]);
                    case BOOLEAN -> old.booleans(c, 0L, base, merged.booleans[c]);
                    case SYMBOL, VARCHAR -> old.texts(c, 0L, base, merged.texts[c]);
                }
            }
            for (int i = 0; i < k; i++) {
                final int r = byFloor[from + i];
                switch (merged.types[c]) {
                    case LONG, TIMESTAMP -> merged.longs[c][base + i] = pending.longs[c][r];
                    case DOUBLE -> merged.doubles[c][base + i] = pending.doubles[c][r];
                    case BOOLEAN -> merged.booleans[c][base + i] = pending.booleans[c][r];
                    case SYMBOL, VARCHAR -> merged.texts[c][base + i] = pending.texts[c][r];
                }
            }
        }
        merged.size = base + k;
        return merged;
    }

    /** Writes the rows {@code order} of {@code block} as the version folder {@code dir}, every file forced. */
    private void writeVersion(final Path dir, final RowBlock block, final int[] order) throws IOException {
        // a folder of this name is an orphan of a commit that crashed before naming it in _txn
        StoreFiles.deleteFolder(dir);
        Files.createDirectories(dir);
        final int m = order.length;
        final List<ColumnDef> columns = def.columns();
        for (int c = 0; c < columns.size(); c++) {
            final Path data = dir.resolve(columns.get(c).name() + ".d");
            switch (block.types[c]) {
                case LONG, TIMESTAMP -> {
                    final ByteBuffer buffer = ByteBuffer.allocate(m * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                    for (final int r : order) {
                        buffer.putLong(block.longs[c][r]);
                    }
                    StoreFiles.writeNew(data, buffer.flip());
                }
                case DOUBLE -> {
                    final ByteBuffer buffer = ByteBuffer.allocate(m * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                    for (final int r : order) {
                        buffer.putDouble(block.doubles[c][r]);
                    }
                    StoreFiles.writeNew(data, buffer.flip());
                }
                case BOOLEAN -> {
                    final ByteBuffer buffer = ByteBuffer.allocate(m);
                    for (final int r : order) {
                        buffer.put(block.booleans[c][r] ? (byte) 1 : (byte) 0);
                    }
                    StoreFiles.writeNew(data, buffer.flip());
                }
                case SYMBOL -> {
                    final SymbolTable symbols = state.symbols().get(c);
                    final ByteBuffer buffer = ByteBuffer.allocate(m * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                    for (final int r : order) {
                        buffer.putInt(symbols.intern(block.texts[c][r]));
                    }
                    StoreFiles.writeNew(data, buffer.flip());
                }
                case VARCHAR -> writeVarchar(dir.resolve(columns.get(c).name() + ".i"), data, block.texts[c], order);
            }
        }
    }

    private static void writeVarchar(final Path indexFile, final Path dataFile, final String[] texts, final int[] order)
            throws IOException {
        final ByteBuffer ends = ByteBuffer.allocate(order.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final byte[][] encoded = new byte[order.length][];
        long end = 0L;
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
        final ByteBuffer bytes = ByteBuffer.allocate((int) end);
        for (final byte[] utf8 : encoded) {
            if (utf8 != null) {
                bytes.put(utf8);
            }
        }
        StoreFiles.writeNew(indexFile, ends.flip());
        StoreFiles.writeNew(dataFile, bytes.flip());
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
