package dev.marregui.comando.cdb.plan;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;

/**
 * An expression with its names resolved to the input's column indexes and its type checked. Operators and functions
 * are both {@link Call}s by lower-case name: {@code + - * / % || = != < <= > >= and or not neg in not_in between
 * is_null is_not_null like ilike case cast} and every scalar function of the corpus. {@code case} takes its arguments
 * as {@code cond1, value1, cond2, value2, …[, else]}; {@code cast} has the target as its type; {@code in} has the
 * operand first, then the values. String literals compared with or added to a TIMESTAMP are TIMESTAMP constants by the
 * time they get here.
 */
public sealed interface Bound {

    CdbType type();

    /** Column {@code index} of the operator's input. */
    record Column(int index, CdbType type) implements Bound {
    }

    /** A Long (LONG, TIMESTAMP), Double, Boolean or String; null for NULL. */
    record Constant(Object value, CdbType type) implements Bound {
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
