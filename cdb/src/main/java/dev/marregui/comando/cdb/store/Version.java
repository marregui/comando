package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * One version folder of a partition, mapped: its column files live in one shared arena for as long as a snapshot holds
 * the version. The table's state counts the holders ({@link #refs}, guarded by the state's lock) and frees the version
 * when the last lets go: the arena is closed and the folder deleted. Reads are stateless, so one version serves
 * every reader thread at once. Column files: {@code <col>.d} fixed width (8 bytes LONG, TIMESTAMP, DOUBLE; 1 byte
 * BOOLEAN; a 4-byte key SYMBOL), VARCHAR {@code <col>.i} (8-byte end offsets, a NULL as the end's complement) and
 * {@code <col>.d} (UTF-8), little-endian.
 */
final class Version implements PartitionReader {

    static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfDouble DOUBLE_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    final Path dir;
    final String folder;
    /** Snapshots holding this version; guarded by the owning {@link TableState}'s lock. */
    int refs;
    /**
     * Named by {@code _txn} when the table closed, so its folder outlives the last holder; guarded by the owning
     * {@link TableState}'s lock.
     */
    boolean committed;

    private final Arena arena;
    private final CdbType[] types;
    private final MemorySegment[] data;
    private final MemorySegment[] index;
    private final SymbolTable[] symbols;
    private final long rowCount;
    private final long lowerBound;
    private final long minTimestamp;
    private final long maxTimestamp;
    private final long mappedBytes;

    private Version(
            final Path dir,
            final Arena arena,
            final CdbType[] types,
            final MemorySegment[] data,
            final MemorySegment[] index,
            final SymbolTable[] symbols,
            final long rowCount,
            final long lowerBound,
            final long minTimestamp,
            final long maxTimestamp,
            final long mappedBytes
    ) {
        this.dir = dir;
        this.folder = dir.getFileName().toString();
        this.arena = arena;
        this.types = types;
        this.data = data;
        this.index = index;
        this.symbols = symbols;
        this.rowCount = rowCount;
        this.lowerBound = lowerBound;
        this.minTimestamp = minTimestamp;
        this.maxTimestamp = maxTimestamp;
        this.mappedBytes = mappedBytes;
    }

    /**
     * Maps the version in {@code dir} of {@code rowCount} rows; a column of {@code def} with no file there (added
     * later) reads NULL. Every file's size is checked against the row count before a byte of it is read.
     */
    static Version open(final Path dir, final TableDef def, final long rowCount,
            final List<SymbolTable> symbolsByColumn) {
        final List<ColumnDef> columns = def.columns();
        final int n = columns.size();
        final CdbType[] types = new CdbType[n];
        final MemorySegment[] data = new MemorySegment[n];
        final MemorySegment[] index = new MemorySegment[n];
        final SymbolTable[] symbols = new SymbolTable[n];
        final Arena arena = Arena.ofShared();
        try {
            long mapped = 0L;
            for (int c = 0; c < n; c++) {
                final ColumnDef column = columns.get(c);
                types[c] = column.type();
                symbols[c] = symbolsByColumn.get(c);
                final Path d = dir.resolve(column.name() + ".d");
                if (!Files.exists(d)) {
                    continue;
                }
                data[c] = StoreFiles.map(d, arena);
                mapped += data[c].byteSize();
                final long expected = switch (column.type()) {
                    case LONG, TIMESTAMP, DOUBLE -> rowCount * Long.BYTES;
                    case BOOLEAN -> rowCount;
                    case SYMBOL -> rowCount * Integer.BYTES;
                    case VARCHAR -> -1L;
                };
                if (column.type() == CdbType.VARCHAR) {
                    final Path i = dir.resolve(column.name() + ".i");
                    index[c] = StoreFiles.map(i, arena);
                    mapped += index[c].byteSize();
                    checkSize(i, index[c], rowCount * Long.BYTES);
                    final long end = rowCount == 0L
                            ? 0L
                            : decodeEnd(index[c].get(LONG_LE, (rowCount - 1L) * Long.BYTES));
                    checkSize(d, data[c], end);
                } else {
                    checkSize(d, data[c], expected);
                }
            }
            final int ts = def.timestampIndex();
            final long min;
            final long max;
            if (ts >= 0 && data[ts] != null && rowCount > 0L) {
                min = data[ts].get(LONG_LE, 0L);
                max = data[ts].get(LONG_LE, (rowCount - 1L) * Long.BYTES);
            } else {
                min = Long.MIN_VALUE;
                max = Long.MIN_VALUE;
            }
            final long lower = ts >= 0 ? Retention.floor(min, def.partitionBy()) : Long.MIN_VALUE;
            return new Version(dir, arena, types, data, index, symbols, rowCount, lower, min, max, mapped);
        } catch (final IOException | RuntimeException e) {
            arena.close();
            if (e instanceof final CdbException cdb) {
                throw cdb;
            }
            throw new CdbException("cannot map partition [dir=" + dir + ']', e);
        }
    }

    /** The bytes this version maps, the size of its arena. */
    long mappedBytes() {
        return mappedBytes;
    }

    /** Unmaps the files; the caller deletes the folder after. Called once, when {@link #refs} reaches 0. */
    void unmap() {
        arena.close();
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

    @Override
    public void longs(final int column, final long from, final int n, final long[] dst) {
        final MemorySegment segment = segment(column, CdbType.LONG, CdbType.TIMESTAMP);
        if (segment == null) {
            Arrays.fill(dst, 0, n, Long.MIN_VALUE);
            return;
        }
        long at = from * Long.BYTES;
        for (int i = 0; i < n; i++, at += Long.BYTES) {
            dst[i] = segment.get(LONG_LE, at);
        }
    }

    @Override
    public void doubles(final int column, final long from, final int n, final double[] dst) {
        final MemorySegment segment = segment(column, CdbType.DOUBLE, CdbType.DOUBLE);
        if (segment == null) {
            Arrays.fill(dst, 0, n, Double.NaN);
            return;
        }
        long at = from * Double.BYTES;
        for (int i = 0; i < n; i++, at += Double.BYTES) {
            dst[i] = segment.get(DOUBLE_LE, at);
        }
    }

    @Override
    public void booleans(final int column, final long from, final int n, final boolean[] dst) {
        final MemorySegment segment = segment(column, CdbType.BOOLEAN, CdbType.BOOLEAN);
        if (segment == null) {
            Arrays.fill(dst, 0, n, false);
            return;
        }
        for (int i = 0; i < n; i++) {
            dst[i] = segment.get(ValueLayout.JAVA_BYTE, from + i) != 0;
        }
    }

    @Override
    public void texts(final int column, final long from, final int n, final String[] dst) {
        final MemorySegment segment = segment(column, CdbType.SYMBOL, CdbType.VARCHAR);
        if (segment == null) {
            Arrays.fill(dst, 0, n, null);
            return;
        }
        if (types[column] == CdbType.SYMBOL) {
            final SymbolTable symbolTable = symbols[column];
            long at = from * Integer.BYTES;
            for (int i = 0; i < n; i++, at += Integer.BYTES) {
                dst[i] = symbolTable.value(segment.get(INT_LE, at));
            }
            return;
        }
        final MemorySegment ends = index[column];
        long start = from == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (from - 1L) * Long.BYTES));
        byte[] scratch = new byte[64];
        for (int i = 0; i < n; i++) {
            final long raw = ends.get(LONG_LE, (from + i) * Long.BYTES);
            final long end = decodeEnd(raw);
            if (end < start || end > segment.byteSize()) {
                throw new CdbException("refuse corrupt text offsets [dir=" + dir + ", column=" + column + ", row="
                        + (from + i) + ']', -1);
            }
            if (raw < 0L) {
                dst[i] = null;
            } else {
                final int length = (int) (end - start);
                if (scratch.length < length) {
                    scratch = new byte[Math.max(length, scratch.length * 2)];
                }
                for (int b = 0; b < length; b++) {
                    scratch[b] = segment.get(ValueLayout.JAVA_BYTE, start + b);
                }
                dst[i] = new String(scratch, 0, length, StandardCharsets.UTF_8);
            }
            start = end;
        }
    }

    /** The end offset an index entry holds: a NULL's entry is the complement of its end, so negative. */
    static long decodeEnd(final long entry) {
        return entry < 0L ? ~entry : entry;
    }

    private MemorySegment segment(final int column, final CdbType a, final CdbType b) {
        if (column >= types.length) {
            return null;
        }
        final CdbType type = types[column];
        if (type != a && type != b) {
            throw new IllegalArgumentException("refuse read of column as another type [column=" + column + ", type="
                    + type + ", asked=" + a + ']');
        }
        return data[column];
    }

    private static void checkSize(final Path file, final MemorySegment segment, final long expected) {
        if (segment.byteSize() != expected) {
            throw new CdbException("refuse partition file of wrong size [file=" + file + ", bytes=" + segment.byteSize()
                    + ", expected=" + expected + ']', -1);
        }
    }
}
