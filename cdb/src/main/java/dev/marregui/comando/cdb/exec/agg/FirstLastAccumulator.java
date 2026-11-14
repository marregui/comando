package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Vector;

/** {@code first} or {@code last}: the value of a group's first or last row in input order, NULL included. */
final class FirstLastAccumulator implements Accumulator {

    private final boolean last;
    private final Values values;
    private boolean[] seen = new boolean[16];

    FirstLastAccumulator(final CdbType type, final boolean last) {
        this.last = last;
        this.values = new Values(type);
        values.ensure(seen.length);
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        if (last || !seen[group]) {
            values.set(group, value, row);
            seen[group] = true;
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
        if (groups > seen.length) {
            seen = Arrays.copyOf(seen, Values.grownCapacity(seen.length, groups));
            values.ensure(seen.length);
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        if (!seen[group]) {
            out.setNull(row);
        } else if (out.type == values.type()) {
            values.write(group, out, row);
        } else {
            convert(group, out, row);
        }
    }

    private void convert(final int group, final Vector out, final int row) {
        final Vector one = Vector.of(values.type(), 1);
        values.write(group, one, 0);
        switch (values.type()) {
            case LONG, TIMESTAMP -> Aggregates.writeLong(one.longs[0], out, row);
            case DOUBLE -> Aggregates.writeDouble(one.doubles[0], out, row);
            default -> out.copy(one, 0, row);
        }
    }
}
