package dev.marregui.comando.cdb.exec.agg;

import java.util.Arrays;

import dev.marregui.comando.cdb.exec.Vector;

/** {@code min} or {@code max} of SYMBOL or VARCHAR by {@link String#compareTo}; NULLs skipped. */
final class TextMinMaxAccumulator implements Accumulator {

    private final boolean max;
    private String[] best = new String[16];

    TextMinMaxAccumulator(final boolean max) {
        this.max = max;
    }

    @Override
    public void add(final int group, final Vector value, final int row) {
        accept(group, value, row);
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        for (int i = 0; i < size; i++) {
            accept(groups[i], value, i);
        }
    }

    @Override
    public void ensure(final int groups) {
        if (groups > best.length) {
            best = Arrays.copyOf(best, Values.grownCapacity(best.length, groups));
        }
    }

    @Override
    public void merge(final int group, final Accumulator from, final int fromGroup) {
        accept(group, ((TextMinMaxAccumulator) from).best[fromGroup]);
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        out.setText(row, best[group]);
    }

    // the value kept is a String copied out of the page, so it outlives the page
    private void accept(final int group, final Vector value, final int row) {
        if (!value.isNull(row)) {
            final String current = best[group];
            if (current == null || (max ? value.textCompare(row, current) > 0 : value.textCompare(row, current) < 0)) {
                best[group] = value.textAt(row);
            }
        }
    }

    private void accept(final int group, final String v) {
        if (v != null) {
            final String current = best[group];
            if (current == null || (max ? v.compareTo(current) > 0 : v.compareTo(current) < 0)) {
                best[group] = v;
            }
        }
    }
}
