package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;

/**
 * One version folder of a partition: its column files are mapped on a column's first read, into one shared arena, and
 * stay mapped until the version is dropped or {@link #releaseIfIdle} finds it idle; the next read maps again. The
 * table's state counts the holders ({@link #refs}, guarded by the state's lock) and frees the version when the last
 * lets go: the arena is closed and the folder deleted. Reads are stateless, so one version serves every reader thread
 * at once. Column files: {@code <col>.d} fixed width (8 bytes LONG, TIMESTAMP, DOUBLE; 1 byte BOOLEAN; a 4-byte key
 * SYMBOL), VARCHAR {@code <col>.i} (8-byte end offsets, a NULL as the end's complement) and {@code <col>.d} (UTF-8),
 * little-endian. A file may run past what the committed rows need (an append in place that failed leaves bytes after
 * the committed end): the version reads by its row count, never by a file's size.
 * <p>
 * Each read pins the version for its length ({@link #pins}), so a release closes the arena only while no read is in
 * flight, and a read that meets a release waits for it on the version's lock.
 * <p>
 * Each version keeps, per column, what its reads derive once: a {@link ZoneMap} of an 8-byte column, a
 * {@link TextCache} of a VARCHAR column, the last constant a pre-filter compared a text column with. Each is built on
 * first use by whichever reader thread needs it and published through an {@link AtomicReferenceArray}; two threads
 * that race build equal ones, and one wins. They live on the heap and survive a release.
 */
final class Version implements PartitionReader {

    static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfDouble DOUBLE_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle BYTES_AS_LONGS = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);
    /** {@link #pins} while a release closes the arena: no read may pin. */
    private static final int RELEASING = Integer.MIN_VALUE;
    private static final int NULL_UNKNOWN = 0;
    private static final int NULL_NONE = 1;
    private static final int NULL_SOME = 2;

    final Path dir;
    final String folder;
    /** The leading columns of the definition that have files in this version; the rest were added later. */
    final int fileColumns;
    /** Snapshots holding this version; guarded by the owning {@link TableState}'s lock. */
    int refs;

    private final CdbType[] types;
    private final Path[] dataFiles;
    private final Path[] indexFiles;
    private final long[] dataLength;
    private final SymbolTable[] symbols;
    private final Mappings mappings;
    private final long rowCount;
    private final long lowerBound;
    private final long minTimestamp;
    private final long maxTimestamp;
    private final long mappedBytes;
    private final AtomicReferenceArray<Mapped> columns;
    private final AtomicReferenceArray<ZoneMap> zones;
    private final AtomicReferenceArray<TextCache> textCaches;
    private final AtomicReferenceArray<Probe> probes;
    // per column, NULL_UNKNOWN until mayHoldNull first reads it, then NULL_NONE or NULL_SOME; the rows never change
    private final AtomicIntegerArray nulls;
    // reads in flight, RELEASING while a release closes the arena; reader threads raise and lower it, a release swaps
    // it between 0 and RELEASING under the lock
    private final AtomicInteger pins = new AtomicInteger();
    private final Object lock = new Object();
    // written by each reader thread as its read ends, before it lowers pins; read by a release after its swap of pins,
    // so it sees the last read's time
    private volatile long lastReadNanos;
    // guarded by lock
    private Arena arena;
    // guarded by lock: the mappings the arena holds
    private int mappedSegments;
    // guarded by lock: set by unmap, after which no read maps again
    private boolean dropped;

    private Version(
            final Path dir,
            final CdbType[] types,
            final Path[] dataFiles,
            final Path[] indexFiles,
            final long[] dataLength,
            final SymbolTable[] symbols,
            final Mappings mappings,
            final long rowCount,
            final long lowerBound,
            final long minTimestamp,
            final long maxTimestamp,
            final long mappedBytes,
            final int fileColumns
    ) {
        this.dir = dir;
        this.folder = dir.getFileName().toString();
        this.fileColumns = fileColumns;
        this.types = types;
        this.dataFiles = dataFiles;
        this.indexFiles = indexFiles;
        this.dataLength = dataLength;
        this.symbols = symbols;
        this.mappings = mappings;
        this.rowCount = rowCount;
        this.lowerBound = lowerBound;
        this.minTimestamp = minTimestamp;
        this.maxTimestamp = maxTimestamp;
        this.mappedBytes = mappedBytes;
        this.columns = new AtomicReferenceArray<>(types.length);
        this.zones = new AtomicReferenceArray<>(types.length);
        this.textCaches = new AtomicReferenceArray<>(types.length);
        this.probes = new AtomicReferenceArray<>(types.length);
        this.nulls = new AtomicIntegerArray(types.length);
        this.lastReadNanos = System.nanoTime();
    }

    /** {@link #open(Path, TableDef, long, List, Mappings)} counting its mappings in a count of its own. */
    @TestOnly
    static Version open(final Path dir, final TableDef def, final long rowCount,
            final List<SymbolTable> symbolsByColumn) {
        return open(dir, def, rowCount, symbolsByColumn, -1, new Mappings());
    }

    /**
     * Opens the version in {@code dir} of {@code rowCount} rows, mapping nothing: each column maps on its first read,
     * every mapping counted in {@code mappings}. A column of {@code def} with no file there (added later) reads NULL.
     * Columns are only added last, so the columns without a file must be a tail of {@code def}'s, and a partition
     * holding rows has the files of its first column and of its designated timestamp: a folder or a file missing
     * otherwise is refused, not read as NULL. Every file is checked to hold what the row count needs before a byte of
     * it is read, and is read through a slice of that length.
     */
    static Version open(final Path dir, final TableDef def, final long rowCount,
            final List<SymbolTable> symbolsByColumn, final Mappings mappings) {
        return open(dir, def, rowCount, symbolsByColumn, -1, mappings);
    }

    /**
     * {@link #open(Path, TableDef, long, List, Mappings)} of a version known to have the files of its first
     * {@code fileColumns} columns, as {@code _txn} records them (-1 when unknown): one of those missing is refused,
     * the file of a last column included, which the tail rule alone would read as a column added later.
     */
    static Version open(final Path dir, final TableDef def, final long rowCount,
            final List<SymbolTable> symbolsByColumn, final int fileColumns, final Mappings mappings) {
        final List<ColumnDef> columnDefs = def.columns();
        final int n = columnDefs.size();
        final CdbType[] types = new CdbType[n];
        final Path[] dataFiles = new Path[n];
        final Path[] indexFiles = new Path[n];
        final long[] dataLength = new long[n];
        final SymbolTable[] symbols = new SymbolTable[n];
        try {
            if (!Files.isDirectory(dir)) {
                throw new CdbException("refuse missing partition folder [table=" + def.name() + ", partition="
                        + dir.getFileName() + ']', -1);
            }
            long mapped = 0L;
            int firstAbsent = -1;
            for (int c = 0; c < n; c++) {
                final ColumnDef column = columnDefs.get(c);
                types[c] = column.type();
                symbols[c] = symbolsByColumn.get(c);
                final Path d = dir.resolve(column.name() + ".d");
                if (!Files.exists(d)) {
                    if (firstAbsent < 0) {
                        firstAbsent = c;
                    }
                    if (column.type() == CdbType.VARCHAR && Files.exists(dir.resolve(column.name() + ".i"))) {
                        throw missing(def, dir, d.getFileName().toString());
                    }
                    continue;
                }
                if (firstAbsent >= 0) {
                    throw missing(def, dir, columnDefs.get(firstAbsent).name() + ".d");
                }
                dataFiles[c] = d;
                final long dataSize = Files.size(d);
                mapped += dataSize;
                if (column.type() == CdbType.VARCHAR) {
                    final Path i = dir.resolve(column.name() + ".i");
                    final long indexSize = Files.size(i);
                    mapped += indexSize;
                    checkCommitted(i, indexSize, rowCount * Long.BYTES);
                    indexFiles[c] = i;
                    dataLength[c] = rowCount == 0L
                            ? 0L
                            : decodeEnd(StoreFiles.readLong(i, (rowCount - 1L) * Long.BYTES));
                } else {
                    dataLength[c] = switch (column.type()) {
                        case LONG, TIMESTAMP, DOUBLE -> rowCount * Long.BYTES;
                        case BOOLEAN -> rowCount;
                        case SYMBOL -> rowCount * Integer.BYTES;
                        case VARCHAR -> throw new IllegalStateException("unreachable");
                    };
                }
                checkCommitted(d, dataSize, dataLength[c]);
            }
            final int present = firstAbsent < 0 ? n : firstAbsent;
            if (present < Math.min(fileColumns, n)) {
                throw missing(def, dir, columnDefs.get(present).name() + ".d");
            }
            final int ts = def.timestampIndex();
            if (rowCount > 0L) {
                if (dataFiles[0] == null) {
                    throw missing(def, dir, columnDefs.get(0).name() + ".d");
                }
                if (ts >= 0 && dataFiles[ts] == null) {
                    throw missing(def, dir, columnDefs.get(ts).name() + ".d");
                }
            }
            final long min;
            final long max;
            if (ts >= 0 && dataFiles[ts] != null && rowCount > 0L) {
                min = StoreFiles.readLong(dataFiles[ts], 0L);
                max = StoreFiles.readLong(dataFiles[ts], (rowCount - 1L) * Long.BYTES);
            } else {
                min = Long.MIN_VALUE;
                max = Long.MIN_VALUE;
            }
            final long lower = ts >= 0 ? Retention.floor(min, def.partitionBy()) : Long.MIN_VALUE;
            return new Version(dir, types, dataFiles, indexFiles, dataLength, symbols, mappings, rowCount, lower, min,
                    max, mapped, present);
        } catch (final IOException e) {
            throw new CdbException("cannot map partition [dir=" + dir + ']', e);
        }
    }

    /** The bytes this version maps once every column is read, the size of its files. */
    long mappedBytes() {
        return mappedBytes;
    }

    /**
     * Unmaps the files for good; the caller deletes the folder after. Called once, when {@link #refs} reaches 0, so
     * no read is in flight; a read after it is refused.
     */
    void unmap() {
        synchronized (lock) {
            dropped = true;
            closeArena();
        }
    }

    /**
     * Unmaps every mapped column when no read used the version for {@code idleNanos} up to {@code nowNanos} (both
     * {@link System#nanoTime()}) and none is in flight; the next read maps again. True when it unmapped. Safe beside
     * reads on any thread: a read that starts during the release waits for it.
     */
    boolean releaseIfIdle(final long nowNanos, final long idleNanos) {
        if (nowNanos - lastReadNanos < idleNanos) {
            return false;
        }
        synchronized (lock) {
            if (arena == null || !pins.compareAndSet(0, RELEASING)) {
                return false;
            }
            try {
                // read after the swap: every read that ended before it wrote its time first
                if (nowNanos - lastReadNanos < idleNanos) {
                    return false;
                }
                closeArena();
                return true;
            } finally {
                pins.set(0);
            }
        }
    }

    @Override
    public long lowerBound() {
        return lowerBound;
    }

    @Override
    public long minTimestamp() {
        return minTimestamp;
    }

    @Override
    public long maxTimestamp() {
        return maxTimestamp;
    }

    @Override
    public long rowCount() {
        return rowCount;
    }

    /**
     * The committed bytes of column {@code column}'s data file: for VARCHAR, its rows' UTF-8 text; 0 for a column
     * added after this version was written.
     */
    long dataBytes(final int column) {
        return column < dataFiles.length && dataFiles[column] != null ? dataLength[column] : 0L;
    }

    @Override
    public void longs(final int column, final long from, final int n, final long[] dst) {
        final Mapped m = acquire(column, CdbType.LONG, CdbType.TIMESTAMP);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, n, Long.MIN_VALUE);
                return;
            }
            MemorySegment.copy(m.data(), LONG_LE, from * Long.BYTES, dst, 0, n);
        } finally {
            release();
        }
    }

    @Override
    public void doubles(final int column, final long from, final int n, final double[] dst) {
        final Mapped m = acquire(column, CdbType.DOUBLE, CdbType.DOUBLE);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, n, Double.NaN);
                return;
            }
            MemorySegment.copy(m.data(), DOUBLE_LE, from * Double.BYTES, dst, 0, n);
        } finally {
            release();
        }
    }

    @Override
    public void booleans(final int column, final long from, final int n, final boolean[] dst) {
        final Mapped m = acquire(column, CdbType.BOOLEAN, CdbType.BOOLEAN);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, n, false);
                return;
            }
            final MemorySegment segment = m.data();
            // a loop, not MemorySegment.copy: the copy takes no boolean[] (JDK 25: "Not a supported array class")
            for (int i = 0; i < n; i++) {
                dst[i] = segment.get(ValueLayout.JAVA_BYTE, from + i) != 0;
            }
        } finally {
            release();
        }
    }

    /**
     * A SYMBOL column is read once for a negative key, the answer kept for the life of the version (4 bytes a row,
     * sequential); any other column answers true.
     */
    @Override
    public boolean mayHoldNull(final int column) {
        if (column >= types.length || types[column] != CdbType.SYMBOL || rowCount == 0L) {
            return rowCount != 0L;
        }
        final int known = nulls.get(column);
        if (known != NULL_UNKNOWN) {
            return known == NULL_SOME;
        }
        final Mapped m = acquire(column, CdbType.SYMBOL, CdbType.VARCHAR);
        try {
            boolean some = m == null;
            if (!some) {
                final MemorySegment segment = m.data();
                final long end = rowCount * Integer.BYTES;
                for (long at = 0L; at < end && !some; at += Integer.BYTES) {
                    some = segment.get(INT_LE, at) < 0;
                }
            }
            nulls.set(column, some ? NULL_SOME : NULL_NONE);
            return some;
        } finally {
            release();
        }
    }

    @Override
    public void texts(final int column, final long from, final int n, final String[] dst) {
        final Mapped m = acquire(column, CdbType.SYMBOL, CdbType.VARCHAR);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, n, null);
                return;
            }
            final MemorySegment segment = m.data();
            if (types[column] == CdbType.SYMBOL) {
                final String[] values = symbols[column].values();
                long at = from * Integer.BYTES;
                for (int i = 0; i < n; i++, at += Integer.BYTES) {
                    final int key = segment.get(INT_LE, at);
                    dst[i] = key < 0 ? null : values[key];
                }
                return;
            }
            final MemorySegment ends = m.index();
            final TextCache cache = textCache(column);
            long start = from == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (from - 1L) * Long.BYTES));
            for (int i = 0; i < n; i++) {
                final long raw = ends.get(LONG_LE, (from + i) * Long.BYTES);
                final long end = checkedEnd(column, from + i, raw, start, segment);
                dst[i] = raw < 0L ? null : cache.text(segment, start, (int) (end - start));
                start = end;
            }
        } finally {
            release();
        }
    }

    /**
     * Reads the mapped segments straight into {@code dst}: a VARCHAR as its UTF-8 bytes, a SYMBOL as its key of the
     * table's dictionary; no String is made.
     */
    @Override
    public void read(final int column, final long from, final int n, final Vector dst, final int at) {
        final Mapped m = acquire(column, dst.type);
        try {
            if (m == null) {
                for (int i = 0; i < n; i++) {
                    dst.setNull(at + i);
                }
                return;
            }
            final MemorySegment segment = m.data();
            switch (types[column]) {
                case LONG, TIMESTAMP -> {
                    for (int i = 0; i < n; i++) {
                        dst.setLong(at + i, segment.get(LONG_LE, (from + i) * Long.BYTES));
                    }
                }
                case DOUBLE -> {
                    for (int i = 0; i < n; i++) {
                        dst.setDouble(at + i, segment.get(DOUBLE_LE, (from + i) * Double.BYTES));
                    }
                }
                case BOOLEAN -> {
                    for (int i = 0; i < n; i++) {
                        dst.setBoolean(at + i, segment.get(ValueLayout.JAVA_BYTE, from + i) != 0);
                    }
                }
                case SYMBOL -> {
                    final SymbolTable dictionary = symbols[column];
                    for (int i = 0; i < n; i++) {
                        setSymbol(dst, at + i, dictionary, segment.get(INT_LE, (from + i) * Integer.BYTES));
                    }
                }
                case VARCHAR -> {
                    final MemorySegment ends = m.index();
                    long start = from == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (from - 1L) * Long.BYTES));
                    for (int i = 0; i < n; i++) {
                        final long raw = ends.get(LONG_LE, (from + i) * Long.BYTES);
                        final long end = checkedEnd(column, from + i, raw, start, segment);
                        setText(dst, at + i, segment, raw, start, end);
                        start = end;
                    }
                }
            }
        } finally {
            release();
        }
    }

    /** {@link #read} of the rows {@code rows[from + k]}, each read where it lies. */
    @Override
    public void fetch(final int column, final long[] rows, final int from, final int n, final Vector dst,
            final int at) {
        final Mapped m = acquire(column, dst.type);
        try {
            if (m == null) {
                for (int k = 0; k < n; k++) {
                    dst.setNull(at + k);
                }
                return;
            }
            final MemorySegment segment = m.data();
            switch (types[column]) {
                case LONG, TIMESTAMP -> {
                    for (int k = 0; k < n; k++) {
                        dst.setLong(at + k, segment.get(LONG_LE, rows[from + k] * Long.BYTES));
                    }
                }
                case DOUBLE -> {
                    for (int k = 0; k < n; k++) {
                        dst.setDouble(at + k, segment.get(DOUBLE_LE, rows[from + k] * Double.BYTES));
                    }
                }
                case BOOLEAN -> {
                    for (int k = 0; k < n; k++) {
                        dst.setBoolean(at + k, segment.get(ValueLayout.JAVA_BYTE, rows[from + k]) != 0);
                    }
                }
                case SYMBOL -> {
                    final SymbolTable dictionary = symbols[column];
                    for (int k = 0; k < n; k++) {
                        setSymbol(dst, at + k, dictionary, segment.get(INT_LE, rows[from + k] * Integer.BYTES));
                    }
                }
                case VARCHAR -> {
                    final MemorySegment ends = m.index();
                    for (int k = 0; k < n; k++) {
                        final long row = rows[from + k];
                        final long start = row == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (row - 1L) * Long.BYTES));
                        final long raw = ends.get(LONG_LE, row * Long.BYTES);
                        final long end = checkedEnd(column, row, raw, start, segment);
                        setText(dst, at + k, segment, raw, start, end);
                    }
                }
            }
        } finally {
            release();
        }
    }

    @Override
    public long rowAtOrAfter(final int timestampColumn, final long micros) {
        final Mapped m = acquire(timestampColumn, CdbType.LONG, CdbType.TIMESTAMP);
        try {
            if (m == null) {
                // every row reads NULL, Long.MIN_VALUE, the smallest timestamp there is
                return micros > Long.MIN_VALUE ? rowCount : 0L;
            }
            final MemorySegment segment = m.data();
            long lo = 0L;
            long hi = rowCount;
            while (lo < hi) {
                final long mid = (lo + hi) >>> 1;
                if (segment.get(LONG_LE, mid * Long.BYTES) < micros) {
                    lo = mid + 1L;
                } else {
                    hi = mid;
                }
            }
            return lo;
        } finally {
            release();
        }
    }

    @Override
    public int selectLong(final int column, final long from, final int n, final Compare op, final long constant,
            final int[] sel) {
        final Mapped m = acquire(column, CdbType.LONG, CdbType.TIMESTAMP);
        try {
            if (m == null) {
                return selectAll(n, sel);
            }
            return select(m.data(), zoneMap(column, m.data(), false), false, from, n, op, constant, sel);
        } finally {
            release();
        }
    }

    @Override
    public int selectDouble(final int column, final long from, final int n, final Compare op, final double constant,
            final int[] sel) {
        final Mapped m = acquire(column, CdbType.DOUBLE, CdbType.DOUBLE);
        try {
            if (m == null) {
                return selectAll(n, sel);
            }
            // Double.compare puts NaN above every other value: its key goes above every key a value that is not NaN has
            final long key = Double.isNaN(constant)
                    ? Long.MAX_VALUE
                    : ZoneMap.doubleKey(Double.doubleToRawLongBits(constant));
            return select(m.data(), zoneMap(column, m.data(), true), true, from, n, op, key, sel);
        } finally {
            release();
        }
    }

    @Override
    public int selectText(final int column, final long from, final int n, final Compare op, final String constant,
            final int[] sel) {
        final Mapped m = acquire(column, CdbType.SYMBOL, CdbType.VARCHAR);
        try {
            if (m == null) {
                // a column added after this partition was written: every row NULL, kept only by !=
                return op.holds(1) ? selectAll(n, sel) : 0;
            }
            final MemorySegment segment = m.data();
            final boolean keepEqual = op.holds(0);
            final boolean keepOther = op.holds(1);
            final Probe probe = probe(column, constant);
            int count = 0;
            if (types[column] == CdbType.SYMBOL) {
                // a NULL row (key below 0) is never equal; a constant the dictionary lacks (key -1) equals no row
                final int key = probe.key();
                long at = from * Integer.BYTES;
                for (int i = 0; i < n; i++, at += Integer.BYTES) {
                    final int k = segment.get(INT_LE, at);
                    sel[count] = i;
                    count += (k >= 0 && k == key ? keepEqual : keepOther) ? 1 : 0;
                }
                return count;
            }
            final byte[] utf8 = probe.utf8();
            final long length = utf8 == null ? -1L : utf8.length;
            final MemorySegment ends = m.index();
            long start = from == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (from - 1L) * Long.BYTES));
            for (int i = 0; i < n; i++) {
                final long raw = ends.get(LONG_LE, (from + i) * Long.BYTES);
                final long end = checkedEnd(column, from + i, raw, start, segment);
                final boolean equal = end - start == length && equalBytes(segment, start, utf8, probe.tail());
                sel[count] = i;
                count += (raw >= 0L && equal ? keepEqual : keepOther) ? 1 : 0;
                start = end;
            }
            return count;
        } finally {
            release();
        }
    }

    @Override
    public void longsAt(final int column, final long from, final int[] sel, final int count, final long[] dst) {
        final Mapped m = acquire(column, CdbType.LONG, CdbType.TIMESTAMP);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, count, Long.MIN_VALUE);
                return;
            }
            final MemorySegment segment = m.data();
            for (int i = 0; i < count; i++) {
                dst[i] = segment.get(LONG_LE, (from + sel[i]) * Long.BYTES);
            }
        } finally {
            release();
        }
    }

    @Override
    public void doublesAt(final int column, final long from, final int[] sel, final int count, final double[] dst) {
        final Mapped m = acquire(column, CdbType.DOUBLE, CdbType.DOUBLE);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, count, Double.NaN);
                return;
            }
            final MemorySegment segment = m.data();
            for (int i = 0; i < count; i++) {
                dst[i] = segment.get(DOUBLE_LE, (from + sel[i]) * Double.BYTES);
            }
        } finally {
            release();
        }
    }

    @Override
    public void booleansAt(final int column, final long from, final int[] sel, final int count,
            final boolean[] dst) {
        final Mapped m = acquire(column, CdbType.BOOLEAN, CdbType.BOOLEAN);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, count, false);
                return;
            }
            final MemorySegment segment = m.data();
            for (int i = 0; i < count; i++) {
                dst[i] = segment.get(ValueLayout.JAVA_BYTE, from + sel[i]) != 0;
            }
        } finally {
            release();
        }
    }

    @Override
    public void textsAt(final int column, final long from, final int[] sel, final int count, final String[] dst) {
        final Mapped m = acquire(column, CdbType.SYMBOL, CdbType.VARCHAR);
        try {
            if (m == null) {
                Arrays.fill(dst, 0, count, null);
                return;
            }
            final MemorySegment segment = m.data();
            if (types[column] == CdbType.SYMBOL) {
                final String[] values = symbols[column].values();
                for (int i = 0; i < count; i++) {
                    final int key = segment.get(INT_LE, (from + sel[i]) * Integer.BYTES);
                    dst[i] = key < 0 ? null : values[key];
                }
                return;
            }
            final MemorySegment ends = m.index();
            final TextCache cache = textCache(column);
            for (int i = 0; i < count; i++) {
                final long row = from + sel[i];
                final long start = row == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (row - 1L) * Long.BYTES));
                final long raw = ends.get(LONG_LE, row * Long.BYTES);
                final long end = checkedEnd(column, row, raw, start, segment);
                dst[i] = raw < 0L ? null : cache.text(segment, start, (int) (end - start));
            }
        } finally {
            release();
        }
    }

    /** The end offset an index entry holds: a NULL's entry is the complement of its end, so negative. */
    static long decodeEnd(final long entry) {
        return entry < 0L ? ~entry : entry;
    }

    private static void setSymbol(final Vector dst, final int i, final SymbolTable dictionary, final int key) {
        if (key < 0) {
            dst.setNull(i);
        } else {
            dst.setSymbol(i, dictionary, key);
        }
    }

    private static void setText(final Vector dst, final int i, final MemorySegment segment, final long raw,
            final long start, final long end) {
        if (raw < 0L) {
            dst.setNull(i);
        } else {
            dst.setText(i, segment, start, (int) (end - start));
        }
    }

    /**
     * The pre-filter of an 8-byte column over its keys (a DOUBLE's by {@link ZoneMap#doubleKey}): a block its zone map
     * rules out is skipped, a block it rules in kept whole, and the rows of any other block compared one by one.
     */
    private static int select(final MemorySegment segment, final ZoneMap zones, final boolean doubles,
            final long from, final int n, final Compare op, final long key, final int[] sel) {
        // the keys op keeps as one range [low, high], empty when low > high; NE keeps the keys outside [key, key]
        final long low = switch (op) {
            case EQ, NE, GE -> key;
            case LT, LE -> Long.MIN_VALUE;
            case GT -> key == Long.MAX_VALUE ? Long.MAX_VALUE : key + 1L;
        };
        final long high = switch (op) {
            case EQ, NE, LE -> key;
            case LT -> key == Long.MIN_VALUE ? Long.MIN_VALUE : key - 1L;
            case GT, GE -> Long.MAX_VALUE;
        };
        final boolean empty = op == Compare.LT && key == Long.MIN_VALUE || op == Compare.GT && key == Long.MAX_VALUE;
        final long lo = empty ? Long.MAX_VALUE : low;
        final long hi = empty ? Long.MIN_VALUE : high;
        final boolean outside = op == Compare.NE;
        final long to = from + n;
        int count = 0;
        long row = from;
        while (row < to) {
            final int block = (int) (row >>> ZoneMap.BLOCK_SHIFT);
            final long blockEnd = Math.min(to, (long) (block + 1) << ZoneMap.BLOCK_SHIFT);
            switch (zones.verdict(block, lo, hi, outside)) {
                case NONE -> {
                }
                case ALL -> {
                    for (long r = row; r < blockEnd; r++) {
                        sel[count++] = (int) (r - from);
                    }
                }
                case SOME -> count = doubles
                        ? scanDoubles(segment, from, row, blockEnd, lo, hi, outside, sel, count)
                        : scanLongs(segment, from, row, blockEnd, lo, hi, outside, sel, count);
            }
            row = blockEnd;
        }
        return count;
    }

    private static int scanLongs(final MemorySegment segment, final long from, final long row, final long end,
            final long lo, final long hi, final boolean outside, final int[] sel, final int count) {
        int kept = count;
        for (long r = row; r < end; r++) {
            final long v = segment.get(LONG_LE, r * Long.BYTES);
            sel[kept] = (int) (r - from);
            kept += v == Long.MIN_VALUE || ((v >= lo && v <= hi) != outside) ? 1 : 0;
        }
        return kept;
    }

    private static int scanDoubles(final MemorySegment segment, final long from, final long row, final long end,
            final long lo, final long hi, final boolean outside, final int[] sel, final int count) {
        int kept = count;
        for (long r = row; r < end; r++) {
            final long bits = segment.get(LONG_LE, r * Long.BYTES);
            final long k = ZoneMap.doubleKey(bits);
            sel[kept] = (int) (r - from);
            kept += ZoneMap.isNaN(bits) || ((k >= lo && k <= hi) != outside) ? 1 : 0;
        }
        return kept;
    }

    private static int selectAll(final int n, final int[] sel) {
        for (int i = 0; i < n; i++) {
            sel[i] = i;
        }
        return n;
    }

    private ZoneMap zoneMap(final int column, final MemorySegment segment, final boolean doubles) {
        final ZoneMap zoneMap = zones.get(column);
        if (zoneMap != null) {
            return zoneMap;
        }
        final ZoneMap built = ZoneMap.of(segment, rowCount, doubles);
        return zones.compareAndSet(column, null, built) ? built : zones.get(column);
    }

    private TextCache textCache(final int column) {
        final TextCache cache = textCaches.get(column);
        if (cache != null) {
            return cache;
        }
        final TextCache fresh = new TextCache();
        return textCaches.compareAndSet(column, null, fresh) ? fresh : textCaches.get(column);
    }

    /** The constant of a text pre-filter, resolved for {@code column}: kept until the next constant replaces it. */
    private Probe probe(final int column, final String constant) {
        final Probe last = probes.get(column);
        if (last != null && Objects.equals(last.constant(), constant)) {
            return last;
        }
        final Probe probe = types[column] == CdbType.SYMBOL
                ? new Probe(constant, symbols[column].keyOf(constant), null, 0L)
                : probeText(constant);
        probes.set(column, probe);
        return probe;
    }

    /** The end of the text at {@code row}, checked to lie between its start and the end of the data. */
    private long checkedEnd(final int column, final long row, final long raw, final long start,
            final MemorySegment segment) {
        final long end = decodeEnd(raw);
        if (end < start || end > segment.byteSize()) {
            throw new CdbException("refuse corrupt text offsets [dir=" + dir + ", column=" + column + ", row=" + row
                    + ']', -1);
        }
        return end;
    }

    /** {@link #acquire(int, CdbType, CdbType)} of a vector of {@code type}: the column types its read accepts. */
    private Mapped acquire(final int column, final CdbType type) {
        return switch (type) {
            case LONG, TIMESTAMP -> acquire(column, CdbType.LONG, CdbType.TIMESTAMP);
            case DOUBLE, BOOLEAN -> acquire(column, type, type);
            case SYMBOL, VARCHAR -> acquire(column, CdbType.SYMBOL, CdbType.VARCHAR);
        };
    }

    /**
     * Pins the version and returns {@code column}'s mapping, mapped now when it is not; null for a column with no file
     * in this version. The caller calls {@link #release()} in a {@code finally} on every return; a throw leaves no pin.
     */
    private Mapped acquire(final int column, final CdbType a, final CdbType b) {
        if (column < types.length) {
            final CdbType type = types[column];
            if (type != a && type != b) {
                throw new IllegalArgumentException("refuse read of column as another type [column=" + column
                        + ", type=" + type + ", asked=" + a + ']');
            }
        }
        pin();
        if (column >= types.length || dataFiles[column] == null) {
            return null;
        }
        try {
            final Mapped m = columns.get(column);
            return m != null ? m : map(column);
        } catch (final RuntimeException | Error e) {
            release();
            throw e;
        }
    }

    private void pin() {
        while (true) {
            final int p = pins.get();
            if (p >= 0) {
                if (pins.compareAndSet(p, p + 1)) {
                    return;
                }
            } else {
                // a release holds the lock while it closes the arena: wait for it there, then pin again
                synchronized (lock) {
                    assert pins.get() >= 0;
                }
            }
        }
    }

    private void release() {
        // the time before the pin drops: a release that sees the drop sees the time
        lastReadNanos = System.nanoTime();
        pins.decrementAndGet();
    }

    /** Maps {@code column}'s files into the arena, under the lock; a column another reader mapped first is returned. */
    private Mapped map(final int column) {
        synchronized (lock) {
            final Mapped mapped = columns.get(column);
            if (mapped != null) {
                return mapped;
            }
            if (dropped) {
                throw new IllegalStateException("refuse read of an unmapped version [dir=" + dir + ']');
            }
            if (arena == null) {
                arena = Arena.ofShared();
            }
            try {
                final MemorySegment index = types[column] == CdbType.VARCHAR
                        ? committed(indexFiles[column], mapFile(indexFiles[column]), rowCount * Long.BYTES)
                        : null;
                final MemorySegment data = committed(dataFiles[column], mapFile(dataFiles[column]),
                        dataLength[column]);
                final Mapped fresh = new Mapped(data, index);
                columns.set(column, fresh);
                return fresh;
            } catch (final IOException e) {
                throw new CdbException("cannot map partition [dir=" + dir + ", column=" + column + ']', e);
            }
        }
    }

    /** Maps {@code file} into the arena, counting the mapping; an empty file maps nothing. Under the lock. */
    private MemorySegment mapFile(final Path file) throws IOException {
        final MemorySegment segment = StoreFiles.map(file, arena);
        if (segment.byteSize() > 0L) {
            mappedSegments++;
            mappings.mapped(1);
        }
        return segment;
    }

    /** Detaches every column's mapping, then closes the arena and reports its mappings released. Under the lock. */
    private void closeArena() {
        final Arena closing = arena;
        if (closing == null) {
            return;
        }
        for (int c = 0; c < types.length; c++) {
            columns.set(c, null);
        }
        arena = null;
        final int released = mappedSegments;
        mappedSegments = 0;
        try {
            closing.close();
        } finally {
            mappings.unmapped(released);
        }
    }

    private static CdbException missing(final TableDef def, final Path dir, final String file) {
        return new CdbException("refuse partition with a missing file [table=" + def.name() + ", partition="
                + dir.getFileName() + ", file=" + file + ']', -1);
    }

    /** Refuses {@code file} of {@code size} bytes when shorter than the {@code bytes} its committed rows need. */
    private static void checkCommitted(final Path file, final long size, final long bytes) {
        if (size < bytes) {
            throw new CdbException("refuse partition file shorter than its rows [file=" + file + ", bytes=" + size
                    + ", expected=" + bytes + ']', -1);
        }
    }

    /**
     * The first {@code bytes} of {@code segment}, mapped from {@code file}: a file shorter than its committed rows need
     * is refused, the bytes past them are not read.
     */
    private static MemorySegment committed(final Path file, final MemorySegment segment, final long bytes) {
        checkCommitted(file, segment.byteSize(), bytes);
        return segment.byteSize() == bytes ? segment : segment.asSlice(0L, bytes);
    }

    /**
     * A text pre-filter's constant resolved for one column: a SYMBOL's dictionary key (-1 when absent), a VARCHAR's
     * UTF-8 bytes (null for a null constant).
     */
    private static Probe probeText(final String constant) {
        if (constant == null) {
            return new Probe(null, -1, null, 0L);
        }
        final byte[] utf8 = constant.getBytes(StandardCharsets.UTF_8);
        return new Probe(constant, -1, utf8, tailWord(utf8));
    }

    /** One column's committed bytes, mapped: its data and, for VARCHAR, its end offsets. */
    private record Mapped(MemorySegment data, MemorySegment index) {
    }

    /** A constant to compare a column with: its dictionary key (SYMBOL), or its UTF-8 bytes and their tail word. */
    private record Probe(String constant, int key, byte[] utf8, long tail) {
    }

    /** {@link #equalBytes(MemorySegment, long, byte[], long)} with the tail word computed here; for tests and one-offs. */
    static boolean equalBytes(final MemorySegment data, final long start, final byte[] bytes) {
        return equalBytes(data, start, bytes, tailWord(bytes));
    }

    /**
     * Whether the {@code bytes.length} bytes of {@code data} at {@code start} equal {@code bytes}: eight at a time, the
     * last one to seven against {@code tail} ({@link #tailWord}) in one masked word where the data runs on far enough,
     * else one at a time. A column's texts are short, and {@code MemorySegment.mismatch}'s set-up per call cost more
     * than the compare: 858 of 2,702 execution samples over the 2 M-row bench's text filters (M2 Max), before this.
     */
    static boolean equalBytes(final MemorySegment data, final long start, final byte[] bytes, final long tail) {
        final int length = bytes.length;
        int i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            if (data.get(LONG_LE, start + i) != (long) BYTES_AS_LONGS.get(bytes, i)) {
                return false;
            }
        }
        if (i == length) {
            return true;
        }
        if (start + i + Long.BYTES <= data.byteSize()) {
            final long mask = -1L >>> (Long.SIZE - (length - i) * Byte.SIZE);
            return ((data.get(LONG_LE, start + i) ^ tail) & mask) == 0L;
        }
        for (; i < length; i++) {
            if (data.get(ValueLayout.JAVA_BYTE, start + i) != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    /** The bytes after the last whole eight of {@code bytes}, little-endian in one word; 0 when there are none. */
    static long tailWord(final byte[] bytes) {
        long tail = 0L;
        for (int j = bytes.length - 1; j >= bytes.length / Long.BYTES * Long.BYTES; j--) {
            tail = tail << Byte.SIZE | bytes[j] & 0xFFL;
        }
        return tail;
    }
}
