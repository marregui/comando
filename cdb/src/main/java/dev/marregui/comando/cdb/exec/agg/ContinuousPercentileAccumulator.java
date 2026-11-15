package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/**
 * {@code percentile_cont(x, p)} as QuestDB answers it: with the n non-NULL values sorted, position {@code p * (n - 1)}
 * interpolated linearly between its two neighbours; NULL for no value. LONG and TIMESTAMP values are read as doubles.
 */
final class ContinuousPercentileAccumulator implements Accumulator {

    private final double fraction;
    private int[] sizes = new int[16];
    private double[][] values = new double[16][];

    ContinuousPercentileAccumulator(final double fraction) {
        this.fraction = fraction;
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        if (value.doubles != null) {
            final double v = value.doubles[row];
            if (!Double.isNaN(v)) {
                append(group, v);
            }
        } else if (value.longs[row] != Vector.NULL_LONG) {
            append(group, value.longs[row]);
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        for (int i = 0; i < size; i++) {
            add(groups[i], value, i);
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
        final double position = fraction * (n - 1);
        final int lo = (int) Math.floor(position);
        final int hi = (int) Math.ceil(position);
        Aggregates.writeDouble(sorted[lo] + (position - lo) * (sorted[hi] - sorted[lo]), out, row);
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
