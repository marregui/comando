package dev.marregui.comando.cdb.store;

import dev.marregui.comando.cdb.exec.Vector;

/**
 * One partition of a {@link TableReader}'s snapshot: its rows are in designated-timestamp order and do not change for
 * the life of the reader. A column added after the partition was written reads as NULL. Reads copy {@code n} values
 * from row {@code from} into {@code dst} from index 0; {@code from + n} is at most {@link #rowCount()}.
 */
public interface PartitionReader {

    /** The inclusive start of the partition's time range, microseconds (its floor by the table's partitioning). */
    long lowerBound();

    /** The smallest designated timestamp in the partition. */
    long minTimestamp();

    /** The largest designated timestamp in the partition. */
    long maxTimestamp();

    long rowCount();

    /**
     * False only when no row of {@code column} is NULL; true when one may be. The default answers true, which never
     * misleads a caller that skips work for a NULL-free column.
     */
    default boolean mayHoldNull(final int column) {
        return true;
    }

    /** LONG and TIMESTAMP columns. */
    void longs(int column, long from, int n, long[] dst);

    /** DOUBLE columns. */
    void doubles(int column, long from, int n, double[] dst);

    /** BOOLEAN columns. */
    void booleans(int column, long from, int n, boolean[] dst);

    /** SYMBOL (resolved through the table's dictionary) and VARCHAR columns; NULL as a null reference. */
    void texts(int column, long from, int n, String[] dst);

    /**
     * Copies {@code n} values of {@code column} from row {@code from} into {@code dst} from index {@code at}; {@code dst}
     * is of the column's type. The vector form of {@link #longs}, {@link #doubles}, {@link #booleans} and {@link #texts}.
     */
    default void read(final int column, final long from, final int n, final Vector dst, final int at) {
        switch (dst.type) {
            case LONG, TIMESTAMP -> {
                final long[] values = new long[n];
                longs(column, from, n, values);
                for (int i = 0; i < n; i++) {
                    dst.setLong(at + i, values[i]);
                }
            }
            case DOUBLE -> {
                final double[] values = new double[n];
                doubles(column, from, n, values);
                for (int i = 0; i < n; i++) {
                    dst.setDouble(at + i, values[i]);
                }
            }
            case BOOLEAN -> {
                final boolean[] values = new boolean[n];
                booleans(column, from, n, values);
                for (int i = 0; i < n; i++) {
                    dst.setBoolean(at + i, values[i]);
                }
            }
            case SYMBOL, VARCHAR -> {
                final String[] values = new String[n];
                texts(column, from, n, values);
                for (int i = 0; i < n; i++) {
                    dst.setText(at + i, values[i]);
                }
            }
        }
    }

    /**
     * Copies the values of {@code column} at the rows {@code rows[from + k]}, {@code k} in {@code [0, n)}, into
     * {@code dst} from index {@code at}; the rows are any rows of the partition, in any order (a late read fetches the
     * rows a sort kept).
     */
    default void fetch(final int column, final long[] rows, final int from, final int n, final Vector dst,
            final int at) {
        for (int k = 0; k < n; k++) {
            read(column, rows[from + k], 1, dst, at + k);
        }
    }

    /**
     * The first row whose designated timestamp, column {@code timestampColumn}, is at least {@code micros}; {@link
     * #rowCount()} when none is. The rows are in timestamp order, so this is a binary search.
     */
    default long rowAtOrAfter(final int timestampColumn, final long micros) {
        final long[] one = new long[1];
        long lo = 0;
        long hi = rowCount();
        while (lo < hi) {
            final long mid = (lo + hi) >>> 1;
            longs(timestampColumn, mid, 1, one);
            if (one[0] < micros) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * The pre-filter of a scan over a LONG or TIMESTAMP column: writes to {@code sel} the offsets, from {@code from},
     * of the rows of {@code [from, from + n)} that may satisfy {@code value op constant}, ascending, and returns their
     * count. It may keep a row that fails; it never drops one that holds, and it keeps every NULL, so the scan's filter,
     * which still runs, decides. {@code sel} has room for {@code n}.
     */
    default int selectLong(final int column, final long from, final int n, final Compare op, final long constant,
            final int[] sel) {
        final long[] values = new long[n];
        longs(column, from, n, values);
        int count = 0;
        for (int i = 0; i < n; i++) {
            final long v = values[i];
            if (v == Long.MIN_VALUE || op.holds(Long.compare(v, constant))) {
                sel[count++] = i;
            }
        }
        return count;
    }

    /** {@link #selectLong} for a DOUBLE column; a NaN (NULL) is kept. */
    default int selectDouble(final int column, final long from, final int n, final Compare op, final double constant,
            final int[] sel) {
        final double[] values = new double[n];
        doubles(column, from, n, values);
        int count = 0;
        for (int i = 0; i < n; i++) {
            final double v = values[i];
            if (Double.isNaN(v) || op.holds(Double.compare(v, constant))) {
                sel[count++] = i;
            }
        }
        return count;
    }

    /**
     * {@link #selectLong} for a SYMBOL or VARCHAR column, {@link Compare#EQ} or {@link Compare#NE} and a non-NULL
     * {@code constant}, but exact, as the filter compares: {@code =} keeps the rows equal to it, {@code !=} the others,
     * NULLs included (a comparison with one NULL side holds only for {@code !=}).
     */
    default int selectText(final int column, final long from, final int n, final Compare op, final String constant,
            final int[] sel) {
        final String[] values = new String[n];
        texts(column, from, n, values);
        int count = 0;
        for (int i = 0; i < n; i++) {
            final String v = values[i];
            if (op.holds(v != null && v.equals(constant) ? 0 : 1)) {
                sel[count++] = i;
            }
        }
        return count;
    }

    /** {@link #longs} of the rows {@code from + sel[i]}, {@code i} in {@code [0, count)}, into {@code dst[i]}. */
    default void longsAt(final int column, final long from, final int[] sel, final int count, final long[] dst) {
        final long[] one = new long[1];
        for (int i = 0; i < count; i++) {
            longs(column, from + sel[i], 1, one);
            dst[i] = one[0];
        }
    }

    /** {@link #longsAt} for {@link #doubles}. */
    default void doublesAt(final int column, final long from, final int[] sel, final int count, final double[] dst) {
        final double[] one = new double[1];
        for (int i = 0; i < count; i++) {
            doubles(column, from + sel[i], 1, one);
            dst[i] = one[0];
        }
    }

    /** {@link #longsAt} for {@link #booleans}. */
    default void booleansAt(final int column, final long from, final int[] sel, final int count,
            final boolean[] dst) {
        final boolean[] one = new boolean[1];
        for (int i = 0; i < count; i++) {
            booleans(column, from + sel[i], 1, one);
            dst[i] = one[0];
        }
    }

    /** {@link #longsAt} for {@link #texts}. */
    default void textsAt(final int column, final long from, final int[] sel, final int count, final String[] dst) {
        final String[] one = new String[1];
        for (int i = 0; i < count; i++) {
            texts(column, from + sel[i], 1, one);
            dst[i] = one[0];
        }
    }
}
