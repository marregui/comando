package dev.marregui.comando.cdb.exec;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

import dev.marregui.comando.cdb.CdbType;

/**
 * One column of a {@link Page}, read and written through its accessors: {@link #getLong} for LONG and TIMESTAMP,
 * {@link #getDouble} for DOUBLE, {@link #getBoolean} for BOOLEAN, the text accessors for SYMBOL and VARCHAR. NULL is
 * the type's sentinel ({@link CdbType}): {@link #NULL_LONG}, NaN, a null text. Values past the page's size are
 * unspecified. Text is held as UTF-8 bytes, and a SYMBOL value set by key as the key of the vector's one
 * {@link SymbolDictionary} ({@link TextColumn}); it compares and hashes as {@link String} does (the sign of
 * {@link String#compareTo}, {@link String#hashCode}).
 */
public final class Vector {

    /** The NULL of LONG and TIMESTAMP. */
    public static final long NULL_LONG = Long.MIN_VALUE;
    /** {@link #symbolKey} of a value whose dictionary key the vector does not carry. */
    public static final int NO_KEY = -1;
    // what poison writes: a LONG no column holds by chance, a NaN whose payload no arithmetic produces
    static final long POISON_LONG = Long.MIN_VALUE + 1L;
    static final double POISON_DOUBLE = Double.longBitsToDouble(0x7FF4_DEAD_BEEF_0001L);
    private static final ValueLayout.OfLong LONG_LE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE_LE =
            ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public final CdbType type;
    private final boolean[] booleans;
    private final double[] doubles;
    private final long[] longs;
    private final TextColumn texts;

    private Vector(final CdbType type, final int capacity) {
        this.type = type;
        longs = type == CdbType.LONG || type == CdbType.TIMESTAMP ? new long[capacity] : null;
        doubles = type == CdbType.DOUBLE ? new double[capacity] : null;
        booleans = type == CdbType.BOOLEAN ? new boolean[capacity] : null;
        texts = type.isText() ? new TextColumn(capacity, type == CdbType.SYMBOL) : null;
    }

    /** A vector of {@code type} for up to {@code capacity} values. */
    public static Vector of(final CdbType type, final int capacity) {
        return new Vector(type, capacity);
    }

    /** The number of values the vector has room for. */
    public int capacity() {
        return switch (type) {
            case LONG, TIMESTAMP -> longs.length;
            case DOUBLE -> doubles.length;
            case BOOLEAN -> booleans.length;
            case SYMBOL, VARCHAR -> texts.capacity();
        };
    }

    /**
     * Copies {@code n} little-endian LONG or TIMESTAMP values of {@code src} from byte {@code offset} to indexes
     * {@code at} on, one bulk copy; the store's NULL is {@link #NULL_LONG}, as here.
     */
    public void readLongs(final int at, final MemorySegment src, final long offset, final int n) {
        MemorySegment.copy(src, LONG_LE, offset, longs, at, n);
    }

    /** {@link #readLongs} for DOUBLE: NaN is NULL in both. */
    public void readDoubles(final int at, final MemorySegment src, final long offset, final int n) {
        MemorySegment.copy(src, DOUBLE_LE, offset, doubles, at, n);
    }

    /**
     * Copies {@code n} little-endian int keys of {@code dictionary} from byte {@code offset} of {@code src} to indexes
     * {@code at} on, a negative key as NULL: as {@link #setSymbol} per value, in one bulk copy when the vector's
     * dictionary is {@code dictionary} or not yet bound.
     */
    public void readSymbols(
            final int at,
            final MemorySegment src,
            final long offset,
            final int n,
            final SymbolDictionary dictionary
    ) {
        texts.readSymbols(at, src, offset, n, dictionary);
    }

    /** Empties the vector for a refill: text bytes let go, the dictionary unbound, the arrays kept. */
    void clear() {
        if (texts != null) {
            texts.clear();
        }
    }

    /** Overwrites values {@code 0} to {@code n - 1} with the poison of {@link Page#poison}. */
    void poison(final int n) {
        switch (type) {
            case LONG, TIMESTAMP -> Arrays.fill(longs, 0, n, POISON_LONG);
            case DOUBLE -> Arrays.fill(doubles, 0, n, POISON_DOUBLE);
            case BOOLEAN -> Arrays.fill(booleans, 0, n, true);
            case SYMBOL, VARCHAR -> texts.poison(n);
        }
    }

    public long getLong(final int i) {
        return longs[i];
    }

    public void setLong(final int i, final long value) {
        longs[i] = value;
    }

    public double getDouble(final int i) {
        return doubles[i];
    }

    public void setDouble(final int i, final double value) {
        doubles[i] = value;
    }

    public boolean getBoolean(final int i) {
        return booleans[i];
    }

    public void setBoolean(final int i, final boolean value) {
        booleans[i] = value;
    }

    /** Text value {@code i} as a String, null when NULL; it decodes the value's bytes, so call it at output. */
    public String textAt(final int i) {
        return texts.textAt(i);
    }

    /** Sets text value {@code i}; null sets NULL. */
    public void setText(final int i, final String value) {
        texts.setText(i, value);
    }

    /** Sets text value {@code i} to a copy of the {@code length} UTF-8 bytes of {@code data} at {@code start}. */
    public void setText(final int i, final MemorySegment data, final long start, final int length) {
        texts.setText(i, data, start, length);
    }

    /**
     * Sets SYMBOL value {@code i} to {@code key} of {@code dictionary}: carried as the key when the vector's dictionary
     * is {@code dictionary} or not yet bound (the first such call binds it), else held as the value's bytes.
     */
    public void setSymbol(final int i, final SymbolDictionary dictionary, final int key) {
        texts.setSymbol(i, dictionary, key);
    }

    /** True when the non-NULL text values {@code i} of this and {@code j} of {@code other} are equal. */
    public boolean textEquals(final int i, final Vector other, final int j) {
        return texts.textEquals(i, other.texts, j);
    }

    /** True when the non-NULL text value {@code i} equals the non-null {@code value}. */
    public boolean textEquals(final int i, final String value) {
        return texts.textEquals(i, value);
    }

    /** The sign of {@link String#compareTo} of non-NULL text values {@code i} of this, {@code j} of {@code other}. */
    public int textCompare(final int i, final Vector other, final int j) {
        return texts.textCompare(i, other.texts, j);
    }

    /** The sign of {@link String#compareTo} of the non-NULL text value {@code i} and the non-null {@code value}. */
    public int textCompare(final int i, final String value) {
        return texts.textCompare(i, value);
    }

    /** {@link String#hashCode} of the non-NULL text value {@code i}. */
    public int textHash(final int i) {
        return texts.textHash(i);
    }

    /** The dictionary key of SYMBOL value {@code i}, {@link #NO_KEY} when the vector does not carry it. */
    public int symbolKey(final int i) {
        return texts == null ? NO_KEY : texts.symbolKey(i);
    }

    /**
     * The dictionary the keys of {@link #symbolKey} come from, null when the vector carries none; the keys of two
     * vectors compare as ints only when this is the same object for both.
     */
    public SymbolDictionary symbolDictionary() {
        return texts == null ? null : texts.symbolDictionary();
    }

    /** True when value {@code i} is NULL. */
    public boolean isNull(final int i) {
        return switch (type) {
            case LONG, TIMESTAMP -> longs[i] == NULL_LONG;
            case DOUBLE -> Double.isNaN(doubles[i]);
            case BOOLEAN -> false;
            case SYMBOL, VARCHAR -> texts.isNull(i);
        };
    }

    /** Puts values {@code [at, at + n)} in reverse order, in place; a text value's bytes stay where they are. */
    public void reverse(final int at, final int n) {
        for (int i = at, j = at + n - 1; i < j; i++, j--) {
            switch (type) {
                case LONG, TIMESTAMP -> {
                    final long t = longs[i];
                    longs[i] = longs[j];
                    longs[j] = t;
                }
                case DOUBLE -> {
                    final double t = doubles[i];
                    doubles[i] = doubles[j];
                    doubles[j] = t;
                }
                case BOOLEAN -> {
                    final boolean t = booleans[i];
                    booleans[i] = booleans[j];
                    booleans[j] = t;
                }
                case SYMBOL, VARCHAR -> texts.swap(i, j);
            }
        }
    }

    /** Copies value {@code from} of {@code source}, of the same type, to index {@code to} of this vector. */
    public void copy(final Vector source, final int from, final int to) {
        switch (type) {
            case LONG, TIMESTAMP -> longs[to] = source.longs[from];
            case DOUBLE -> doubles[to] = source.doubles[from];
            case BOOLEAN -> booleans[to] = source.booleans[from];
            case SYMBOL, VARCHAR -> texts.copy(source.texts, from, to);
        }
    }

    /**
     * Copies {@code n} values of {@code source}, of the same type, from {@code from} to this vector at {@code to}; the
     * ranges may overlap when {@code source} is this vector.
     */
    public void copy(final Vector source, final int from, final int to, final int n) {
        switch (type) {
            case LONG, TIMESTAMP -> System.arraycopy(source.longs, from, longs, to, n);
            case DOUBLE -> System.arraycopy(source.doubles, from, doubles, to, n);
            case BOOLEAN -> System.arraycopy(source.booleans, from, booleans, to, n);
            case SYMBOL, VARCHAR -> texts.copy(source.texts, from, to, n);
        }
    }

    /** Sets value {@code i} to NULL (false for BOOLEAN). */
    public void setNull(final int i) {
        switch (type) {
            case LONG, TIMESTAMP -> longs[i] = NULL_LONG;
            case DOUBLE -> doubles[i] = Double.NaN;
            case BOOLEAN -> booleans[i] = false;
            case SYMBOL, VARCHAR -> texts.setNull(i);
        }
    }
}
