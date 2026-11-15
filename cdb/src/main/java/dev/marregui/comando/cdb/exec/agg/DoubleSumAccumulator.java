package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code sum} of DOUBLE: NaN skipped, NULL for a group with no other value. */
final class DoubleSumAccumulator implements Accumulator {

    private boolean[] seen = new boolean[16];
    private double[] sums = new double[16];

    @Override
    public void add(final int group, final Vector value, final int row) {
        final double v = value.doubles[row];
        if (!Double.isNaN(v)) {
            sums[group] += v;
            seen[group] = true;
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        final double[] in = value.doubles;
        for (int i = 0; i < size; i++) {
            final double v = in[i];
            if (!Double.isNaN(v)) {
                sums[groups[i]] += v;
                seen[groups[i]] = true;
            }
        }
    }

    @Override
    public void addRange(final int group, final Vector value, final int from, final int to) {
        final double[] in = value.doubles;
        // one addition per value in input order, as add makes them, so the sum rounds alike
        double sum = sums[group];
        boolean any = false;
        for (int i = from; i < to; i++) {
            final double v = in[i];
            if (!Double.isNaN(v)) {
                sum += v;
                any = true;
            }
        }
        sums[group] = sum;
        seen[group] |= any;
    }

    @Override
    public void ensure(final int groups) {
        if (groups > sums.length) {
            final int capacity = Values.grownCapacity(sums.length, groups);
            sums = Arrays.copyOf(sums, capacity);
            seen = Arrays.copyOf(seen, capacity);
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeDouble(seen[group] ? sums[group] : Double.NaN, out, row);
    }
}
