package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code min} or {@code max} of DOUBLE; NaN skipped. */
final class DoubleMinMaxAccumulator implements Accumulator {

    private final boolean max;
    // NaN until a group's first value
    private double[] best = filled(16);

    DoubleMinMaxAccumulator(final boolean max) {
        this.max = max;
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        accept(group, value.doubles[row]);
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        final double[] in = value.doubles;
        for (int i = 0; i < size; i++) {
            accept(groups[i], in[i]);
        }
    }

    @Override
    public void ensure(final int groups) {
        if (groups > best.length) {
            final int old = best.length;
            best = Arrays.copyOf(best, Values.grownCapacity(old, groups));
            Arrays.fill(best, old, best.length, Double.NaN);
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeDouble(best[group], out, row);
    }

    private static double[] filled(final int size) {
        final double[] a = new double[size];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    private void accept(final int group, final double v) {
        if (!Double.isNaN(v)) {
            final double current = best[group];
            if (Double.isNaN(current) || (max ? v > current : v < current)) {
                best[group] = v;
            }
        }
    }
}
