package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;

/** {@code avg} of LONG, TIMESTAMP or DOUBLE, as a double sum over the non-NULL count; NULL for no value. */
final class AvgAccumulator implements Accumulator {

    private long[] counts = new long[16];
    private double[] sums = new double[16];

    @Override
    public void add(final int group, final Vector value, final int row) {
        if (value.type == CdbType.DOUBLE) {
            final double v = value.getDouble(row);
            if (!Double.isNaN(v)) {
                sums[group] += v;
                counts[group]++;
            }
        } else {
            final long v = value.getLong(row);
            if (v != Vector.NULL_LONG) {
                sums[group] += v;
                counts[group]++;
            }
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        if (value.type == CdbType.DOUBLE) {
            for (int i = 0; i < size; i++) {
                final double v = value.getDouble(i);
                if (!Double.isNaN(v)) {
                    sums[groups[i]] += v;
                    counts[groups[i]]++;
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                final long v = value.getLong(i);
                if (v != Vector.NULL_LONG) {
                    sums[groups[i]] += v;
                    counts[groups[i]]++;
                }
            }
        }
    }

    @Override
    public void addRange(final int group, final Vector value, final int from, final int to) {
        // one addition per value in input order, as add makes them, so the sum rounds alike
        double sum = sums[group];
        long n = 0;
        if (value.type == CdbType.DOUBLE) {
            for (int i = from; i < to; i++) {
                final double v = value.getDouble(i);
                if (!Double.isNaN(v)) {
                    sum += v;
                    n++;
                }
            }
        } else {
            for (int i = from; i < to; i++) {
                final long v = value.getLong(i);
                if (v != Vector.NULL_LONG) {
                    sum += v;
                    n++;
                }
            }
        }
        sums[group] = sum;
        counts[group] += n;
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
    public void merge(final int group, final Accumulator from, final int fromGroup) {
        final AvgAccumulator other = (AvgAccumulator) from;
        // the frames' sums added in frame order, a different grouping of the additions than one per row: usually the
        // last digits differ, but cancellation or overflow can change the whole value (1, 1e17, -1e17 split
        // 1 | 1e17, -1e17)
        sums[group] += other.sums[fromGroup];
        counts[group] += other.counts[fromGroup];
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeDouble(counts[group] == 0 ? Double.NaN : sums[group] / counts[group], out, row);
    }
}
