package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/**
 * A percentile of DOUBLE values, NaN skipped, NULL for no value: the k-th smallest, k = {@code max(1, ceil(p * n))}.
 * This is {@code percentile_disc} exactly; for {@code approx_percentile} it is the exact value where QuestDB's
 * auto-ranging double histogram answers an approximation of it.
 */
final class DoublePercentileAccumulator implements Accumulator {


    private final double fraction;
    private int[] sizes = new int[16];
    private double[][] values = new double[16][];

    DoublePercentileAccumulator(final double fraction) {
        this.fraction = fraction;
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        final double v = value.doubles[row];
        if (!Double.isNaN(v)) {
            append(group, v);
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        final double[] in = value.doubles;
        for (int i = 0; i < size; i++) {
            if (!Double.isNaN(in[i])) {
                append(groups[i], in[i]);
            }
        }
    }

    @Override
    public void addRange(final int group, final Vector value, final int from, final int to) {
        final double[] list = reserve(group, to - from);
        final double[] in = value.doubles;
        int size = sizes[group];
        for (int i = from; i < to; i++) {
            if (!Double.isNaN(in[i])) {
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
    public void write(final int group, final Vector out, final int row) {
        final int n = sizes[group];
        if (n == 0) {
            Aggregates.writeDouble(Double.NaN, out, row);
            return;
        }
        Aggregates.writeDouble(Select.kthDouble(values[group], n, LongPercentileAccumulator.rank(fraction, n) - 1),
                out, row);
    }

    private void append(final int group, final double v) {
        reserve(group, 1)[sizes[group]++] = v;
    }

    /** The list of {@code group}, with room for {@code extra} more values. */
    private double[] reserve(final int group, final int extra) {
        final double[] list = Values.room(values[group], sizes[group] + extra);
        values[group] = list;
        return list;
    }
}
