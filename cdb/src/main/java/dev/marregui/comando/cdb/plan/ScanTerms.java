package dev.marregui.comando.cdb.plan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.store.Compare;

/**
 * The pre-filter terms of a scan, read from its filter's top-level AND terms that compare a scan column with a value
 * known at plan time, in the domain the filter's comparison uses: a LONG or TIMESTAMP column with a LONG or TIMESTAMP
 * value ({@link Pruning#fold}), a DOUBLE column with a DOUBLE or LONG constant, a SYMBOL or VARCHAR column with a text
 * constant whose UTF-8 bytes decode back to it ({@code =} and {@code !=} only); {@code BETWEEN} as two terms, its
 * bounds swapped when reversed. Left out: a NULL or NaN value, a DOUBLE zero (a scan may compare doubles by {@link Double#compare}, which orders -0.0 below 0.0
 * where the filter finds them equal), OR, NOT, functions of a column, and the designated timestamp's bounds, which
 * the scan's time range already holds.
 */
final class ScanTerms {

    private ScanTerms() {
    }

    /** The terms {@code predicate} gives over {@code fields}; {@code ts} is the designated timestamp's index or -1. */
    static List<Plan.ScanTerm> of(
            final Bound predicate,
            final List<Field> fields,
            final int ts,
            final long nowMicros
    ) {
        final List<Plan.ScanTerm> terms = new ArrayList<>();
        collect(predicate, fields, ts, nowMicros, terms);
        return List.copyOf(terms);
    }

    private static void collect(
            final Bound predicate,
            final List<Field> fields,
            final int ts,
            final long nowMicros,
            final List<Plan.ScanTerm> out
    ) {
        if (!(predicate instanceof Bound.Call(final String function, final List<Bound> args, final CdbType _))) {
            return;
        }
        if ("and".equals(function)) {
            for (final Bound a : args) {
                collect(a, fields, ts, nowMicros, out);
            }
            return;
        }
        if ("between".equals(function)) {
            if (args.getFirst() instanceof Bound.Column(final int column, final CdbType _) && column != ts) {
                between(column, fields.get(column).type(), args.get(1), args.get(2), nowMicros, out);
            }
            return;
        }
        if (!Signatures.isComparison(function)) {
            return;
        }
        if (args.getFirst() instanceof Bound.Column(final int column, final CdbType _)) {
            compare(column, function, args.getLast(), fields, ts, nowMicros, out);
        } else if (args.getLast() instanceof Bound.Column(final int column, final CdbType _)) {
            compare(column, Pruning.flip(function), args.getFirst(), fields, ts, nowMicros, out);
        }
    }

    private static void compare(
            final int column,
            final String function,
            final Bound value,
            final List<Field> fields,
            final int ts,
            final long nowMicros,
            final List<Plan.ScanTerm> out
    ) {
        final Compare op = op(function);
        if (column == ts && op != Compare.NE) {
            return;
        }
        switch (fields.get(column).type()) {
            case LONG, TIMESTAMP -> {
                final Long v = longValue(value, nowMicros);
                if (v != null) {
                    out.add(new Plan.ScanTerm(column, op, v, Double.NaN, null));
                }
            }
            case DOUBLE -> {
                final double v = doubleValue(value);
                if (!Double.isNaN(v)) {
                    out.add(new Plan.ScanTerm(column, op, Long.MIN_VALUE, v, null));
                }
            }
            case SYMBOL, VARCHAR, JSON -> {
                final String text = exactText(value);
                if ((op == Compare.EQ || op == Compare.NE) && text != null) {
                    out.add(new Plan.ScanTerm(column, op, Long.MIN_VALUE, Double.NaN, text));
                }
            }
            case BOOLEAN -> {
            }
        }
    }

    /**
     * Whether every top-level AND term of {@code predicate} is a SYMBOL or VARCHAR column compared by {@code =} or
     * {@code !=} with a text constant {@link #exactText} accepts: the terms then keep exactly the rows the filter keeps
     * (the text kernels are exact, {@code PartitionReader#selectText}), and the filter can go.
     */
    static boolean exact(final Bound predicate, final List<Field> fields) {
        if (!(predicate instanceof Bound.Call(final String function, final List<Bound> args, final CdbType _))) {
            return false;
        }
        if ("and".equals(function)) {
            for (final Bound a : args) {
                if (!exact(a, fields)) {
                    return false;
                }
            }
            return true;
        }
        if (!"=".equals(function) && !"!=".equals(function)) {
            return false;
        }
        final Bound column = args.getFirst() instanceof Bound.Column ? args.getFirst() : args.getLast();
        final Bound value = column == args.getFirst() ? args.getLast() : args.getFirst();
        return column instanceof Bound.Column(final int index, final CdbType _)
                && fields.get(index).type().isText() && exactText(value) != null;
    }

    /**
     * {@code value}'s text when it is a non-NULL text constant whose UTF-8 bytes decode back to it, else null: the scan
     * compares bytes, the filter Strings, and a lone surrogate encodes as {@code ?}, which a stored {@code ?} would
     * then equal.
     */
    private static String exactText(final Bound value) {
        return value instanceof Bound.Constant(final String text, final CdbType type) && type.isText()
                && text.equals(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)) ? text : null;
    }

    private static void between(
            final int column,
            final CdbType type,
            final Bound low,
            final Bound high,
            final long nowMicros,
            final List<Plan.ScanTerm> out
    ) {
        switch (type) {
            case LONG, TIMESTAMP -> {
                final Long a = longValue(low, nowMicros);
                final Long b = longValue(high, nowMicros);
                if (a != null && b != null) {
                    out.add(new Plan.ScanTerm(column, Compare.GE, Math.min(a, b), Double.NaN, null));
                    out.add(new Plan.ScanTerm(column, Compare.LE, Math.max(a, b), Double.NaN, null));
                }
            }
            case DOUBLE -> {
                final double a = doubleValue(low);
                final double b = doubleValue(high);
                if (!Double.isNaN(a) && !Double.isNaN(b)) {
                    out.add(new Plan.ScanTerm(column, Compare.GE, Long.MIN_VALUE, Math.min(a, b), null));
                    out.add(new Plan.ScanTerm(column, Compare.LE, Long.MIN_VALUE, Math.max(a, b), null));
                }
            }
            case BOOLEAN, SYMBOL, VARCHAR, JSON -> {
            }
        }
    }

    /** The value of a LONG or TIMESTAMP comparison, null when it is not known at plan time or is NULL. */
    private static Long longValue(final Bound value, final long nowMicros) {
        final Long v = Pruning.fold(value, nowMicros);
        return v == null || v == Long.MIN_VALUE ? null : v;
    }

    /**
     * The value of a DOUBLE comparison, as the filter reads a LONG constant there; NaN when it is not a constant, is
     * NULL, or is a zero.
     */
    private static double doubleValue(final Bound value) {
        final double v = switch (value) {
            case Bound.Constant(final Double d, final CdbType type) when type == CdbType.DOUBLE -> d;
            case Bound.Constant(final Long l, final CdbType type) when type == CdbType.LONG && l != Long.MIN_VALUE ->
                    l;
            case Bound.Constant _, Bound.Column _, Bound.Call _, Bound.Agg _, Bound.WindowCall _ -> Double.NaN;
        };
        return v == 0.0 ? Double.NaN : v;
    }

    private static Compare op(final String function) {
        return switch (function) {
            case "=" -> Compare.EQ;
            case "!=" -> Compare.NE;
            case "<" -> Compare.LT;
            case "<=" -> Compare.LE;
            case ">" -> Compare.GT;
            case ">=" -> Compare.GE;
            default -> throw new IllegalArgumentException("refuse non-comparison [function=" + function + ']');
        };
    }
}
