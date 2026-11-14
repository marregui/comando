package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Accumulator;
import dev.marregui.comando.cdb.exec.agg.Aggregates;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;

/** Groups by key tuple and the accumulators of each aggregate per group, fed a page at a time. */
final class AggTable {

    private final Accumulator[] accumulators;
    // the first argument of each aggregate, null for count()
    private final VectorFn[] args;
    private final int[] groupOfRow = new int[Page.CAPACITY];
    private final AggKeys keys;

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

    /** Adds the rows of {@code page}, whose key columns are {@code keyVectors}. */
    void add(final Page page, final Vector[] keyVectors) {
        final int rows = page.size;
        for (int i = 0; i < rows; i++) {
            groupOfRow[i] = keys.groupOf(keyVectors, i);
        }
        final int groups = keys.size();
        for (int a = 0; a < accumulators.length; a++) {
            accumulators[a].ensure(groups);
            accumulators[a].addAll(groupOfRow, args[a] == null ? null : args[a].eval(page), rows);
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
}
