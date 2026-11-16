package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;

/**
 * A growable column of one type, by index: the keys of a group table, the stored value of a group. NULL is the type's
 * sentinel, as in a {@link Vector}. The static helpers hash, compare and test values of a vector the same way.
 */
public final class Values {

    private static final int INITIAL_CAPACITY = 16;
    // a percentile group's first value list; a sizing hint
    private static final int INITIAL_LIST = 8;
    // the largest array the JVM allocates on every collector, with the headroom ArrayList keeps
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    private final CdbType type;
    private boolean[] booleanValues;
    private int capacity;
    private double[] doubleValues;
    private long[] longValues;
    private String[] textValues;

    /** An empty column of {@code type}. */
    public Values(final CdbType type) {
        this.type = type;
        allocate(INITIAL_CAPACITY);
    }

    /** Values {@code a} and {@code b} of {@code v} compared: NULLs first, then ascending; BOOLEAN false first. */
    public static int compare(final Vector v, final int a, final int b) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> Long.compare(v.getLong(a), v.getLong(b));
            case DOUBLE -> compareDoubles(v.getDouble(a), v.getDouble(b));
            case BOOLEAN -> Boolean.compare(v.getBoolean(a), v.getBoolean(b));
            case SYMBOL, VARCHAR -> compareTexts(v, a, b);
        };
    }

    /** NaN (DOUBLE's NULL) first, then ascending. */
    public static int compareDoubles(final double a, final double b) {
        final boolean nanA = Double.isNaN(a);
        final boolean nanB = Double.isNaN(b);
        if (nanA || nanB) {
            return Boolean.compare(nanB, nanA);
        }
        return Double.compare(a, b);
    }

    /** Null first, then by {@link String#compareTo}. */
    public static int compareTexts(final String a, final String b) {
        if (a == null || b == null) {
            return Boolean.compare(b == null, a == null);
        }
        return a.compareTo(b);
    }

    // the order of compareTexts(String, String), read through the vector's text accessors
    private static int compareTexts(final Vector v, final int a, final int b) {
        final boolean nullA = v.isNull(a);
        final boolean nullB = v.isNull(b);
        if (nullA || nullB) {
            return Boolean.compare(nullB, nullA);
        }
        return v.textCompare(a, v, b);
    }

    /** True when values {@code a} and {@code b} of {@code v} are equal as {@link #sameAs} decides it. */
    public static boolean equal(final Vector v, final int a, final int b) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> v.getLong(a) == v.getLong(b);
            case DOUBLE -> sameDouble(v.getDouble(a), v.getDouble(b));
            case BOOLEAN -> v.getBoolean(a) == v.getBoolean(b);
            case SYMBOL, VARCHAR -> sameText(v, a, v, b);
        };
    }

    /**
     * {@code list}, or a longer copy when it holds fewer than {@code needed} values; a new one of at least
     * {@link #INITIAL_LIST} when it is null. A per-group value list of the percentile accumulators.
     */
    static double[] room(final double[] list, final int needed) {
        if (list == null) {
            return new double[Math.max(INITIAL_LIST, needed)];
        }
        return needed > list.length ? Arrays.copyOf(list, grownCapacity(list.length, needed)) : list;
    }

    /** {@link #room(double[], int)} for LONG values. */
    static long[] room(final long[] list, final int needed) {
        if (list == null) {
            return new long[Math.max(INITIAL_LIST, needed)];
        }
        return needed > list.length ? Arrays.copyOf(list, grownCapacity(list.length, needed)) : list;
    }

    // equality of two values of one type: NaN equals NaN and -0.0 is not 0.0, by their bits; NULL texts equal
    private static boolean sameDouble(final double a, final double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private static boolean sameText(final String a, final String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean sameText(final Vector v, final int a, final Vector w, final int b) {
        final boolean nullA = v.isNull(a);
        return nullA == w.isNull(b) && (nullA || v.textEquals(a, w, b));
    }

    private static boolean sameText(final String a, final Vector v, final int row) {
        return a == null ? v.isNull(row) : !v.isNull(row) && v.textEquals(row, a);
    }

    /** The hash of value {@code row}; equal values (the NULLs of a type among them) hash alike. */
    public static int hash(final Vector v, final int row) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> Long.hashCode(v.getLong(row));
            case DOUBLE -> Double.hashCode(v.getDouble(row));
            case BOOLEAN -> Boolean.hashCode(v.getBoolean(row));
            case SYMBOL, VARCHAR -> v.isNull(row) ? 0 : v.textHash(row);
        };
    }

    /** Makes room for indexes {@code [0, size)}. */
    public void ensure(final int size) {
        if (size > capacity) {
            allocate(grownCapacity(capacity, size));
        }
    }

    /** True when value {@code index} equals value {@code row} of {@code source}, of this type; NULL equals NULL. */
    public boolean sameAs(final int index, final Vector source, final int row) {
        return switch (type) {
            case LONG, TIMESTAMP -> longValues[index] == source.getLong(row);
            case DOUBLE -> sameDouble(doubleValues[index], source.getDouble(row));
            case BOOLEAN -> booleanValues[index] == source.getBoolean(row);
            case SYMBOL, VARCHAR -> sameText(textValues[index], source, row);
        };
    }

    /**
     * True when value {@code index} equals value {@code sourceIndex} of {@code source}, a column of this type, as
     * {@link #sameAs(int, Vector, int)} decides it.
     */
    public boolean sameAs(final int index, final Values source, final int sourceIndex) {
        return switch (type) {
            case LONG, TIMESTAMP -> longValues[index] == source.longValues[sourceIndex];
            case DOUBLE -> sameDouble(doubleValues[index], source.doubleValues[sourceIndex]);
            case BOOLEAN -> booleanValues[index] == source.booleanValues[sourceIndex];
            case SYMBOL, VARCHAR -> sameText(textValues[index], source.textValues[sourceIndex]);
        };
    }

    /** Sets value {@code index}, below the size last ensured, to value {@code row} of {@code source}, of this type. */
    public void set(final int index, final Vector source, final int row) {
        switch (type) {
            case LONG, TIMESTAMP -> longValues[index] = source.getLong(row);
            case DOUBLE -> doubleValues[index] = source.getDouble(row);
            case BOOLEAN -> booleanValues[index] = source.getBoolean(row);
            // a String of its own, as the page's backing may be bytes it does not keep
            case SYMBOL, VARCHAR -> textValues[index] = source.textAt(row);
        }
    }

    /**
     * Sets value {@code index}, below the size last ensured, to value {@code sourceIndex} of {@code source}, a column
     * of this type.
     */
    public void set(final int index, final Values source, final int sourceIndex) {
        switch (type) {
            case LONG, TIMESTAMP -> longValues[index] = source.longValues[sourceIndex];
            case DOUBLE -> doubleValues[index] = source.doubleValues[sourceIndex];
            case BOOLEAN -> booleanValues[index] = source.booleanValues[sourceIndex];
            case SYMBOL, VARCHAR -> textValues[index] = source.textValues[sourceIndex];
        }
    }

    public CdbType type() {
        return type;
    }

    /** Writes value {@code index} to value {@code row} of {@code out}, a vector of this type. */
    public void write(final int index, final Vector out, final int row) {
        switch (type) {
            case LONG, TIMESTAMP -> out.setLong(row, longValues[index]);
            case DOUBLE -> out.setDouble(row, doubleValues[index]);
            case BOOLEAN -> out.setBoolean(row, booleanValues[index]);
            case SYMBOL, VARCHAR -> out.setText(row, textValues[index]);
        }
    }

    /** The capacity to grow to for {@code needed} values: {@code current} doubled, at least {@code needed}. */
    static int grownCapacity(final int current, final int needed) {
        if (needed < 0 || needed > MAX_CAPACITY) {
            throw new CdbException("cannot hold more values [needed=" + needed + ", limit=" + MAX_CAPACITY + ']', -1);
        }
        final int doubled = current > MAX_CAPACITY / 2 ? MAX_CAPACITY : current * 2;
        return Math.max(doubled, needed);
    }

    private void allocate(final int newCapacity) {
        switch (type) {
            case LONG, TIMESTAMP -> longValues = longValues == null
                    ? new long[newCapacity]
                    : Arrays.copyOf(longValues, newCapacity);
            case DOUBLE -> doubleValues = doubleValues == null
                    ? new double[newCapacity]
                    : Arrays.copyOf(doubleValues, newCapacity);
            case BOOLEAN -> booleanValues = booleanValues == null
                    ? new boolean[newCapacity]
                    : Arrays.copyOf(booleanValues, newCapacity);
            case SYMBOL, VARCHAR -> textValues = textValues == null
                    ? new String[newCapacity]
                    : Arrays.copyOf(textValues, newCapacity);
        }
        capacity = newCapacity;
    }
}
