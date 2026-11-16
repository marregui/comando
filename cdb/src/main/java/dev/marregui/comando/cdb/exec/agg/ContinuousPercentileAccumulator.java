package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.CdbType;
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
        if (value.type == CdbType.DOUBLE) {
            final double v = value.getDouble(row);
            if (!Double.isNaN(v)) {
                append(group, v);
            }
        } else {
            final long v = value.getLong(row);
            if (v != Vector.NULL_LONG) {
                append(group, v);
            }
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        for (int i = 0; i < size; i++) {
            add(groups[i], value, i);
        }
    }

    @Override
    public void addRange(final int group, final Vector value, final int from, final int to) {
        final double[] list = reserve(group, to - from);
        int size = sizes[group];
        if (value.type == CdbType.DOUBLE) {
            for (int i = from; i < to; i++) {
                final double v = value.getDouble(i);
                if (!Double.isNaN(v)) {
                    list[size++] = v;
                }
            }
        } else {
            for (int i = from; i < to; i++) {
                final long v = value.getLong(i);
                if (v != Vector.NULL_LONG) {
                    list[size++] = v;
                }
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
        final ContinuousPercentileAccumulator other = (ContinuousPercentileAccumulator) from;
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
            Aggregates.writeDouble(Double.NaN, out, row);
            return;
        }
        final double[] list = values[group];
        final double position = fraction * (n - 1);
        final int lo = (int) Math.floor(position);
        final int hi = (int) Math.ceil(position);
        // after the selection every value past lo is at least the low one, so the high one is their least
        final double low = Select.kthDouble(list, n, lo);
        final double high = hi == lo ? low : Select.minDouble(list, hi, n);
        Aggregates.writeDouble(low + (position - lo) * (high - low), out, row);
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
