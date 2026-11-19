package dev.marregui.comando.cdb.store;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbType;

/**
 * Rows of one partition held in native memory by column while a commit merges them, NULL as in {@link CdbType}; {@link
 * #size} rows are valid. The block's memory comes from the epoch of {@link BlockArenas} it joined (filled on a request
 * thread, written by the applier), tagged with its table and counted in bytes, and is given back by {@link #close},
 * which first points every column at a one-byte segment of a closed arena, so a read or write after the close throws
 * ({@link IllegalStateException}, or {@link IndexOutOfBoundsException} for an offset past that byte) rather than reach
 * bytes given back, which the epoch hands to its next block. Fixed-width columns are one segment each; a text column
 * (SYMBOL, VARCHAR) holds the UTF-8 bytes of its values in one segment that grows by doubling, the outgrown one given
 * back, and per row their start, their byte length ({@code -1} for NULL) and their UTF-16 length. Not safe for
 * concurrent use: one thread uses a block at a time, and none while another closes it.
 * <p>
 * A segment the epoch reuses is not zeroed: a row not yet set reads NULL in a text column, and in a fixed-width column
 * whatever bytes the segment's last block left, so its writer sets every column of each row before a read of it. The
 * readers read rows below {@link #size}, all set; a resize copies the row at the size too, the one being written, and
 * a text row's start, UTF-16 length and bytes are read only once its length is set.
 */
final class RowBlock implements AutoCloseable {

    // native bytes of every block not yet closed, across the process
    private static final AtomicLong LIVE_BYTES = new AtomicLong();
    // a segment whose arena is closed: every access throws IllegalStateException, as the block's own arena's did
    private static final MemorySegment CLOSED = closedSegment();
    private static final int NULL_LENGTH = -1;
    // the first text segment of a column, per row of capacity; grown by doubling from there
    private static final long TEXT_BYTES_PER_ROW = 8L;

    final CdbType[] types;
    int size;
    private final String tag;
    private final BlockArenas.Epoch epoch;
    // per column: the values of a fixed-width column, the UTF-8 bytes of a text column
    private final MemorySegment[] values;
    // per text column, per row: start in values (long), UTF-8 length (int), UTF-16 length (int)
    private final MemorySegment[] starts;
    private final MemorySegment[] lengths;
    private final MemorySegment[] chars;
    // per text column: the bytes of values used
    private final long[] used;
    private long bytes;
    private boolean closed;

    RowBlock(final List<ColumnDef> columns, final int capacity) {
        this("rows", columns, capacity);
    }

    /** A block of {@code capacity} rows of {@code columns}, its native memory tagged {@code tag} (its table). */
    RowBlock(final String tag, final List<ColumnDef> columns, final int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("refuse negative capacity [tag=" + tag + ", capacity=" + capacity + ']');
        }
        final int n = columns.size();
        this.tag = tag;
        types = new CdbType[n];
        values = new MemorySegment[n];
        starts = new MemorySegment[n];
        lengths = new MemorySegment[n];
        chars = new MemorySegment[n];
        used = new long[n];
        epoch = BlockArenas.shared().join();
        try {
            for (int c = 0; c < n; c++) {
                final CdbType t = columns.get(c).type();
                types[c] = t;
                switch (t) {
                    case LONG, TIMESTAMP, DOUBLE -> values[c] = allocate((long) capacity * Long.BYTES, Long.BYTES);
                    case BOOLEAN -> values[c] = allocate(capacity, 1L);
                    case SYMBOL, VARCHAR -> {
                        values[c] = allocate(capacity * TEXT_BYTES_PER_ROW, 1L);
                        starts[c] = allocate((long) capacity * Long.BYTES, Long.BYTES);
                        lengths[c] = allocate((long) capacity * Integer.BYTES, Integer.BYTES);
                        chars[c] = allocate((long) capacity * Integer.BYTES, Integer.BYTES);
                        // every row NULL until set, as a fresh String[] was: a length of -1 is all one bits
                        lengths[c].fill((byte) -1);
                    }
                }
            }
        } catch (final RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    /** Native bytes of every block not yet closed. */
    @TestOnly
    static long liveBytes() {
        return LIVE_BYTES.get();
    }

    /** Native bytes this block holds; 0 once closed. */
    @TestOnly
    long bytes() {
        return bytes;
    }

    String tag() {
        return tag;
    }

    long getLong(final int column, final int row) {
        return values[column].getAtIndex(ValueLayout.JAVA_LONG, row);
    }

    void setLong(final int column, final int row, final long value) {
        values[column].setAtIndex(ValueLayout.JAVA_LONG, row, value);
    }

    double getDouble(final int column, final int row) {
        return values[column].getAtIndex(ValueLayout.JAVA_DOUBLE, row);
    }

    void setDouble(final int column, final int row, final double value) {
        values[column].setAtIndex(ValueLayout.JAVA_DOUBLE, row, value);
    }

    boolean getBoolean(final int column, final int row) {
        return values[column].get(ValueLayout.JAVA_BYTE, row) != 0;
    }

    void setBoolean(final int column, final int row, final boolean value) {
        values[column].set(ValueLayout.JAVA_BYTE, row, value ? (byte) 1 : (byte) 0);
    }

    /** Text value {@code row} of {@code column} as a String, null when NULL. */
    String textAt(final int column, final int row) {
        final int length = lengths[column].getAtIndex(ValueLayout.JAVA_INT, row);
        if (length == NULL_LENGTH) {
            return null;
        }
        final long start = starts[column].getAtIndex(ValueLayout.JAVA_LONG, row);
        final byte[] utf8 = new byte[length];
        MemorySegment.copy(values[column], ValueLayout.JAVA_BYTE, start, utf8, 0, length);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    /** The UTF-8 byte length of text value {@code row} of {@code column}, {@code -1} when NULL. */
    int textLength(final int column, final int row) {
        return lengths[column].getAtIndex(ValueLayout.JAVA_INT, row);
    }

    /** The UTF-16 length of text value {@code row} of {@code column}, as {@link String#length()}; 0 when set NULL. */
    int textChars(final int column, final int row) {
        return chars[column].getAtIndex(ValueLayout.JAVA_INT, row);
    }

    /** Copies the UTF-8 bytes of text value {@code row} of {@code column}, not NULL, to {@code dst} at {@code at}. */
    void copyText(final int column, final int row, final byte[] dst, final int at) {
        final int length = lengths[column].getAtIndex(ValueLayout.JAVA_INT, row);
        final long start = starts[column].getAtIndex(ValueLayout.JAVA_LONG, row);
        MemorySegment.copy(values[column], ValueLayout.JAVA_BYTE, start, dst, at, length);
    }

    /** True when text values {@code a} and {@code b} of {@code column} are equal, both NULL included. */
    boolean textEquals(final int column, final int a, final int b) {
        final int length = lengths[column].getAtIndex(ValueLayout.JAVA_INT, a);
        if (length != lengths[column].getAtIndex(ValueLayout.JAVA_INT, b)) {
            return false;
        }
        if (length == NULL_LENGTH) {
            return true;
        }
        final long sa = starts[column].getAtIndex(ValueLayout.JAVA_LONG, a);
        final long sb = starts[column].getAtIndex(ValueLayout.JAVA_LONG, b);
        return MemorySegment.mismatch(values[column], sa, sa + length, values[column], sb, sb + length) < 0L;
    }

    /** A hash of text value {@code row} of {@code column} over its UTF-8 bytes; 0 for NULL. */
    int textHash(final int column, final int row) {
        final int length = lengths[column].getAtIndex(ValueLayout.JAVA_INT, row);
        if (length == NULL_LENGTH) {
            return 0;
        }
        final long start = starts[column].getAtIndex(ValueLayout.JAVA_LONG, row);
        int h = 1;
        for (long i = start; i < start + length; i++) {
            h = 31 * h + values[column].get(ValueLayout.JAVA_BYTE, i);
        }
        return h;
    }

    void setText(final int column, final int row, final String value) {
        if (value == null) {
            lengths[column].setAtIndex(ValueLayout.JAVA_INT, row, NULL_LENGTH);
            chars[column].setAtIndex(ValueLayout.JAVA_INT, row, 0);
            return;
        }
        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        final long start = reserveText(column, utf8.length);
        MemorySegment.copy(utf8, 0, values[column], ValueLayout.JAVA_BYTE, start, utf8.length);
        starts[column].setAtIndex(ValueLayout.JAVA_LONG, row, start);
        lengths[column].setAtIndex(ValueLayout.JAVA_INT, row, utf8.length);
        chars[column].setAtIndex(ValueLayout.JAVA_INT, row, value.length());
    }

    /** Copies row {@code from} of {@code source}, of the same columns, to row {@code to} of this block. */
    void copyRow(final RowBlock source, final int from, final int to) {
        copyRows(source, from, to, 1);
    }

    /**
     * Copies rows {@code [from, from + n)} of {@code source} to rows from {@code to}; the columns of {@code source}
     * are the first of this block's, the others left as they are.
     */
    void copyRows(final RowBlock source, final int from, final int to, final int n) {
        for (int c = 0; c < source.types.length; c++) {
            switch (types[c]) {
                case LONG, TIMESTAMP, DOUBLE -> MemorySegment.copy(source.values[c], (long) from * Long.BYTES,
                        values[c], (long) to * Long.BYTES, (long) n * Long.BYTES);
                case BOOLEAN -> MemorySegment.copy(source.values[c], from, values[c], to, n);
                case SYMBOL, VARCHAR -> {
                    for (int i = 0; i < n; i++) {
                        copyTextRow(source, c, from + i, to + i);
                    }
                }
            }
        }
    }

    /** Frees the block's native memory; idempotent, safe on a half-built block. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        final long freed = bytes;
        bytes = 0L;
        // detach, then give back: no column reaches the epoch's bytes once the block left it, nor a segment the epoch
        // hands to its next block
        free(values);
        free(starts);
        free(lengths);
        free(chars);
        BlockArenas.shared().leave(epoch);
        LIVE_BYTES.addAndGet(-freed);
    }

    private void copyTextRow(final RowBlock source, final int c, final int from, final int to) {
        final int length = source.lengths[c].getAtIndex(ValueLayout.JAVA_INT, from);
        if (length == NULL_LENGTH) {
            lengths[c].setAtIndex(ValueLayout.JAVA_INT, to, NULL_LENGTH);
            chars[c].setAtIndex(ValueLayout.JAVA_INT, to, 0);
            return;
        }
        final long start = reserveText(c, length);
        MemorySegment.copy(source.values[c], source.starts[c].getAtIndex(ValueLayout.JAVA_LONG, from), values[c],
                start, length);
        starts[c].setAtIndex(ValueLayout.JAVA_LONG, to, start);
        lengths[c].setAtIndex(ValueLayout.JAVA_INT, to, length);
        chars[c].setAtIndex(ValueLayout.JAVA_INT, to, source.chars[c].getAtIndex(ValueLayout.JAVA_INT, from));
    }

    // the start of length free bytes in text column c's segment, grown by doubling when they do not fit
    private long reserveText(final int c, final int length) {
        if (closed) {
            // the columns are detached, but a grow would take memory from an epoch the block left
            throw new IllegalStateException("refuse a write to a closed row block [tag=" + tag + ']');
        }
        final long start = used[c];
        final long need = start + length;
        final MemorySegment old = values[c];
        if (need > old.byteSize()) {
            final long doubled = old.byteSize() > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : old.byteSize() * 2L;
            final MemorySegment grown = allocate(Math.max(need, doubled), 1L);
            MemorySegment.copy(old, 0L, grown, 0L, start);
            values[c] = grown;
            // the column reaches only the grown segment now, so the old one goes back to the epoch for its next block
            epoch.free(old);
            bytes -= old.byteSize();
            LIVE_BYTES.addAndGet(-old.byteSize());
        }
        used[c] = need;
        return start;
    }

    // a shared arena, so an access from any thread throws IllegalStateException; a confined one would throw
    // WrongThreadException on every thread but the one that loaded this class
    private static MemorySegment closedSegment() {
        final Arena arena = Arena.ofShared();
        final MemorySegment segment = arena.allocate(1L);
        arena.close();
        return segment;
    }

    private MemorySegment allocate(final long byteSize, final long alignment) {
        final MemorySegment segment = epoch.allocate(byteSize, alignment);
        bytes += byteSize;
        LIVE_BYTES.addAndGet(byteSize);
        return segment;
    }

    // points each column at CLOSED, then gives its segment back to the epoch
    private void free(final MemorySegment[] segments) {
        for (int c = 0; c < segments.length; c++) {
            final MemorySegment segment = segments[c];
            if (segment != null) {
                segments[c] = CLOSED;
                epoch.free(segment);
            }
        }
    }
}
