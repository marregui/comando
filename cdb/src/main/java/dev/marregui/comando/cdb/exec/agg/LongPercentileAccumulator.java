package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.exec.Vector;

/**
 * A percentile of LONG or TIMESTAMP values, NULLs skipped, NULL for no value. Keeps each group's values and selects the
 * k-th in place at {@link #write}. With a precision ({@code approx_percentile}, {@code approx_median}) the result is what
 * QuestDB's HdrHistogram of that many significant digits answers: the highest value equivalent to the k-th smallest,
 * the lowest for percentile 0, values below 0 refused. Without one ({@code percentile_disc}) it is the k-th smallest.
 * Both take k as {@code max(1, ceil(p * n))}.
 */
final class LongPercentileAccumulator implements Accumulator {


    private final double fraction;
    // significant digits of the histogram, -1 for the exact value
    private final int precision;
    private int[] sizes = new int[16];
    private long[][] values = new long[16][];

    LongPercentileAccumulator(final double fraction, final int precision) {
        this.fraction = fraction;
        this.precision = precision;
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        final long v = value.longs[row];
        if (v != Vector.NULL_LONG) {
            append(group, v);
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        final long[] in = value.longs;
        for (int i = 0; i < size; i++) {
            if (in[i] != Vector.NULL_LONG) {
                append(groups[i], in[i]);
            }
        }
    }

    @Override
    public void addRange(final int group, final Vector value, final int from, final int to) {
        final long[] list = reserve(group, to - from);
        final long[] in = value.longs;
        int size = sizes[group];
        for (int i = from; i < to; i++) {
            if (in[i] != Vector.NULL_LONG) {
                list[size++] = in[i];
            }
        }
        sizes[group] = size;
    }

    @Override
    public void ensure(final int groups) {
        if (groups > sizes.length) {
            final int capacity = Values.grownCapacity(sizes.length, groups);
            sizes = Arrays.copyOf(sizes, capacity);
            values = Arrays.copyOf(values, capacity);
        }
    }

    @Override
    public void merge(final int group, final Accumulator from, final int fromGroup) {
        final LongPercentileAccumulator other = (LongPercentileAccumulator) from;
        final int n = other.sizes[fromGroup];
        if (n > 0) {
            System.arraycopy(other.values[fromGroup], 0, reserve(group, n), sizes[group], n);
            sizes[group] += n;
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        final int n = sizes[group];
        if (n == 0) {
            Aggregates.writeLong(Vector.NULL_LONG, out, row);
            return;
        }
        final long[] list = values[group];
        if (precision < 0) {
            Aggregates.writeLong(Select.kthLong(list, n, rank(fraction, n) - 1), out, row);
            return;
        }
        final long least = Select.minLong(list, n);
        if (least < 0) {
            throw new CdbException("cannot take an approximate percentile of a negative value [value=" + least + ']',
                    -1);
        }
        final double percent = Math.min(Math.max(Math.nextAfter(fraction * 100.0, Double.NEGATIVE_INFINITY), 0.0),
                100.0);
        final int k = (int) Math.max(1, Math.ceil(percent / 100.0 * n));
        final long kth = Select.kthLong(list, n, k - 1);
        Aggregates.writeLong(fraction == 0.0 ? lowestEquivalent(kth, precision) : highestEquivalent(kth, precision),
                out, row);
    }

    /** The 1-based rank of percentile {@code p} among {@code n} sorted values. */
    static int rank(final double p, final int n) {
        return (int) Math.max(1, Math.min(n, Math.ceil(p * n)));
    }

    /** The lowest value HdrHistogram of {@code digits} significant digits holds in one bucket with {@code v}. */
    static long lowestEquivalent(final long v, final int digits) {
        final int bucket = bucketIndex(v, digits);
        return (v >>> bucket) << bucket;
    }

    /** The highest value HdrHistogram of {@code digits} significant digits holds in one bucket with {@code v}. */
    static long highestEquivalent(final long v, final int digits) {
        return lowestEquivalent(v, digits) + (1L << bucketIndex(v, digits)) - 1;
    }

    private static int bucketIndex(final long v, final int digits) {
        // HdrHistogram with a lowest discernible value of 1: sub-buckets resolve 2 * 10^digits values
        final long largestSingleUnitResolution = 2 * (long) Math.pow(10, digits);
        final int subBucketCountMagnitude = 64 - Long.numberOfLeadingZeros(largestSingleUnitResolution - 1);
        final int subBucketHalfCountMagnitude = Math.max(subBucketCountMagnitude, 1) - 1;
        final long subBucketMask = (1L << (subBucketHalfCountMagnitude + 1)) - 1;
        final int leadingZeroCountBase = 64 - subBucketHalfCountMagnitude - 1;
        return leadingZeroCountBase - Long.numberOfLeadingZeros(v | subBucketMask);
    }

    private void append(final int group, final long v) {
        reserve(group, 1)[sizes[group]++] = v;
    }

    /** The list of {@code group}, with room for {@code extra} more values. */
    private long[] reserve(final int group, final int extra) {
        final long[] list = Values.room(values[group], sizes[group] + extra);
        values[group] = list;
        return list;
    }
}
