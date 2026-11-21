package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code count()}: the rows of each group; {@code count(x)}: its non-NULL values. */
final class CountAccumulator implements Accumulator {

    private final boolean rows;
    private long[] counts = new long[16];

    CountAccumulator(final boolean rows) {
        this.rows = rows;
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        if (rows || !value.isNull(row)) {
            counts[group]++;
        }
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        if (rows) {
            for (int i = 0; i < size; i++) {
                counts[groups[i]]++;
            }
        } else {
            for (int i = 0; i < size; i++) {
                if (!value.isNull(i)) {
                    counts[groups[i]]++;
                }
            }
        }
    }

    @Override
    public void addRange(final int group, final Vector value, final int from, final int to) {
        if (rows) {
            counts[group] += to - from;
            return;
        }
        long n = 0;
        switch (value.type) {
            case LONG, TIMESTAMP -> {
                for (int i = from; i < to; i++) {
                    if (value.getLong(i) != Vector.NULL_LONG) {
                        n++;
                    }
                }
            }
            case DOUBLE -> {
                for (int i = from; i < to; i++) {
                    if (!Double.isNaN(value.getDouble(i))) {
                        n++;
                    }
                }
            }
            case BOOLEAN -> n = to - from;
            case SYMBOL, VARCHAR, JSON -> {
                for (int i = from; i < to; i++) {
                    if (!value.isNull(i)) {
                        n++;
                    }
                }
            }
        }
        counts[group] += n;
    }

    @Override
    public void ensure(final int groups) {
        if (groups > counts.length) {
            counts = Arrays.copyOf(counts, Values.grownCapacity(counts.length, groups));
        }
    }

    @Override
    public void merge(final int group, final Accumulator from, final int fromGroup) {
        counts[group] += ((CountAccumulator) from).counts[fromGroup];
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        Aggregates.writeLong(counts[group], out, row);
    }
}
