package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Accumulator;
import dev.marregui.comando.cdb.exec.agg.Aggregates;
import dev.marregui.comando.cdb.exec.agg.Values;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

/**
 * Window functions: the input's columns, then one per call, rows in input order. {@code lag(x[, n[, default]])} and
 * {@code row_number()} walk each partition in {@code ORDER BY} order (input order when there is none); an aggregate
 * with {@code ORDER BY} is running over the rows so far (a ROWS frame: rows that tie are still taken one at a time),
 * without it the value of its whole partition. Reads the whole input on the first {@link #next}.
 */
final class WindowCursor implements Cursor {

    private final Bound.WindowCall[] calls;
    private final Cursor child;
    private final List<Field> fields;
    private final long nowMicros;
    private final List<Page> pages = new ArrayList<>();
    private boolean closed;
    private int emitted;
    private int[] pageStarts;
    private Vector[] results;

    WindowCursor(final Cursor child, final Plan.Window spec, final long nowMicros) {
        this.child = child;
        try {
            fields = spec.fields();
            calls = spec.calls().toArray(new Bound.WindowCall[0]);
            this.nowMicros = nowMicros;
        } catch (final RuntimeException e) {
            child.close();
            throw e;
        }
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (results == null) {
            compute();
        }
        if (emitted >= pages.size()) {
            return null;
        }
        final Page page = pages.get(emitted);
        final int base = pageStarts[emitted];
        emitted++;
        final int inputs = page.columns.length;
        final Vector[] columns = new Vector[inputs + calls.length];
        System.arraycopy(page.columns, 0, columns, 0, inputs);
        for (int c = 0; c < calls.length; c++) {
            final Vector slice = Vector.of(results[c].type, page.size);
            for (int i = 0; i < page.size; i++) {
                slice.copy(results[c], base + i, i);
            }
            columns[inputs + c] = slice;
        }
        return new Page(columns, page.size);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            pages.clear();
            child.close();
        }
    }

    private static void convert(final Vector from, final int i, final Vector to, final int j) {
        if (from.type == to.type) {
            to.copy(from, i, j);
        } else if (from.isNull(i)) {
            to.setNull(j);
        } else if (to.type == CdbType.DOUBLE && from.longs != null) {
            to.doubles[j] = from.longs[i];
        } else if (to.longs != null && from.type == CdbType.DOUBLE) {
            to.longs[j] = (long) from.doubles[i];
        } else if (to.longs != null && from.longs != null) {
            to.longs[j] = from.longs[i];
        } else {
            throw new CdbException("cannot convert a window value [from=" + from.type + ", to=" + to.type + ']', -1);
        }
    }

    private static Vector constant(final Bound arg, final CdbType type) {
        final Vector v = Vector.of(type, 1);
        if (!(arg instanceof Bound.Constant(Object value, CdbType _))) {
            throw new CdbException("cannot take a non-constant window argument [type=" + type + ']', -1);
        }
        switch (value) {
            case null -> v.setNull(0);
            case Number n when type == CdbType.DOUBLE -> v.doubles[0] = n.doubleValue();
            case Number n when v.longs != null -> v.longs[0] = n.longValue();
            case Boolean b when type == CdbType.BOOLEAN -> v.booleans[0] = b;
            case String s when type.isText() -> v.texts[0] = s;
            default -> throw new CdbException("cannot use this window default [value=" + value + ", type=" + type
                    + ']', -1);
        }
        return v;
    }

    private static Vector nullOf(final CdbType type) {
        final Vector v = Vector.of(type, 1);
        v.setNull(0);
        return v;
    }

    private void compute() {
        int n = 0;
        Page page;
        while ((page = child.next()) != null) {
            pages.add(page);
            n = Math.addExact(n, page.size);
        }
        pageStarts = new int[pages.size()];
        for (int p = 1; p < pageStarts.length; p++) {
            pageStarts[p] = pageStarts[p - 1] + pages.get(p - 1).size;
        }
        final Vector[] computed = new Vector[calls.length];
        for (int c = 0; c < calls.length; c++) {
            computed[c] = evaluate(calls[c], n);
        }
        results = computed;
    }

    private Vector evaluate(final Bound.WindowCall call, final int n) {
        final int[] partition = new int[n];
        final int partitions = partition(call.partitionBy(), partition);
        final List<Plan.SortKey> orderBy = call.orderBy();
        final Vector[] sortColumns = new Vector[orderBy.size()];
        for (int k = 0; k < sortColumns.length; k++) {
            final int column = orderBy.get(k).column();
            sortColumns[k] = gather(new Bound.Column(column, fields.get(column).type()), n);
        }
        final Vector out = Vector.of(call.type(), Math.max(n, 1));
        switch (call.function()) {
            case "row_number" -> {
                final int[] order = order(partition, sortColumns, orderBy, n);
                long number = 0;
                for (int pos = 0; pos < n; pos++) {
                    final int row = order[pos];
                    number = pos > 0 && partition[order[pos - 1]] == partition[row] ? number + 1 : 1;
                    out.longs[row] = number;
                }
            }
            case "lag" -> lag(call, partition, order(partition, sortColumns, orderBy, n), out, n);
            default -> aggregate(call, partition, partitions, orderBy.isEmpty() ? null
                    : order(partition, sortColumns, orderBy, n), out, n);
        }
        return out;
    }

    private void aggregate(
            final Bound.WindowCall call,
            final int[] partition,
            final int partitions,
            final int[] order,
            final Vector out,
            final int n
    ) {
        final Accumulator accumulator = Aggregates.create(new Bound.Agg(call.function(), call.args(), false,
                call.type()));
        accumulator.ensure(Math.max(partitions, 1));
        final Vector value = call.args().isEmpty() ? null : gather(call.args().getFirst(), n);
        if (order != null) {
            for (final int row : order) {
                accumulator.add(partition[row], value, row);
                accumulator.write(partition[row], out, row);
            }
            return;
        }
        for (int row = 0; row < n; row++) {
            accumulator.add(partition[row], value, row);
        }
        for (int row = 0; row < n; row++) {
            accumulator.write(partition[row], out, row);
        }
    }

    private Vector gather(final Bound expr, final int n) {
        final VectorFn fn = Functions.compile(expr, nowMicros);
        final Vector all = Vector.of(expr.type(), Math.max(n, 1));
        for (int p = 0; p < pages.size(); p++) {
            final Page page = pages.get(p);
            final Vector values = fn.eval(page);
            final int base = pageStarts[p];
            for (int i = 0; i < page.size; i++) {
                all.copy(values, i, base + i);
            }
        }
        return all;
    }

    private void lag(
            final Bound.WindowCall call,
            final int[] partition,
            final int[] order,
            final Vector out,
            final int n
    ) {
        final List<Bound> args = call.args();
        if (args.isEmpty()) {
            throw new CdbException("cannot run lag without its argument [function=lag]", -1);
        }
        final Vector value = gather(args.getFirst(), n);
        final long offset = args.size() > 1 ? constant(args.get(1), CdbType.LONG).longs[0] : 1;
        if (offset < 0) {
            throw new CdbException("cannot lag by a negative offset [offset=" + offset + ']', -1);
        }
        final Vector fallback = args.size() > 2 ? constant(args.get(2), call.type()) : nullOf(call.type());
        int runStart = 0;
        for (int pos = 0; pos < n; pos++) {
            final int row = order[pos];
            if (pos > 0 && partition[order[pos - 1]] != partition[row]) {
                runStart = pos;
            }
            if (pos - runStart >= offset) {
                convert(value, order[(int) (pos - offset)], out, row);
            } else {
                out.copy(fallback, 0, row);
            }
        }
    }

    private int[] order(final int[] partition, final Vector[] columns, final List<Plan.SortKey> keys, final int n) {
        return AggSort.sorted(n, (a, b) -> {
            final int byPartition = Integer.compare(partition[a], partition[b]);
            if (byPartition != 0) {
                return byPartition;
            }
            for (int k = 0; k < columns.length; k++) {
                final int c = Values.compare(columns[k], a, b);
                if (c != 0) {
                    return keys.get(k).descending() ? -c : c;
                }
            }
            return 0;
        });
    }

    private int partition(final List<Bound> partitionBy, final int[] partition) {
        if (partitionBy.isEmpty()) {
            return 1;
        }
        final List<CdbType> types = new ArrayList<>();
        final VectorFn[] fns = new VectorFn[partitionBy.size()];
        for (int k = 0; k < fns.length; k++) {
            types.add(partitionBy.get(k).type());
            fns[k] = Functions.compile(partitionBy.get(k), nowMicros);
        }
        final AggKeys keys = new AggKeys(types);
        final Vector[] vectors = new Vector[fns.length];
        for (int p = 0; p < pages.size(); p++) {
            final Page page = pages.get(p);
            for (int k = 0; k < fns.length; k++) {
                vectors[k] = fns[k].eval(page);
            }
            final int base = pageStarts[p];
            for (int i = 0; i < page.size; i++) {
                partition[base + i] = keys.groupOf(vectors, i);
            }
        }
        return keys.size();
    }
}
