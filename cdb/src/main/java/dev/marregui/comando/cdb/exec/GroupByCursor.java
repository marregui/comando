package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

/**
 * GROUP BY (or a whole-input aggregate when there are no keys): the keys, then the aggregates, one row per group in
 * first-seen order; without keys one row, even for no input. Reads the whole input on the first {@link #next}.
 */
final class GroupByCursor implements Cursor {

    // the input of the serial operator, null over frames
    private final Cursor child;
    private final List<Field> fields;
    private final VectorFn[] keyFns;
    private final List<CdbType> keyTypes;
    private final Vector[] keyVectors;
    private final long nowMicros;
    // the input over frames, null for the serial operator
    private final FrameSource source;
    private final Plan.Aggregate spec;
    private final AggTable table;
    private boolean closed;
    private int emitted;
    private boolean read;

    GroupByCursor(final Cursor child, final Plan.Aggregate spec, final long nowMicros) {
        this(child, null, spec, nowMicros);
    }

    // exactly one of child and source is not null; a failure closes it
    private GroupByCursor(
            final Cursor child,
            final FrameSource source,
            final Plan.Aggregate spec,
            final long nowMicros
    ) {
        this.child = child;
        this.source = source;
        this.spec = spec;
        this.nowMicros = nowMicros;
        try {
            fields = spec.fields();
            keyTypes = new ArrayList<>();
            for (final Bound key : spec.keys()) {
                keyTypes.add(key.type());
            }
            keyFns = compileKeys(spec.keys(), nowMicros);
            keyVectors = new Vector[keyFns.length];
            table = new AggTable(keyTypes, spec.aggs(), nowMicros);
            if (keyFns.length == 0) {
                table.addEmptyGroup();
            }
        } catch (final RuntimeException e) {
            closeInput();
            throw e;
        }
    }

    /**
     * GROUP BY over {@code source}'s frames: a partial aggregate table per frame, merged in frame order. The answer is
     * the serial operator's, the same bits whatever the pool's size. Owns and closes the source.
     */
    static Cursor parallel(final FrameSource source, final Plan.Aggregate spec, final long nowMicros) {
        return new GroupByCursor(null, source, spec, nowMicros);
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        if (!read) {
            readAll();
        }
        if (emitted >= table.groups()) {
            return null;
        }
        final Page out = Page.allocate(fields);
        final int keys = keyFns.length;
        int row = 0;
        while (row < Page.CAPACITY && emitted < table.groups()) {
            for (int k = 0; k < keys; k++) {
                table.writeKey(emitted, k, out.columns[k], row);
            }
            for (int a = 0; a < table.aggregates(); a++) {
                table.writeAggregate(emitted, a, out.columns[keys + a], row);
            }
            row++;
            emitted++;
        }
        out.size = row;
        return out;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeInput();
        }
    }

    private static void add(final Page page, final VectorFn[] fns, final Vector[] vectors, final AggTable into) {
        for (int k = 0; k < fns.length; k++) {
            vectors[k] = fns[k].eval(page);
        }
        into.add(page, vectors);
    }

    private static VectorFn[] compileKeys(final List<Bound> keys, final long nowMicros) {
        final VectorFn[] fns = new VectorFn[keys.size()];
        for (int k = 0; k < fns.length; k++) {
            fns[k] = Functions.compile(keys.get(k), nowMicros);
        }
        return fns;
    }

    private void closeInput() {
        if (source != null) {
            source.close();
        } else {
            child.close();
        }
    }

    // a frame's partial, on a pool worker or the consuming thread: its key functions and table are its own, as a
    // compiled function reuses its output vector
    private AggTable partial(final int frame) {
        final VectorFn[] fns = compileKeys(spec.keys(), nowMicros);
        final Vector[] vectors = new Vector[fns.length];
        final AggTable partial = new AggTable(keyTypes, spec.aggs(), nowMicros);
        try (Cursor rows = source.open(frame)) {
            Page page;
            while ((page = rows.next()) != null) {
                source.context().check();
                add(page, fns, vectors, partial);
            }
        }
        return partial;
    }

    private void readAll() {
        read = true;
        if (source == null) {
            Page page;
            while ((page = child.next()) != null) {
                add(page, keyFns, keyVectors, table);
            }
            return;
        }
        // frame order, never completion order: the groups' order and every sum's rounding follow the frames
        try (final Job<AggTable> job = source.submit(this::partial)) {
            for (int frame = 0; frame < job.frames(); frame++) {
                final AggTable partial = job.await(frame);
                if (partial.groups() > 0) {
                    table.merge(partial);
                }
            }
        }
    }
}
