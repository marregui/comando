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
import java.util.concurrent.atomic.AtomicReferenceArray;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * One version folder of a partition, mapped: its column files live in one shared arena for as long as a snapshot holds
 * the version. The table's state counts the holders ({@link #refs}, guarded by the state's lock) and frees the version
 * when the last lets go: the arena is closed and the folder deleted. Reads are stateless, so one version serves
 * every reader thread at once. Column files: {@code <col>.d} fixed width (8 bytes LONG, TIMESTAMP, DOUBLE; 1 byte
 * BOOLEAN; a 4-byte key SYMBOL), VARCHAR {@code <col>.i} (8-byte end offsets, a NULL as the end's complement) and
 * {@code <col>.d} (UTF-8), little-endian. A file may run past what the committed rows need (an append in place that
 * failed leaves bytes after the committed end): the version reads by its row count, never by a file's size.
 * <p>
 * Each version keeps, per column, what its reads derive once: a {@link ZoneMap} of an 8-byte column, a
 * {@link TextCache} of a VARCHAR column, the last constant a pre-filter compared a text column with. Each is built on
 * first use by whichever reader thread needs it and published through an {@link AtomicReferenceArray}; two threads
 * that race build equal ones, and one wins.
 */
final class Version implements PartitionReader {

    static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfDouble DOUBLE_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle BYTES_AS_LONGS = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);

    final Path dir;
    final String folder;
    /** The leading columns of the definition that have files in this version; the rest were added later. */
    final int fileColumns;
    /** Snapshots holding this version; guarded by the owning {@link TableState}'s lock. */
    int refs;

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
    private final AtomicReferenceArray<ZoneMap> zones;
    private final AtomicReferenceArray<TextCache> texts;
    private final AtomicReferenceArray<Probe> probes;

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
            final long mappedBytes,
            final int fileColumns
    ) {
        this.dir = dir;
        this.folder = dir.getFileName().toString();
        this.fileColumns = fileColumns;
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
        this.zones = new AtomicReferenceArray<>(types.length);
        this.texts = new AtomicReferenceArray<>(types.length);
        this.probes = new AtomicReferenceArray<>(types.length);
    }

    /**
     * Maps the version in {@code dir} of {@code rowCount} rows; a column of {@code def} with no file there (added
     * later) reads NULL. Columns are only added last, so the columns without a file must be a tail of {@code def}'s,
     * and a partition holding rows has the files of its first column and of its designated timestamp: a folder or a
     * file missing otherwise is refused, not read as NULL. Every file is checked to hold what the row count needs
     * before a byte of it is read, and is read through a slice of that length.
     */
    static Version open(final Path dir, final TableDef def, final long rowCount,
            final List<SymbolTable> symbolsByColumn) {
        return open(dir, def, rowCount, symbolsByColumn, -1);
    }

    /**
     * {@link #open(Path, TableDef, long, List)} of a version known to have the files of its first
     * {@code fileColumns} columns, as {@code _txn} records them (-1 when unknown): one of those missing is refused,
     * the file of a last column included, which the tail rule alone would read as a column added later.
     */
    static Version open(final Path dir, final TableDef def, final long rowCount,
            final List<SymbolTable> symbolsByColumn, final int fileColumns) {
        final List<ColumnDef> columns = def.columns();
        final int n = columns.size();
        final CdbType[] types = new CdbType[n];
        final MemorySegment[] data = new MemorySegment[n];
        final MemorySegment[] index = new MemorySegment[n];
        final SymbolTable[] symbols = new SymbolTable[n];
        final Arena arena = Arena.ofShared();
        try {
            if (!Files.isDirectory(dir)) {
                throw new CdbException("refuse missing partition folder [table=" + def.name() + ", partition="
                        + dir.getFileName() + ']', -1);
            }
            long mapped = 0L;
            int firstAbsent = -1;
            for (int c = 0; c < n; c++) {
                final ColumnDef column = columns.get(c);
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
                    throw missing(def, dir, columns.get(firstAbsent).name() + ".d");
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
                    final MemorySegment ends = StoreFiles.map(i, arena);
                    mapped += ends.byteSize();
                    index[c] = committed(i, ends, rowCount * Long.BYTES);
                    final long end = rowCount == 0L
                            ? 0L
                            : decodeEnd(index[c].get(LONG_LE, (rowCount - 1L) * Long.BYTES));
                    data[c] = committed(d, data[c], end);
                } else {
                    data[c] = committed(d, data[c], expected);
                }
            }
            final int present = firstAbsent < 0 ? n : firstAbsent;
            if (present < Math.min(fileColumns, n)) {
                throw missing(def, dir, columns.get(present).name() + ".d");
            }
            final int ts = def.timestampIndex();
            if (rowCount > 0L) {
                if (data[0] == null) {
                    throw missing(def, dir, columns.get(0).name() + ".d");
                }
                if (ts >= 0 && data[ts] == null) {
                    throw missing(def, dir, columns.get(ts).name() + ".d");
                }
            }
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
            return new Version(dir, arena, types, data, index, symbols, rowCount, lower, min, max, mapped, present);
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

    /**
     * The committed bytes of column {@code column}'s data file: for VARCHAR, its rows' UTF-8 text; 0 for a column
     * added after this version was written.
     */
    long dataBytes(final int column) {
        final MemorySegment segment = column < data.length ? data[column] : null;
        return segment == null ? 0L : segment.byteSize();
    }

    @Override
    public void longs(final int column, final long from, final int n, final long[] dst) {
        final MemorySegment segment = segment(column, CdbType.LONG, CdbType.TIMESTAMP);
        if (segment == null) {
            Arrays.fill(dst, 0, n, Long.MIN_VALUE);
            return;
        }
        MemorySegment.copy(segment, LONG_LE, from * Long.BYTES, dst, 0, n);
    }

    @Override
    public void doubles(final int column, final long from, final int n, final double[] dst) {
        final MemorySegment segment = segment(column, CdbType.DOUBLE, CdbType.DOUBLE);
        if (segment == null) {
            Arrays.fill(dst, 0, n, Double.NaN);
            return;
        }
        MemorySegment.copy(segment, DOUBLE_LE, from * Double.BYTES, dst, 0, n);
    }

    @Override
    public void booleans(final int column, final long from, final int n, final boolean[] dst) {
        final MemorySegment segment = segment(column, CdbType.BOOLEAN, CdbType.BOOLEAN);
        if (segment == null) {
            Arrays.fill(dst, 0, n, false);
            return;
        }
        // a loop, not MemorySegment.copy: the copy takes no boolean[] (JDK 25: "Not a supported array class")
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
            final String[] values = symbols[column].values();
            long at = from * Integer.BYTES;
            for (int i = 0; i < n; i++, at += Integer.BYTES) {
                final int key = segment.get(INT_LE, at);
                dst[i] = key < 0 ? null : values[key];
            }
            return;
        }
        final MemorySegment ends = index[column];
        final TextCache cache = textCache(column);
        long start = from == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (from - 1L) * Long.BYTES));
        for (int i = 0; i < n; i++) {
            final long raw = ends.get(LONG_LE, (from + i) * Long.BYTES);
            final long end = checkedEnd(column, from + i, raw, start, segment);
            dst[i] = raw < 0L ? null : cache.text(segment, start, (int) (end - start));
            start = end;
        }
    }

    @Override
    public long rowAtOrAfter(final int timestampColumn, final long micros) {
        final MemorySegment segment = segment(timestampColumn, CdbType.LONG, CdbType.TIMESTAMP);
        if (segment == null) {
            // every row reads NULL, Long.MIN_VALUE, the smallest timestamp there is
            return micros > Long.MIN_VALUE ? rowCount : 0L;
        }
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
    }

    @Override
    public int selectLong(final int column, final long from, final int n, final Compare op, final long constant,
            final int[] sel) {
        final MemorySegment segment = segment(column, CdbType.LONG, CdbType.TIMESTAMP);
        if (segment == null) {
            return selectAll(n, sel);
        }
        return select(segment, zoneMap(column, segment, false), false, from, n, op, constant, sel);
    }

    @Override
    public int selectDouble(final int column, final long from, final int n, final Compare op, final double constant,
            final int[] sel) {
        final MemorySegment segment = segment(column, CdbType.DOUBLE, CdbType.DOUBLE);
        if (segment == null) {
            return selectAll(n, sel);
        }
        // Double.compare puts NaN above every other value: its key goes above every key a value that is not NaN has
        final long key = Double.isNaN(constant)
                ? Long.MAX_VALUE
                : ZoneMap.doubleKey(Double.doubleToRawLongBits(constant));
        return select(segment, zoneMap(column, segment, true), true, from, n, op, key, sel);
    }

    @Override
    public int selectText(final int column, final long from, final int n, final Compare op, final String constant,
            final int[] sel) {
        final MemorySegment segment = segment(column, CdbType.SYMBOL, CdbType.VARCHAR);
        if (segment == null) {
            // a column added after this partition was written: every row NULL, kept only by !=
            return op.holds(1) ? selectAll(n, sel) : 0;
        }
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
        final MemorySegment ends = index[column];
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
    }

    @Override
    public void longsAt(final int column, final long from, final int[] sel, final int count, final long[] dst) {
        final MemorySegment segment = segment(column, CdbType.LONG, CdbType.TIMESTAMP);
        if (segment == null) {
            Arrays.fill(dst, 0, count, Long.MIN_VALUE);
            return;
        }
        for (int i = 0; i < count; i++) {
            dst[i] = segment.get(LONG_LE, (from + sel[i]) * Long.BYTES);
        }
    }

    @Override
    public void doublesAt(final int column, final long from, final int[] sel, final int count, final double[] dst) {
        final MemorySegment segment = segment(column, CdbType.DOUBLE, CdbType.DOUBLE);
        if (segment == null) {
            Arrays.fill(dst, 0, count, Double.NaN);
            return;
        }
        for (int i = 0; i < count; i++) {
            dst[i] = segment.get(DOUBLE_LE, (from + sel[i]) * Double.BYTES);
        }
    }

    @Override
    public void booleansAt(final int column, final long from, final int[] sel, final int count,
            final boolean[] dst) {
        final MemorySegment segment = segment(column, CdbType.BOOLEAN, CdbType.BOOLEAN);
        if (segment == null) {
            Arrays.fill(dst, 0, count, false);
            return;
        }
        for (int i = 0; i < count; i++) {
            dst[i] = segment.get(ValueLayout.JAVA_BYTE, from + sel[i]) != 0;
        }
    }

    @Override
    public void textsAt(final int column, final long from, final int[] sel, final int count, final String[] dst) {
        final MemorySegment segment = segment(column, CdbType.SYMBOL, CdbType.VARCHAR);
        if (segment == null) {
            Arrays.fill(dst, 0, count, null);
            return;
        }
        if (types[column] == CdbType.SYMBOL) {
            final String[] values = symbols[column].values();
            for (int i = 0; i < count; i++) {
                final int key = segment.get(INT_LE, (from + sel[i]) * Integer.BYTES);
                dst[i] = key < 0 ? null : values[key];
            }
            return;
        }
        final MemorySegment ends = index[column];
        final TextCache cache = textCache(column);
        for (int i = 0; i < count; i++) {
            final long row = from + sel[i];
            final long start = row == 0L ? 0L : decodeEnd(ends.get(LONG_LE, (row - 1L) * Long.BYTES));
            final long raw = ends.get(LONG_LE, row * Long.BYTES);
            final long end = checkedEnd(column, row, raw, start, segment);
            dst[i] = raw < 0L ? null : cache.text(segment, start, (int) (end - start));
        }
    }

    /** The end offset an index entry holds: a NULL's entry is the complement of its end, so negative. */
    static long decodeEnd(final long entry) {
        return entry < 0L ? ~entry : entry;
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
        final TextCache cache = texts.get(column);
        if (cache != null) {
            return cache;
        }
        final TextCache fresh = new TextCache();
        return texts.compareAndSet(column, null, fresh) ? fresh : texts.get(column);
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

    private static CdbException missing(final TableDef def, final Path dir, final String file) {
        return new CdbException("refuse partition with a missing file [table=" + def.name() + ", partition="
                + dir.getFileName() + ", file=" + file + ']', -1);
    }

    /**
     * The first {@code bytes} of {@code segment}, mapped from {@code file}: a file shorter than its committed rows need
     * is refused, the bytes past them are not read.
     */
    private static MemorySegment committed(final Path file, final MemorySegment segment, final long bytes) {
        if (segment.byteSize() < bytes) {
            throw new CdbException("refuse partition file shorter than its rows [file=" + file + ", bytes="
                    + segment.byteSize() + ", expected=" + bytes + ']', -1);
        }
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
