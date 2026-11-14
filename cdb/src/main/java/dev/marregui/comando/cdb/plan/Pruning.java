package dev.marregui.comando.cdb.plan;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/**
 * The {@code [from, to)} a filter allows on the designated timestamp, read from its top-level AND terms that compare
 * the timestamp column with a value known at plan time: a constant, {@code now()}, {@code dateadd} of those, a
 * timestamp plus or minus a constant. Other terms leave the range as it is; the filter itself still runs.
 */
final class Pruning {

    private Pruning() {
    }

    /** {@code range} narrowed by {@code predicate} on column {@code ts}. */
    static void narrow(final long[] range, final Bound predicate, final int ts, final long nowMicros) {
        if (!(predicate instanceof Bound.Call(final String function, final List<Bound> args, final CdbType _))) {
            return;
        }
        if ("and".equals(function)) {
            for (final Bound a : args) {
                narrow(range, a, ts, nowMicros);
            }
            return;
        }
        if ("between".equals(function)) {
            final Long lo = fold(args.get(1), nowMicros);
            final Long hi = fold(args.get(2), nowMicros);
            if (isColumn(args.getFirst(), ts) && lo != null && hi != null) {
                lower(range, lo);
                upper(range, hi + 1);
            }
            return;
        }
        if (!Signatures.isComparison(function) || "!=".equals(function)) {
            return;
        }
        final Long right = fold(args.getLast(), nowMicros);
        final Long left = fold(args.getFirst(), nowMicros);
        if (isColumn(args.getFirst(), ts) && right != null) {
            bound(range, function, right);
        } else if (isColumn(args.getLast(), ts) && left != null) {
            bound(range, flip(function), left);
        }
    }

    private static void bound(final long[] range, final String op, final long value) {
        switch (op) {
            case ">" -> lower(range, value + 1);
            case ">=" -> lower(range, value);
            case "<" -> upper(range, value);
            case "<=" -> upper(range, value + 1);
            case "=" -> {
                lower(range, value);
                upper(range, value + 1);
            }
            default -> {
            }
        }
    }

    private static String flip(final String op) {
        return switch (op) {
            case ">" -> "<";
            case ">=" -> "<=";
            case "<" -> ">";
            case "<=" -> ">=";
            default -> op;
        };
    }

    private static void lower(final long[] range, final long value) {
        range[0] = Math.max(range[0], value);
    }

    private static void upper(final long[] range, final long value) {
        range[1] = Math.min(range[1], value);
    }

    private static boolean isColumn(final Bound b, final int ts) {
        return b instanceof Bound.Column(final int index, final CdbType _) && index == ts;
    }

    /** The microseconds {@code b} stands for when it is known at plan time, else null. */
    private static Long fold(final Bound b, final long nowMicros) {
        return switch (b) {
            case Bound.Constant(final Object value, final CdbType type)
                    when value instanceof final Long l && (type == CdbType.TIMESTAMP || type == CdbType.LONG) -> l;
            case Bound.Call(final String function, final List<Bound> args, final CdbType _) ->
                    foldCall(function, args, nowMicros);
            case Bound.Constant _, Bound.Column _, Bound.Agg _, Bound.WindowCall _ -> null;
        };
    }

    private static Long foldCall(final String function, final List<Bound> args, final long nowMicros) {
        if ("now".equals(function) && args.isEmpty()) {
            return nowMicros;
        }
        if ("dateadd".equals(function) && args.size() == 3
                && args.getFirst() instanceof Bound.Constant(final String unit, final CdbType _)
                && args.get(1) instanceof Bound.Constant(final Long amount, final CdbType _)) {
            final Long base = fold(args.get(2), nowMicros);
            return base == null ? null : Times.dateadd(unit, amount, base, -1);
        }
        if (("+".equals(function) || "-".equals(function)) && args.size() == 2
                && args.getFirst().type() == CdbType.TIMESTAMP
                && args.getLast() instanceof Bound.Constant(final Long n, final CdbType _)) {
            final Long base = fold(args.getFirst(), nowMicros);
            return base == null ? null : "+".equals(function) ? base + n : base - n;
        }
        return null;
    }
}
