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

    private final Cursor child;
    private final List<Field> fields;
    private final VectorFn[] keyFns;
    private final Vector[] keyVectors;
    private final AggTable table;
    private boolean closed;
    private int emitted;
    private boolean read;

    GroupByCursor(final Cursor child, final Plan.Aggregate spec, final long nowMicros) {
        this.child = child;
        try {
            fields = spec.fields();
            keyFns = new VectorFn[spec.keys().size()];
            keyVectors = new Vector[keyFns.length];
            final List<CdbType> keyTypes = new ArrayList<>();
            for (int k = 0; k < keyFns.length; k++) {
                final Bound key = spec.keys().get(k);
                keyFns[k] = Functions.compile(key, nowMicros);
                keyTypes.add(key.type());
            }
            table = new AggTable(keyTypes, spec.aggs(), nowMicros);
            if (keyFns.length == 0) {
                table.addEmptyGroup();
            }
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
            child.close();
        }
    }

    private void readAll() {
        read = true;
        Page page;
        while ((page = child.next()) != null) {
            for (int k = 0; k < keyFns.length; k++) {
                keyVectors[k] = keyFns[k].eval(page);
            }
            table.add(page, keyVectors);
        }
    }
}
