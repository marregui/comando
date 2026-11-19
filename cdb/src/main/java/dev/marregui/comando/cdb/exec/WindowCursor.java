package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.Accumulator;
import dev.marregui.comando.cdb.exec.agg.Aggregates;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

/**
 * Window functions: the input's columns, then one per call, rows in input order. {@code lag(x[, n[, default]])},
 * {@code lead(x[, n[, default]])} and {@code row_number()} walk each partition in {@code ORDER BY} order (input order
 * when there is none); an aggregate with {@code ORDER BY} is running over the rows so far (a ROWS frame: rows that tie
 * are still taken one at a time), without it the value of its whole partition. {@code first_value(x)} and
 * {@code last_value(x)} are the aggregates {@code first} and {@code last} over that frame, NULL included: with
 * {@code ORDER BY} the partition's first row and the current row, without it the partition's first and last row.
 * Reads the whole input on the first {@link #next}.
 */
final class WindowCursor implements Cursor {

    private final Bound.WindowCall[] calls;
    private final Cursor child;
    private final List<Field> fields;
    private final long nowMicros;
    /** Where a call with PARTITION BY computes its partitions; null computes every call on the calling thread. */
    private final QueryContext ctx;
    private final List<Page> pages = new ArrayList<>();
    private boolean closed;
    private int emitted;
    private int[] pageStarts;
    private Vector[] results;

    WindowCursor(final Cursor child, final Plan.Window spec, final long nowMicros) {
        this(child, spec, nowMicros, null);
    }

    private WindowCursor(final Cursor child, final Plan.Window spec, final long nowMicros, final QueryContext ctx) {
        this.child = child;
        this.ctx = ctx;
        try {
            fields = spec.fields();
            calls = spec.calls().toArray(new Bound.WindowCall[0]);
            this.nowMicros = nowMicros;
        } catch (final RuntimeException e) {
            child.close();
            throw e;
        }
    }

    /**
     * Window functions over {@code source}'s frames: the input read per frame through the exchange; a call with
     * PARTITION BY computes each partition as its own task on the source's pool, one without it on the consumer in one
     * ordered pass. The answer is the serial operator's, the same bits whatever the pool's size: a partition's rows are
     * taken in the order the serial operator takes them. Owns and closes the source.
     */
    static Cursor parallel(final FrameSource source, final Plan.Window spec, final long nowMicros) {
        return new WindowCursor(new ExchangeCursor(source), spec, nowMicros, source.context());
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
        } else if (to.type == CdbType.DOUBLE && isLong(from.type)) {
            to.setDouble(j, from.getLong(i));
        } else if (isLong(to.type) && from.type == CdbType.DOUBLE) {
            to.setLong(j, (long) from.getDouble(i));
        } else if (isLong(to.type) && isLong(from.type)) {
            to.setLong(j, from.getLong(i));
        } else {
            throw new CdbException("cannot convert a window value [from=" + from.type + ", to=" + to.type + ']', -1);
        }
    }

    private static boolean isLong(final CdbType type) {
        return type == CdbType.LONG || type == CdbType.TIMESTAMP;
    }

    private static Vector constant(final Bound arg, final CdbType type) {
        final Vector v = Vector.of(type, 1);
        if (!(arg instanceof Bound.Constant(Object value, CdbType _))) {
            throw new CdbException("cannot take a non-constant window argument [type=" + type + ']', -1);
        }
        switch (value) {
            case null -> v.setNull(0);
            case Number n when type == CdbType.DOUBLE -> v.setDouble(0, n.doubleValue());
            case Number n when isLong(type) -> v.setLong(0, n.longValue());
            case Boolean b when type == CdbType.BOOLEAN -> v.setBoolean(0, b);
            case String s when type.isText() -> v.setText(0, s);
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
            computed[c] = ctx != null && !calls[c].partitionBy().isEmpty()
                    ? evaluateByPartition(calls[c], n)
                    : evaluate(calls[c], n);
        }
        results = computed;
    }

    private Vector evaluate(final Bound.WindowCall call, final int n) {
        final int[] partition = new int[n];
        final int partitions = partition(call.partitionBy(), partition);
        final List<Plan.SortKey> orderBy = call.orderBy();
        final Vector[] sortColumns = sortColumns(orderBy, n);
        final Vector out = Vector.of(call.type(), Math.max(n, 1));
        switch (call.function()) {
            case "row_number" -> {
                final int[] order = order(partition, sortColumns, orderBy, n);
                long number = 0;
                for (int pos = 0; pos < n; pos++) {
                    final int row = order[pos];
                    number = pos > 0 && partition[order[pos - 1]] == partition[row] ? number + 1 : 1;
                    out.setLong(row, number);
                }
            }
            case "lag", "lead" -> shift(call, partition, order(partition, sortColumns, orderBy, n), out, n);
            default -> aggregate(call, partition, partitions, orderBy.isEmpty() ? null
                    : order(partition, sortColumns, orderBy, n), out, n);
        }
        return out;
    }

    /**
     * {@code call}, which has PARTITION BY, one task per partition on the context's pool. The partitions, the sort
     * keys and the argument are read here; a task sorts its partition's rows, in input order, by the call's ORDER BY
     * (stable, as the serial sort's ties) and writes only those rows of the output, so the tasks share no state but
     * what they read. A text output is written by one thread, the consumer: its values share one byte buffer.
     */
    private Vector evaluateByPartition(final Bound.WindowCall call, final int n) {
        final int[] partition = new int[n];
        final int partitions = partition(call.partitionBy(), partition);
        // members[starts[p] .. starts[p + 1]) are partition p's rows in input order
        final int[] starts = new int[partitions + 1];
        for (int row = 0; row < n; row++) {
            starts[partition[row] + 1]++;
        }
        for (int p = 0; p < partitions; p++) {
            starts[p + 1] += starts[p];
        }
        final int[] members = new int[n];
        final int[] fill = Arrays.copyOf(starts, partitions);
        for (int row = 0; row < n; row++) {
            members[fill[partition[row]]++] = row;
        }
        final List<Plan.SortKey> orderBy = call.orderBy();
        final Vector[] sortColumns = sortColumns(orderBy, n);
        final Vector out = Vector.of(call.type(), Math.max(n, 1));
        final IntFunction<Boolean> task = switch (call.function()) {
            case "row_number" -> p -> {
                final int[] order = partitionOrder(members, starts[p], starts[p + 1], sortColumns, orderBy);
                for (int pos = 0; pos < order.length; pos++) {
                    out.setLong(order[pos], pos + 1);
                }
                return Boolean.TRUE;
            };
            case "lag", "lead" -> {
                final Shift shift = shift(call, n);
                yield p -> {
                    final int[] order = partitionOrder(members, starts[p], starts[p + 1], sortColumns, orderBy);
                    shift.write(order, 0, order.length, out);
                    return Boolean.TRUE;
                };
            }
            default -> {
                final Bound.Agg agg = aggregateOf(call);
                // created here once so an unknown function fails on this thread before any task, as the serial one
                Aggregates.create(agg);
                final Vector value = call.args().isEmpty() ? null : gather(call.args().getFirst(), n);
                yield p -> {
                    final Accumulator accumulator = Aggregates.create(agg);
                    accumulator.ensure(1);
                    final int[] order = partitionOrder(members, starts[p], starts[p + 1], sortColumns, orderBy);
                    if (!orderBy.isEmpty()) {
                        for (final int row : order) {
                            accumulator.add(0, value, row);
                            accumulator.write(0, out, row);
                        }
                        return Boolean.TRUE;
                    }
                    for (final int row : order) {
                        accumulator.add(0, value, row);
                    }
                    for (final int row : order) {
                        accumulator.write(0, out, row);
                    }
                    return Boolean.TRUE;
                };
            }
        };
        if (partitions > 0 && call.type().isText()) {
            // a text vector appends every value to one byte buffer, so tasks writing rows of it at once would race:
            // the partitions run here, in order, which gives the same values the pool would
            for (int p = 0; p < partitions; p++) {
                ctx.check();
                task.apply(p);
            }
        } else if (partitions > 0) {
            // each await hands over the rows its task wrote on a worker, published by the task's completion
            try (final Job<Boolean> job = ctx.pool().submit(partitions, task, ctx)) {
                for (int p = 0; p < partitions; p++) {
                    job.await(p);
                }
            }
        }
        return out;
    }

    /** The rows {@code members[from .. to)}, in input order, sorted by {@code keys}; ties keep input order. */
    private static int[] partitionOrder(
            final int[] members,
            final int from,
            final int to,
            final Vector[] columns,
            final List<Plan.SortKey> keys
    ) {
        if (keys.isEmpty()) {
            return Arrays.copyOfRange(members, from, to);
        }
        final int[] index = AggSort.sorted(to - from,
                (a, b) -> compareKeys(columns, keys, members[from + a], members[from + b]));
        for (int i = 0; i < index.length; i++) {
            index[i] = members[from + index[i]];
        }
        return index;
    }

    private static int compareKeys(final Vector[] columns, final List<Plan.SortKey> keys, final int a, final int b) {
        for (int k = 0; k < columns.length; k++) {
            final int c = Rows.sortCompare(columns[k], a, columns[k], b);
            if (c != 0) {
                return keys.get(k).descending() ? -c : c;
            }
        }
        return 0;
    }

    private Vector[] sortColumns(final List<Plan.SortKey> orderBy, final int n) {
        final Vector[] columns = new Vector[orderBy.size()];
        for (int k = 0; k < columns.length; k++) {
            final int column = orderBy.get(k).column();
            columns[k] = gather(new Bound.Column(column, fields.get(column).type()), n);
        }
        return columns;
    }

    private void aggregate(
            final Bound.WindowCall call,
            final int[] partition,
            final int partitions,
            final int[] order,
            final Vector out,
            final int n
    ) {
        final Accumulator accumulator = Aggregates.create(aggregateOf(call));
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

    /** {@code lag} or {@code lead}, every partition walked in {@code order}. */
    private void shift(
            final Bound.WindowCall call,
            final int[] partition,
            final int[] order,
            final Vector out,
            final int n
    ) {
        final Shift shift = shift(call, n);
        int runStart = 0;
        for (int pos = 1; pos <= n; pos++) {
            if (pos == n || partition[order[pos - 1]] != partition[order[pos]]) {
                shift.write(order, runStart, pos, out);
                runStart = pos;
            }
        }
    }

    /** {@code lag}'s or {@code lead}'s argument read, its offset and default checked, on this thread. */
    private Shift shift(final Bound.WindowCall call, final int n) {
        final String function = call.function();
        final List<Bound> args = call.args();
        if (args.isEmpty()) {
            throw new CdbException("cannot run a window function without its argument [function=" + function + ']',
                    -1);
        }
        final Vector value = gather(args.getFirst(), n);
        final long offset = args.size() > 1 ? constant(args.get(1), CdbType.LONG).getLong(0) : 1;
        if (offset < 0) {
            throw new CdbException("cannot shift by a negative offset [function=" + function + ", offset=" + offset
                    + ']', -1);
        }
        final Vector fallback = args.size() > 2 ? constant(args.get(2), call.type()) : nullOf(call.type());
        return new Shift(value, offset, "lead".equals(function), fallback);
    }

    /** The aggregate a window call runs: {@code first_value} runs {@code first}, {@code last_value} {@code last}. */
    private static Bound.Agg aggregateOf(final Bound.WindowCall call) {
        final String function = switch (call.function()) {
            case "first_value" -> "first";
            case "last_value" -> "last";
            default -> call.function();
        };
        return new Bound.Agg(function, call.args(), false, call.type());
    }

    private int[] order(final int[] partition, final Vector[] columns, final List<Plan.SortKey> keys, final int n) {
        return AggSort.sorted(n, (a, b) -> {
            final int byPartition = Integer.compare(partition[a], partition[b]);
            return byPartition != 0 ? byPartition : compareKeys(columns, keys, a, b);
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

    /**
     * {@code lag}'s or {@code lead}'s argument over every row, its offset, its direction ({@code ahead} for
     * {@code lead}) and its default; only read once built.
     */
    private record Shift(Vector value, long offset, boolean ahead, Vector fallback) {

        /** Writes the rows {@code order[from .. to)}, one partition walked in its order. */
        void write(final int[] order, final int from, final int to, final Vector out) {
            for (int pos = from; pos < to; pos++) {
                final int row = order[pos];
                // compared as distances, so an offset near Long.MAX_VALUE does not overflow
                final boolean inside = ahead ? offset < to - pos : offset <= pos - from;
                if (inside) {
                    convert(value, order[(int) (ahead ? pos + offset : pos - offset)], out, row);
                } else {
                    out.copy(fallback, 0, row);
                }
            }
        }
    }
}
