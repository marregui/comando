package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * {@code and}, {@code or}, {@code not}, the NULL tests, {@code case} and {@code coalesce}. BOOLEAN has no NULL (a NULL
 * BOOLEAN is false), so the connectives are two-valued, as in QuestDB. {@code case} without {@code else} gives NULL;
 * {@code coalesce} gives the first operand that is not NULL.
 */
final class Logic {

    private Logic() {
    }

    static VectorFn connective(final boolean and, final List<Bound> args, final CdbType type, final long nowMicros) {
        final VectorFn a = Cast.arg(args.get(0), CdbType.BOOLEAN, nowMicros);
        final VectorFn b = Cast.arg(args.get(1), CdbType.BOOLEAN, nowMicros);
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final boolean[] x = a.eval(page).booleans;
            final boolean[] y = b.eval(page).booleans;
            for (int i = 0, n = page.size; i < n; i++) {
                out.booleans[i] = and ? x[i] && y[i] : x[i] || y[i];
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    static VectorFn not(final Bound arg, final CdbType type, final long nowMicros) {
        final VectorFn a = Cast.arg(arg, CdbType.BOOLEAN, nowMicros);
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final boolean[] x = a.eval(page).booleans;
            for (int i = 0, n = page.size; i < n; i++) {
                out.booleans[i] = !x[i];
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    static VectorFn isNull(final Bound arg, final boolean negated, final CdbType type, final long nowMicros) {
        final VectorFn a = Functions.compile(arg, nowMicros);
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector x = a.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                out.booleans[i] = x.isNull(i) != negated;
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    /** {@code case}: arguments {@code cond1, value1, ..., [else]}; every branch is evaluated, one picked per row. */
    static VectorFn choose(final List<Bound> args, final CdbType type, final long nowMicros) {
        final int branches = args.size() / 2;
        final VectorFn[] conditions = new VectorFn[branches];
        final VectorFn[] values = new VectorFn[branches];
        for (int b = 0; b < branches; b++) {
            conditions[b] = Cast.arg(args.get(2 * b), CdbType.BOOLEAN, nowMicros);
            values[b] = exactArg(args.get(2 * b + 1), type, nowMicros);
        }
        final VectorFn otherwise = args.size() % 2 == 1 ? exactArg(args.getLast(), type, nowMicros) : null;
        final Vector[] tests = new Vector[branches];
        final Vector[] results = new Vector[branches];
        final Vector out = Vector.of(type, Page.CAPACITY);
        return page -> {
            for (int b = 0; b < branches; b++) {
                tests[b] = conditions[b].eval(page);
                results[b] = values[b].eval(page);
            }
            final Vector fallback = otherwise == null ? null : otherwise.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                int b = 0;
                while (b < branches && !tests[b].booleans[i]) {
                    b++;
                }
                if (b < branches) {
                    out.copy(results[b], i, i);
                } else if (fallback != null) {
                    out.copy(fallback, i, i);
                } else {
                    out.setNull(i);
                }
            }
            return out;
        };
    }

    static VectorFn coalesce(final List<Bound> args, final CdbType type, final long nowMicros) {
        final VectorFn[] values = new VectorFn[args.size()];
        for (int j = 0; j < values.length; j++) {
            values[j] = exactArg(args.get(j), type, nowMicros);
        }
        final Vector[] evaluated = new Vector[values.length];
        final Vector out = Vector.of(type, Page.CAPACITY);
        return page -> {
            for (int j = 0; j < values.length; j++) {
                evaluated[j] = values[j].eval(page);
            }
            for (int i = 0, n = page.size; i < n; i++) {
                int j = 0;
                while (j < evaluated.length - 1 && evaluated[j].isNull(i)) {
                    j++;
                }
                out.copy(evaluated[j], i, i);
            }
            return out;
        };
    }

    private static VectorFn exactArg(final Bound arg, final CdbType type, final long nowMicros) {
        return Cast.exact(Functions.compile(arg, nowMicros), arg.type(), type);
    }
}
