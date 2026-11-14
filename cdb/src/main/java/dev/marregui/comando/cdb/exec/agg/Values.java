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
    // the largest array the JVM allocates on every collector, with the headroom ArrayList keeps
    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    private final CdbType type;
    private boolean[] booleans;
    private int capacity;
    private double[] doubles;
    private long[] longs;
    private String[] texts;

    /** An empty column of {@code type}. */
    public Values(final CdbType type) {
        this.type = type;
        allocate(INITIAL_CAPACITY);
    }

    /** Values {@code a} and {@code b} of {@code v} compared: NULLs first, then ascending; BOOLEAN false first. */
    public static int compare(final Vector v, final int a, final int b) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> Long.compare(v.longs[a], v.longs[b]);
            case DOUBLE -> compareDoubles(v.doubles[a], v.doubles[b]);
            case BOOLEAN -> Boolean.compare(v.booleans[a], v.booleans[b]);
            case SYMBOL, VARCHAR -> compareTexts(v.texts[a], v.texts[b]);
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

    /** The hash of value {@code row}; equal values (the NULLs of a type among them) hash alike. */
    public static int hash(final Vector v, final int row) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> Long.hashCode(v.longs[row]);
            case DOUBLE -> Double.hashCode(v.doubles[row]);
            case BOOLEAN -> Boolean.hashCode(v.booleans[row]);
            case SYMBOL, VARCHAR -> v.texts[row] == null ? 0 : v.texts[row].hashCode();
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
            case LONG, TIMESTAMP -> longs[index] == source.longs[row];
            case DOUBLE -> Double.doubleToLongBits(doubles[index]) == Double.doubleToLongBits(source.doubles[row]);
            case BOOLEAN -> booleans[index] == source.booleans[row];
            case SYMBOL, VARCHAR -> texts[index] == null
                    ? source.texts[row] == null
                    : texts[index].equals(source.texts[row]);
        };
    }

    /** Sets value {@code index}, below the size last ensured, to value {@code row} of {@code source}, of this type. */
    public void set(final int index, final Vector source, final int row) {
        switch (type) {
            case LONG, TIMESTAMP -> longs[index] = source.longs[row];
            case DOUBLE -> doubles[index] = source.doubles[row];
            case BOOLEAN -> booleans[index] = source.booleans[row];
            case SYMBOL, VARCHAR -> texts[index] = source.texts[row];
        }
    }

    public CdbType type() {
        return type;
    }

    /** Writes value {@code index} to value {@code row} of {@code out}, a vector of this type. */
    public void write(final int index, final Vector out, final int row) {
        switch (type) {
            case LONG, TIMESTAMP -> out.longs[row] = longs[index];
            case DOUBLE -> out.doubles[row] = doubles[index];
            case BOOLEAN -> out.booleans[row] = booleans[index];
            case SYMBOL, VARCHAR -> out.texts[row] = texts[index];
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
            case LONG, TIMESTAMP -> longs = longs == null ? new long[newCapacity] : Arrays.copyOf(longs, newCapacity);
            case DOUBLE -> doubles = doubles == null
                    ? new double[newCapacity]
                    : Arrays.copyOf(doubles, newCapacity);
            case BOOLEAN -> booleans = booleans == null
                    ? new boolean[newCapacity]
                    : Arrays.copyOf(booleans, newCapacity);
            case SYMBOL, VARCHAR -> texts = texts == null
                    ? new String[newCapacity]
                    : Arrays.copyOf(texts, newCapacity);
        }
        capacity = newCapacity;
    }
}
