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
        accept(group, value.texts[row]);
    }

    @Override
    public void addAll(final int[] groups, final Vector value, final int size) {
        final String[] in = value.texts;
        for (int i = 0; i < size; i++) {
            accept(groups[i], in[i]);
        }
    }

    @Override
    public void ensure(final int groups) {
        if (groups > best.length) {
            best = Arrays.copyOf(best, Values.grownCapacity(best.length, groups));
        }
    }

    @Override
    public void write(final int group, final Vector out, final int row) {
        out.texts[row] = best[group];
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
