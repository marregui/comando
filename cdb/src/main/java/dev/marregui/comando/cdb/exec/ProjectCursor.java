package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.exec.expr.VectorFn;

/**
 * One output column per expression, evaluated over each input page. A vector that is one of the input page's own
 * columns (a plain column reference) is handed on uncopied; any other is copied, since a {@link VectorFn} may reuse
 * its vector on the next call.
 */
final class ProjectCursor implements Cursor {

    private final Cursor child;
    private final VectorFn[] exprs;
    private final List<Field> fields;

    ProjectCursor(final Cursor child, final List<VectorFn> exprs, final List<Field> fields) {
        this.child = child;
        this.exprs = exprs.toArray(new VectorFn[0]);
        this.fields = fields;
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        final Page in = child.next();
        if (in == null) {
            return null;
        }
        final Vector[] out = new Vector[exprs.length];
        for (int i = 0; i < exprs.length; i++) {
            final Vector v = exprs[i].eval(in);
            if (owned(v, in)) {
                out[i] = v;
            } else {
                final Field field = fields.get(i);
                if (!Rows.sameStorage(v.type, field.type())) {
                    throw new CdbException("cannot project a value of another type [column=" + field.name()
                            + ", type=" + field.type() + ", value=" + v.type + "]", -1);
                }
                final Vector copy = Vector.of(field.type(), Page.CAPACITY);
                Rows.copyRange(v, 0, copy, 0, in.size);
                out[i] = copy;
            }
        }
        return new Page(out, in.size);
    }

    @Override
    public void close() {
        child.close();
    }

    private static boolean owned(final Vector v, final Page page) {
        for (final Vector c : page.columns) {
            if (c == v) {
                return true;
            }
        }
        return false;
    }
}
