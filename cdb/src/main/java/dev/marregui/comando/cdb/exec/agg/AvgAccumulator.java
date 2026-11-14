package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code avg} of LONG, TIMESTAMP or DOUBLE, as a double sum over the non-NULL count; NULL for no value. */
final class AvgAccumulator implements Accumulator {

    private long[] counts = new long[16];
    private double[] sums = new double[16];

    @Override
    public void add(final int group, final Vector value, final int row) {
        if (value.doubles != null) {
            final double v = value.doubles[row];
            if (!Double.isNaN(v)) {
                sums[group] += v;
                counts[group]++;
            }
        } else {
            final long v = value.longs[row];
            if (v != Vector.NULL_LONG) {
                sums[group] += v;
                counts[group]++;
            }
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        if (value.doubles != null) {
            final double[] in = value.doubles;
            for (int i = 0; i < size; i++) {
                final double v = in[i];
                if (!Double.isNaN(v)) {
                    sums[groups[i]] += v;
                    counts[groups[i]]++;
                }
            }
        } else {
            final long[] in = value.longs;
            for (int i = 0; i < size; i++) {
                final long v = in[i];
                if (v != Vector.NULL_LONG) {
                    sums[groups[i]] += v;
                    counts[groups[i]]++;
                }
            }
        }
    }

    @Override
    public void ensure(final int groups) {
        if (groups > sums.length) {
            final int capacity = Values.grownCapacity(sums.length, groups);
            sums = Arrays.copyOf(sums, capacity);
            counts = Arrays.copyOf(counts, capacity);
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeDouble(counts[group] == 0 ? Double.NaN : sums[group] / counts[group], out, row);
    }
}
