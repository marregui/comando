package dev.marregui.comando.cdb.exec;

import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Accumulator;
import dev.marregui.comando.cdb.exec.agg.Aggregates;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;

/** Groups by key tuple and the accumulators of each aggregate per group, fed a page at a time. */
final class AggTable {

    // a page whose runs average fewer rows than this goes to the accumulators row by row: a call per accumulator and
    // run would cost more than the per-row loop it replaces (not measured)
    private static final int MIN_MEAN_RUN = 4;

    private final Accumulator[] accumulators;
    // the first argument of each aggregate, null for count()
    private final VectorFn[] args;
    private final int[] groupOfRow = new int[Page.CAPACITY];
    private final AggKeys keys;
    // the group and the first row of each run of the page last added; runStarts ends with the page's size
    private final int[] runGroups = new int[Page.CAPACITY];
    private final int[] runStarts = new int[Page.CAPACITY + 1];

    AggTable(final List<CdbType> keyTypes, final List<Bound.Agg> aggs, final long nowMicros) {
        keys = new AggKeys(keyTypes);
        accumulators = new Accumulator[aggs.size()];
        args = new VectorFn[aggs.size()];
        for (int a = 0; a < accumulators.length; a++) {
            final Bound.Agg agg = aggs.get(a);
            accumulators[a] = Aggregates.create(agg);
            args[a] = agg.args().isEmpty() ? null : Functions.compile(agg.args().getFirst(), nowMicros);
        }
    }

    /**
     * Adds the rows of {@code page}, whose key columns are {@code keyVectors}. Consecutive rows of one key tuple form a
     * run whose group is looked up once; when the runs are long enough each goes to an accumulator as one range. A
     * single LONG or TIMESTAMP key (the bucket of a SAMPLE BY without keys) finds its runs on its values, one
     * comparison per row. Either way every group receives its rows in input order, so the results do not depend on
     * the runs.
     */
    void add(final Page page, final Vector[] keyVectors) {
        final int rows = page.size;
        final boolean longKey = keyVectors.length == 1
                && (keyVectors[0].type == CdbType.LONG || keyVectors[0].type == CdbType.TIMESTAMP);
        final Vector single = longKey ? keyVectors[0] : null;
        int runs = 0;
        int start = 0;
        while (start < rows) {
            final int group = keys.groupOf(keyVectors, start);
            int end = start + 1;
            if (single != null) {
                final long key = single.getLong(start);
                while (end < rows && single.getLong(end) == key) {
                    end++;
                }
            } else {
                while (end < rows && keys.sameRows(keyVectors, start, end)) {
                    end++;
                }
            }
            Arrays.fill(groupOfRow, start, end, group);
            runStarts[runs] = start;
            runGroups[runs] = group;
            runs++;
            start = end;
        }
        runStarts[runs] = rows;
        final int groups = keys.size();
        final boolean ranges = (long) runs * MIN_MEAN_RUN <= rows;
        for (int a = 0; a < accumulators.length; a++) {
            final Accumulator accumulator = accumulators[a];
            final Vector value = args[a] == null ? null : args[a].eval(page);
            accumulator.ensure(groups);
            if (ranges) {
                for (int r = 0; r < runs; r++) {
                    accumulator.addRange(runGroups[r], value, runStarts[r], runStarts[r + 1]);
                }
            } else {
                accumulator.addAll(groupOfRow, value, rows);
            }
        }
    }

    /** Makes sure group 0 exists, so a table without keys answers one row for no input. */
    void addEmptyGroup() {
        keys.groupOf(new Vector[0], 0);
        for (final Accumulator accumulator : accumulators) {
            accumulator.ensure(keys.size());
        }
    }

    int aggregates() {
        return accumulators.length;
    }

    int groups() {
        return keys.size();
    }

    /** The group of row {@code row} of the page last added. */
    int groupOfRow(final int row) {
        return groupOfRow[row];
    }

    void writeAggregate(final int group, final int aggregate, final Vector out, final int row) {
        accumulators[aggregate].write(group, out, row);
    }

    void writeKey(final int group, final int key, final Vector out, final int row) {
        keys.write(group, key, out, row);
    }

    /**
     * Adds {@code partial}, a table of the same keys and aggregates over a later frame's rows: each of its groups is
     * merged into the group of the same keys, a group new here appended in {@code partial}'s first-seen order, so the
     * groups and their order are the serial table's. The empty group of a table without keys merges as any group.
     * {@code partial} keeps its results and must not be this table.
     */
    void merge(final AggTable partial) {
        assert partial != this && partial.accumulators.length == accumulators.length
                : "cannot merge a table of other aggregates";
        final int count = partial.keys.size();
        // the group here of each of the partial's, found or added in the partial's first-seen order
        final int[] into = new int[count];
        for (int g = 0; g < count; g++) {
            into[g] = keys.groupOf(partial.keys, g);
        }
        final int groups = keys.size();
        for (int a = 0; a < accumulators.length; a++) {
            final Accumulator accumulator = accumulators[a];
            final Accumulator from = partial.accumulators[a];
            accumulator.ensure(groups);
            for (int g = 0; g < count; g++) {
                accumulator.merge(into[g], from, g);
            }
        }
    }
}
