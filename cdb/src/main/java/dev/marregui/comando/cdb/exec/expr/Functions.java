package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * The scalar functions and operators compiled for the operators; each produces the type its {@code Bound} carries,
 * which {@link dev.marregui.comando.cdb.plan.Signatures} decided. NULL in gives NULL out unless the function says
 * otherwise ({@code coalesce}, {@code is_null}, {@code length}, {@code like}, {@code between}, the comparisons, and
 * {@code concat}, which reads NULL as empty, as QuestDB does). BOOLEAN has no NULL, so {@code and} and {@code or} are
 * two-valued.
 * Arguments of any type are accepted and converted as {@code CAST} would; a function evaluates a whole page into a
 * vector it reuses on every call.
 */
public final class Functions {

    private Functions() {
    }

    /** {@code expr} compiled; {@code nowMicros} is {@code now()} for the statement. */
    public static VectorFn compile(final Bound expr, final long nowMicros) {
        return switch (expr) {
            case Bound.Column c -> page -> page.columns[c.index()];
            case Bound.Constant k -> constant(k);
            case Bound.Call c -> call(c, nowMicros);
            case Bound.Agg a -> throw new CdbException("cannot compile an aggregate as a scalar [function=" + a.function() + "]", -1);
            case Bound.WindowCall w -> throw new CdbException("cannot compile a window call as a scalar [function=" + w.function() + "]", -1);
        };
    }

    /** The text of a constant argument (a unit, a pattern, a format); an error when it is not a non-NULL constant. */
    static String constantText(final Bound arg, final String function) {
        if (arg instanceof Bound.Constant(Object value, CdbType _) && value != null) {
            return value.toString();
        }
        throw new CdbException("argument must be a constant [function=" + function + "]", -1);
    }

    private static VectorFn call(final Bound.Call call, final long nowMicros) {
        final String f = call.function();
        final List<Bound> a = call.args();
        final CdbType t = call.type();
        return switch (f) {
            case "+" -> Arithmetic.binary(Arithmetic.ADD, arity(call, 2, 2), t, nowMicros);
            case "-" -> Arithmetic.binary(Arithmetic.SUB, arity(call, 2, 2), t, nowMicros);
            case "*" -> Arithmetic.binary(Arithmetic.MUL, arity(call, 2, 2), t, nowMicros);
            case "/" -> Arithmetic.binary(Arithmetic.DIV, arity(call, 2, 2), t, nowMicros);
            case "%" -> Arithmetic.binary(Arithmetic.MOD, arity(call, 2, 2), t, nowMicros);
            case "neg" -> Arithmetic.negate(arity(call, 1, 1).getFirst(), t, nowMicros);
            case "abs" -> Arithmetic.abs(arity(call, 1, 1).getFirst(), t, nowMicros);
            case "round" -> Arithmetic.round(arity(call, 1, 2), t, nowMicros);
            case "=" -> Comparison.compare(Comparison.EQ, arity(call, 2, 2), t, nowMicros);
            case "!=" -> Comparison.compare(Comparison.NE, arity(call, 2, 2), t, nowMicros);
            case "<" -> Comparison.compare(Comparison.LT, arity(call, 2, 2), t, nowMicros);
            case "<=" -> Comparison.compare(Comparison.LE, arity(call, 2, 2), t, nowMicros);
            case ">" -> Comparison.compare(Comparison.GT, arity(call, 2, 2), t, nowMicros);
            case ">=" -> Comparison.compare(Comparison.GE, arity(call, 2, 2), t, nowMicros);
            case "in" -> Comparison.in(arity(call, 2, Integer.MAX_VALUE), false, t, nowMicros);
            case "not_in" -> Comparison.in(arity(call, 2, Integer.MAX_VALUE), true, t, nowMicros);
            case "between" -> Comparison.between(arity(call, 3, 3), t, nowMicros);
            case "and" -> Logic.connective(true, arity(call, 2, 2), t, nowMicros);
            case "or" -> Logic.connective(false, arity(call, 2, 2), t, nowMicros);
            case "not" -> Logic.not(arity(call, 1, 1).getFirst(), t, nowMicros);
            case "is_null" -> Logic.isNull(arity(call, 1, 1).getFirst(), false, t, nowMicros);
            case "is_not_null" -> Logic.isNull(arity(call, 1, 1).getFirst(), true, t, nowMicros);
            case "case" -> Logic.choose(arity(call, 2, Integer.MAX_VALUE), t, nowMicros);
            case "coalesce" -> Logic.coalesce(arity(call, 1, Integer.MAX_VALUE), t, nowMicros);
            case "like" -> Like.compile(arity(call, 2, 2), false, t, nowMicros);
            case "ilike" -> Like.compile(arity(call, 2, 2), true, t, nowMicros);
            case "cast", "timestamp" -> Cast.exact(compile(arity(call, 1, 1).getFirst(), nowMicros), a.getFirst().type(), t);
            case "||", "concat" -> TextFns.concat(arity(call, 1, Integer.MAX_VALUE), t, nowMicros);
            case "substring" -> TextFns.substring(arity(call, 3, 3), t, nowMicros);
            case "replace" -> TextFns.replace(arity(call, 3, 3), t, nowMicros);
            case "length" -> TextFns.length(arity(call, 1, 1).getFirst(), t, nowMicros);
            case "upper" -> TextFns.changeCase(arity(call, 1, 1).getFirst(), true, t, nowMicros);
            case "lower" -> TextFns.changeCase(arity(call, 1, 1).getFirst(), false, t, nowMicros);
            case "to_str" -> TextFns.toStr(arity(call, 2, 2), t, nowMicros);
            case "now" -> TimeFns.now(t, nowMicros);
            case "dateadd" -> TimeFns.dateAdd(arity(call, 3, 3), t, nowMicros);
            case "datediff" -> TimeFns.dateDiff(arity(call, 3, 3), t, nowMicros);
            case "timestamp_floor" -> TimeFns.timestampFloor(arity(call, 2, 2), t, nowMicros);
            case "date_trunc" -> TimeFns.dateTrunc(arity(call, 2, 2), t, nowMicros);
            case "hour" -> TimeFns.hour(arity(call, 1, 1).getFirst(), t, nowMicros);
            default -> throw new CdbException("unknown function [function=" + f + "]", -1);
        };
    }

    private static List<Bound> arity(final Bound.Call call, final int min, final int max) {
        final int n = call.args().size();
        if (n < min || n > max) {
            throw new CdbException("wrong number of arguments [function=" + call.function() + ", min=" + min
                    + ", max=" + max + ", actual=" + n + "]", -1);
        }
        return call.args();
    }

    private static VectorFn constant(final Bound.Constant k) {
        final Vector v = Vector.of(k.type(), Page.CAPACITY);
        final Object value = k.value();
        for (int i = 0; i < Page.CAPACITY; i++) {
            if (value == null) {
                v.setNull(i);
            } else {
                switch (k.type()) {
                    case LONG, TIMESTAMP -> v.longs[i] = (Long) value;
                    case DOUBLE -> v.doubles[i] = (Double) value;
                    case BOOLEAN -> v.booleans[i] = (Boolean) value;
                    case SYMBOL, VARCHAR -> v.texts[i] = (String) value;
                }
            }
        }
        return page -> v;
    }
}
