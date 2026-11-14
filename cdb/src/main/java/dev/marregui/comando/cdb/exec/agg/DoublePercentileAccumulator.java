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
        final double[] sorted = Arrays.copyOf(values[group], n);
        Arrays.sort(sorted);
        Aggregates.writeDouble(sorted[LongPercentileAccumulator.rank(fraction, n) - 1], out, row);
    }

    private void append(final int group, final double v) {
        double[] list = values[group];
        if (list == null) {
            list = new double[8];
            values[group] = list;
        } else if (sizes[group] == list.length) {
            list = Arrays.copyOf(list, Values.grownCapacity(list.length, list.length + 1));
            values[group] = list;
        }
        list[sizes[group]++] = v;
    }
}
