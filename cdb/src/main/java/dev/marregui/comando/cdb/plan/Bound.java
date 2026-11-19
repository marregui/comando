package dev.marregui.comando.cdb.plan;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/**
 * An expression with its names resolved to the input's column indexes and its type checked. Operators and functions
 * are both {@link Call}s by lower-case name: {@code + - * / % || = != < <= > >= and or not neg in not_in between
 * is_null is_not_null like ilike case cast} and every scalar function of the corpus. {@code case} takes its arguments
 * as {@code cond1, value1, cond2, value2, …[, else]}; {@code cast} has the target as its type; {@code in} has the
 * operand first, then the values; {@code in_query} has the operand, then a constant holding a {@link Subquery}.
 * String literals compared with or added to a TIMESTAMP are TIMESTAMP constants by the time they get here.
 */
public sealed interface Bound {

    CdbType type();

    /** Column {@code index} of the operator's input. */
    record Column(int index, CdbType type) implements Bound {
    }

    /**
     * A Long (LONG, TIMESTAMP), Double, Boolean or String; null for NULL. The second argument of {@code in_query} is a
     * constant of its subquery's column type whose value is the {@link Subquery} as planned, and the set of the
     * subquery's values once the executor has run it.
     */
    record Constant(Object value, CdbType type) implements Bound {
    }

    /**
     * The one-column query of {@code x IN (SELECT …)}, uncorrelated, which the executor runs once per statement before
     * the statement's first row; {@code pos} is the IN's position in the text.
     */
    record Subquery(Plan plan, int pos) {

        /** The subqueries {@code b} holds, in the order {@code b} names them. */
        public static List<Subquery> of(final Bound b) {
            final List<Subquery> out = new ArrayList<>();
            collect(b, out);
            return out;
        }

        private static void collect(final Bound b, final List<Subquery> out) {
            switch (b) {
                case Column _ -> {
                }
                case Constant(final Object value, final CdbType _) -> {
                    if (value instanceof final Subquery s) {
                        out.add(s);
                    }
                }
                case Call(final String _, final List<Bound> args, final CdbType _) -> collectAll(args, out);
                case Agg(final String _, final List<Bound> args, final boolean _, final CdbType _) ->
                        collectAll(args, out);
                case WindowCall(final String _, final List<Bound> args, final List<Bound> partitionBy,
                                final List<Plan.SortKey> _, final CdbType _) -> {
                    collectAll(args, out);
                    collectAll(partitionBy, out);
                }
            }
        }

        private static void collectAll(final List<Bound> bs, final List<Subquery> out) {
            for (final Bound b : bs) {
                collect(b, out);
            }
        }
    }

    record Call(String function, List<Bound> args, CdbType type) implements Bound {
    }

    /** An aggregate ({@code count}, {@code sum}, {@code count_distinct}, …); {@code count(*)} has no args. */
    record Agg(String function, List<Bound> args, boolean distinct, CdbType type) implements Bound {
    }

    /**
     * A window function over its input ({@code lag}, {@code row_number}, an aggregate with {@code OVER}): the rows of
     * each {@code partitionBy} group in {@code orderBy} order.
     */
    record WindowCall(
            String function,
            List<Bound> args,
            List<Bound> partitionBy,
            List<Plan.SortKey> orderBy,
            CdbType type
    ) implements Bound {
    }
}
