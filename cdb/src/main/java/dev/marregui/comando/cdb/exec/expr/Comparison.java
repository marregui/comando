package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * Comparisons, {@code in}, {@code not_in} and {@code between}, with QuestDB's NULL rules: two NULLs compare equal, so
 * {@code =}, {@code <=} and {@code >=} are true and the others false; one NULL makes only {@code !=} true. The operands
 * meet in {@link Cast#common}; doubles compare by value, so -0.0 equals 0.0. {@code between} is false when any operand
 * is NULL and swaps reversed bounds.
 */
final class Comparison {

    static final int EQ = 0;
    static final int NE = 1;
    static final int LT = 2;
    static final int LE = 3;
    static final int GT = 4;
    static final int GE = 5;

    private static final int BOTH_NULL = 2;
    private static final int ONE_NULL = 3;
    // per operator: the outcome when the left side is less, equal, greater
    private static final boolean[][] TRUTH = {
            {false, true, false},
            {true, false, true},
            {true, false, false},
            {true, true, false},
            {false, false, true},
            {false, true, true},
    };

    private Comparison() {
    }

    static VectorFn compare(final int op, final List<Bound> args, final CdbType type, final long nowMicros) {
        final CdbType domain = Cast.common(args);
        final VectorFn a = Cast.arg(args.get(0), domain, nowMicros);
        final VectorFn b = Cast.arg(args.get(1), domain, nowMicros);
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector x = a.eval(page);
            final Vector y = b.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                out.booleans[i] = outcome(op, order(x, y, i));
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    /** {@code args[0] in (args[1], ...)} by the equality of {@link #compare}; {@code negated} for {@code not_in}. */
    static VectorFn in(final List<Bound> args, final boolean negated, final CdbType type, final long nowMicros) {
        final CdbType domain = Cast.common(args);
        final VectorFn operand = Cast.arg(args.get(0), domain, nowMicros);
        final VectorFn[] values = Cast.args(args, 1, domain, nowMicros);
        final Vector[] evaluated = new Vector[values.length];
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector x = operand.eval(page);
            for (int j = 0; j < values.length; j++) {
                evaluated[j] = values[j].eval(page);
            }
            for (int i = 0, n = page.size; i < n; i++) {
                boolean found = false;
                for (int j = 0; j < evaluated.length && !found; j++) {
                    found = outcome(EQ, order(x, evaluated[j], i));
                }
                out.booleans[i] = found != negated;
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    static VectorFn between(final List<Bound> args, final CdbType type, final long nowMicros) {
        final CdbType domain = Cast.common(args);
        final VectorFn operand = Cast.arg(args.get(0), domain, nowMicros);
        final VectorFn low = Cast.arg(args.get(1), domain, nowMicros);
        final VectorFn high = Cast.arg(args.get(2), domain, nowMicros);
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector x = operand.eval(page);
            final Vector lo = low.eval(page);
            final Vector hi = high.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                final int toLow = order(x, lo, i);
                final int toHigh = order(x, hi, i);
                final int bounds = order(lo, hi, i);
                if (toLow > 1 || toHigh > 1 || bounds > 1) {
                    out.booleans[i] = false;
                } else if (bounds > 0) {
                    out.booleans[i] = toHigh >= 0 && toLow <= 0;
                } else {
                    out.booleans[i] = toLow >= 0 && toHigh <= 0;
                }
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    private static boolean outcome(final int op, final int order) {
        return switch (order) {
            case BOTH_NULL -> TRUTH[op][1];
            case ONE_NULL -> op == NE;
            default -> TRUTH[op][order + 1];
        };
    }

    /** -1, 0 or 1 for value {@code i} of {@code a} against that of {@code b}, of one storage; else a NULL code. */
    private static int order(final Vector a, final Vector b, final int i) {
        final boolean aNull = a.isNull(i);
        final boolean bNull = b.isNull(i);
        if (aNull || bNull) {
            return aNull && bNull ? BOTH_NULL : ONE_NULL;
        }
        return switch (a.type) {
            case LONG, TIMESTAMP -> Long.compare(a.longs[i], b.longs[i]);
            case DOUBLE -> a.doubles[i] < b.doubles[i] ? -1 : a.doubles[i] == b.doubles[i] ? 0 : 1;
            case BOOLEAN -> Boolean.compare(a.booleans[i], b.booleans[i]);
            case SYMBOL, VARCHAR -> Integer.signum(a.texts[i].compareTo(b.texts[i]));
        };
    }
}
