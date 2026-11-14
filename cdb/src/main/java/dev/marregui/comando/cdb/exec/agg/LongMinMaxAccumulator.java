package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code min} or {@code max} of LONG or TIMESTAMP; NULLs skipped. */
final class LongMinMaxAccumulator implements Accumulator {

    private final boolean max;
    // NULL_LONG until a group's first value: no value equals it, as it is the type's NULL
    private long[] best = filled(16);

    LongMinMaxAccumulator(final boolean max) {
        this.max = max;
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        accept(group, value.longs[row]);
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        final long[] in = value.longs;
        for (int i = 0; i < size; i++) {
            accept(groups[i], in[i]);
        }
    }

    @Override
    public void ensure(final int groups) {
        if (groups > best.length) {
            final int old = best.length;
            best = Arrays.copyOf(best, Values.grownCapacity(old, groups));
            Arrays.fill(best, old, best.length, Vector.NULL_LONG);
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeLong(best[group], out, row);
    }

    private static long[] filled(final int size) {
        final long[] a = new long[size];
        Arrays.fill(a, Vector.NULL_LONG);
        return a;
    }

    private void accept(final int group, final long v) {
        if (v != Vector.NULL_LONG) {
            final long current = best[group];
            if (current == Vector.NULL_LONG || (max ? v > current : v < current)) {
                best[group] = v;
            }
        }
    }
}
