package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code sum} of LONG or TIMESTAMP: NULL for a group with no non-NULL value; wraps on overflow, as a long does. */
final class LongSumAccumulator implements Accumulator {

    private boolean[] seen = new boolean[16];
    private long[] sums = new long[16];

    @Override
    public void add(final int group, final Vector value, final int row) {
        final long v = value.longs[row];
        if (v != Vector.NULL_LONG) {
            sums[group] += v;
            seen[group] = true;
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        final long[] in = value.longs;
        for (int i = 0; i < size; i++) {
            final long v = in[i];
            if (v != Vector.NULL_LONG) {
                sums[groups[i]] += v;
                seen[groups[i]] = true;
            }
        }
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
        Aggregates.writeLong(seen[group] ? sums[group] : Vector.NULL_LONG, out, row);
    }
}
