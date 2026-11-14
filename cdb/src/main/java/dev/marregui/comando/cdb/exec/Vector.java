package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.CdbType;

/**
 * One column of a {@link Page}: the array of its type holds the values, the others are null. LONG and TIMESTAMP in
 * {@link #longs}, DOUBLE in {@link #doubles}, BOOLEAN in {@link #booleans}, SYMBOL and VARCHAR in {@link #texts}; NULL is
 * the type's sentinel ({@link CdbType}). Values past the page's size are unspecified.
 */
public final class Vector {

    /** The NULL of LONG and TIMESTAMP. */
    public static final long NULL_LONG = Long.MIN_VALUE;

    public final CdbType type;
    public final long[] longs;
    public final double[] doubles;
    public final boolean[] booleans;
    public final String[] texts;

    private Vector(final CdbType type, final int capacity) {
        this.type = type;
        longs = type == CdbType.LONG || type == CdbType.TIMESTAMP ? new long[capacity] : null;
        doubles = type == CdbType.DOUBLE ? new double[capacity] : null;
        booleans = type == CdbType.BOOLEAN ? new boolean[capacity] : null;
        texts = type.isText() ? new String[capacity] : null;
    }

    /** A vector of {@code type} for up to {@code capacity} values. */
    public static Vector of(final CdbType type, final int capacity) {
        return new Vector(type, capacity);
    }

    /** True when value {@code i} is NULL. */
    public boolean isNull(final int i) {
        return switch (type) {
            case LONG, TIMESTAMP -> longs[i] == NULL_LONG;
            case DOUBLE -> Double.isNaN(doubles[i]);
            case BOOLEAN -> false;
            case SYMBOL, VARCHAR -> texts[i] == null;
        };
    }

    /** Copies value {@code from} of {@code source}, of the same type, to index {@code to} of this vector. */
    public void copy(final Vector source, final int from, final int to) {
        switch (type) {
            case LONG, TIMESTAMP -> longs[to] = source.longs[from];
            case DOUBLE -> doubles[to] = source.doubles[from];
            case BOOLEAN -> booleans[to] = source.booleans[from];
            case SYMBOL, VARCHAR -> texts[to] = source.texts[from];
        }
    }

    /** Sets value {@code i} to NULL (false for BOOLEAN). */
    public void setNull(final int i) {
        switch (type) {
            case LONG, TIMESTAMP -> longs[i] = NULL_LONG;
            case DOUBLE -> doubles[i] = Double.NaN;
            case BOOLEAN -> booleans[i] = false;
            case SYMBOL, VARCHAR -> texts[i] = null;
        }
    }
}
