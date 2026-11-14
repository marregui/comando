package dev.marregui.comando.cdb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.sql.Ast;

/**
 * Binds an expression over a {@link Rel}: names to column indexes, types through {@link Signatures}, string literals
 * compared with a TIMESTAMP to TIMESTAMP constants, NULL to the type of what it meets. A {@code hook} binds first, for
 * the expressions an aggregate or a window below already computed; it returns null for the rest.
 */
final class Binder {

    private final Rel rel;
    private final Function<Ast.Expr, Bound> hook;

    Binder(final Rel rel, final Function<Ast.Expr, Bound> hook) {
        this.rel = rel;
        this.hook = hook;
    }

    /** {@code expr} bound; a NULL alone is VARCHAR. */
    Bound bindValue(final Ast.Expr expr) {
        return typed(bind(expr), CdbType.VARCHAR);
    }

    /** {@code expr} bound; a bare NULL is a constant of no type yet, which its caller types. */
    Bound bind(final Ast.Expr expr) {
        if (hook != null) {
            final Bound hooked = hook.apply(expr);
            if (hooked != null) {
                return hooked;
            }
        }
        return switch (expr) {
            case Ast.Literal(final Object value, final int _) -> literal(value);
            case Ast.Ident(final List<String> parts, final int pos) -> {
                final int i = rel.resolve(parts, pos);
                yield new Bound.Column(i, rel.cols().get(i).type());
            }
            case Ast.Star(final String _, final int pos) ->
                    throw new CdbException("cannot use * in an expression", pos);
            case Ast.Unary(final String op, final Ast.Expr operand, final int pos) -> unary(op, operand, pos);
            case Ast.Binary(final String op, final Ast.Expr left, final Ast.Expr right, final int pos) ->
                    call(op, List.of(bind(left), bind(right)), pos);
            case Ast.Call c -> function(c);
            case Ast.Cast(final Ast.Expr operand, final String type, final int pos) ->
                    cast(bind(operand), Ddl.type(type, pos), pos);
            case Ast.Case(final Ast.Expr operand, final List<Ast.When> whens, final Ast.Expr otherwise,
                          final int pos) -> caseOf(operand, whens, otherwise, pos);
            case Ast.In in -> in(in);
            case Ast.Between(final Ast.Expr operand, final Ast.Expr low, final Ast.Expr high, final boolean not,
                             final int pos) ->
                    negate(call("between", List.of(bind(operand), bind(low), bind(high)), pos), not);
            case Ast.IsNull(final Ast.Expr operand, final boolean not, final int pos) ->
                    call(not ? "is_not_null" : "is_null", List.of(typed(bind(operand), CdbType.VARCHAR)), pos);
            case Ast.Like(final Ast.Expr operand, final Ast.Expr pattern, final boolean not,
                          final boolean caseInsensitive, final int pos) ->
                    negate(call(caseInsensitive ? "ilike" : "like", List.of(bind(operand), bind(pattern)), pos), not);
            case Ast.ScalarQuery(final Ast.QueryBody _, final int pos) ->
                    throw new CdbException("cannot plan unsupported scalar subquery", pos);
        };
    }

    /** {@code b} with type {@code t} when it is an untyped NULL. */
    static Bound typed(final Bound b, final CdbType t) {
        if (b instanceof Bound.Constant(final Object value, final CdbType type) && value == null && type == null) {
            return new Bound.Constant(null, t);
        }
        return b;
    }

    /** {@code b} as a value of {@code to}: the same, a typed NULL, a parsed timestamp, or a cast. */
    static Bound cast(final Bound b, final CdbType to, final int pos) {
        if (b instanceof Bound.Constant(final Object value, final CdbType _)) {
            if (value == null) {
                return new Bound.Constant(null, to);
            }
            if (to == CdbType.TIMESTAMP && value instanceof final String s) {
                return new Bound.Constant(Times.parse(s, pos), CdbType.TIMESTAMP);
            }
        }
        if (b.type() == to) {
            return b;
        }
        return new Bound.Call("cast", List.of(b), to);
    }

    /**
     * {@code function} over {@code args}, after typing NULLs as the first typed argument and, where a TIMESTAMP meets a
     * string constant in a comparison or an addition, reading the string as a timestamp.
     */
    static Bound call(final String function, final List<Bound> args, final int pos) {
        CdbType first = null;
        boolean timestamp = false;
        for (final Bound a : args) {
            if (first == null) {
                first = a.type();
            }
            timestamp |= a.type() == CdbType.TIMESTAMP;
        }
        final boolean coerce = timestamp && (Signatures.isComparison(function) || "between".equals(function)
                || "in".equals(function) || "not_in".equals(function));
        final CdbType nullType = first == null ? CdbType.VARCHAR : first;
        final List<Bound> out = new ArrayList<>(args.size());
        for (final Bound a : args) {
            final Bound t = typed(a, nullType);
            out.add(coerce ? toTimestamp(t, pos) : t);
        }
        return new Bound.Call(function, List.copyOf(out), type(function, out, pos));
    }

    private static Bound literal(final Object value) {
        return switch (value) {
            case null -> new Bound.Constant(null, null);
            case Long _ -> new Bound.Constant(value, CdbType.LONG);
            case Double _ -> new Bound.Constant(value, CdbType.DOUBLE);
            case Boolean _ -> new Bound.Constant(value, CdbType.BOOLEAN);
            case String _ -> new Bound.Constant(value, CdbType.VARCHAR);
            default -> throw new CdbException("cannot bind literal [value=" + value + ']', -1);
        };
    }

    /** A minus on a number literal is folded: {@code -1} is the constant -1. */
    private Bound unary(final String op, final Ast.Expr operand, final int pos) {
        final Bound b = bind(operand);
        if ("-".equals(op)) {
            if (b instanceof Bound.Constant(final Long l, final CdbType _)) {
                return new Bound.Constant(-l, CdbType.LONG);
            }
            if (b instanceof Bound.Constant(final Double d, final CdbType _)) {
                return new Bound.Constant(-d, CdbType.DOUBLE);
            }
            return call("neg", List.of(b), pos);
        }
        return call(op, List.of(b), pos);
    }

    private Bound function(final Ast.Call c) {
        if (c.over() != null) {
            throw new CdbException("cannot use window function here [function=" + c.name() + ']', c.pos());
        }
        if (Signatures.isAggregate(c.name()) || c.star()) {
            throw new CdbException("cannot use aggregate here [function=" + c.name() + ']', c.pos());
        }
        if (Signatures.isWindowOnly(c.name())) {
            throw new CdbException("cannot call window function without OVER [function=" + c.name() + ']', c.pos());
        }
        final List<Bound> args = new ArrayList<>(c.args().size());
        for (final Ast.Expr a : c.args()) {
            args.add(bind(a));
        }
        final int ts = timestampArg(c.name(), args.size());
        if (ts >= 0) {
            args.set(ts, toTimestamp(args.get(ts), c.pos()));
        }
        if ("datediff".equals(c.name()) && args.size() == 3) {
            args.set(1, toTimestamp(args.get(1), c.pos()));
        }
        return call(c.name(), args, c.pos());
    }

    /** The argument of {@code function} that takes a timestamp, -1 when none does. */
    private static int timestampArg(final String function, final int arity) {
        return switch (function) {
            case "dateadd", "datediff" -> arity - 1;
            case "timestamp_floor", "date_trunc" -> 1;
            case "to_str", "hour" -> 0;
            default -> -1;
        };
    }

    private static Bound toTimestamp(final Bound b, final int pos) {
        if (b instanceof Bound.Constant(final String s, final CdbType _)) {
            return new Bound.Constant(Times.parse(s, pos), CdbType.TIMESTAMP);
        }
        return b;
    }

    /** A CASE with an operand compares it with each WHEN; NULL branches take the first typed branch's type. */
    private Bound caseOf(final Ast.Expr operand, final List<Ast.When> whens, final Ast.Expr otherwise, final int pos) {
        final List<Bound> args = new ArrayList<>();
        final Bound subject = operand == null ? null : bind(operand);
        for (final Ast.When w : whens) {
            args.add(subject == null ? bind(w.condition()) : call("=", List.of(subject, bind(w.condition())), pos));
            args.add(bind(w.value()));
        }
        if (otherwise != null) {
            args.add(bind(otherwise));
        }
        CdbType t = null;
        for (int i = 1; i < args.size() && t == null; i += 2) {
            t = args.get(i).type();
        }
        if (t == null && otherwise != null) {
            t = args.getLast().type();
        }
        final CdbType valueType = t == null ? CdbType.VARCHAR : t;
        args.replaceAll(b -> typed(b, valueType));
        return new Bound.Call("case", List.copyOf(args), type("case", args, pos));
    }

    /** A timestamp IN interval texts is one range per text, OR-ed; any other IN is a membership test. */
    private Bound in(final Ast.In in) {
        if (in.query() != null) {
            throw new CdbException("cannot plan unsupported IN subquery", in.pos());
        }
        final Bound subject = bind(in.operand());
        final List<Bound> values = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (final Ast.Expr v : in.values()) {
            final Bound b = bind(v);
            values.add(b);
            if (b instanceof Bound.Constant(final String s, final CdbType _)) {
                texts.add(s);
            }
        }
        if (subject.type() == CdbType.TIMESTAMP && !texts.isEmpty() && texts.size() == values.size()) {
            Bound any = null;
            for (final String text : texts) {
                final long[] range = Times.interval(text, in.pos());
                final Bound one = new Bound.Call("and", List.of(
                        new Bound.Call(">=", List.of(subject, new Bound.Constant(range[0], CdbType.TIMESTAMP)),
                                CdbType.BOOLEAN),
                        new Bound.Call("<", List.of(subject, new Bound.Constant(range[1], CdbType.TIMESTAMP)),
                                CdbType.BOOLEAN)), CdbType.BOOLEAN);
                any = any == null ? one : new Bound.Call("or", List.of(any, one), CdbType.BOOLEAN);
            }
            return negate(any, in.not());
        }
        final List<Bound> args = new ArrayList<>();
        args.add(subject);
        args.addAll(values);
        return call(in.not() ? "not_in" : "in", args, in.pos());
    }

    private static Bound negate(final Bound b, final boolean not) {
        return not ? new Bound.Call("not", List.of(b), CdbType.BOOLEAN) : b;
    }

    private static CdbType type(final String function, final List<Bound> args, final int pos) {
        final List<CdbType> types = new ArrayList<>(args.size());
        for (final Bound a : args) {
            types.add(a.type());
        }
        try {
            return Signatures.returnType(function, types);
        } catch (final CdbException e) {
            throw new CdbException(e.getMessage(), pos);
        }
    }
}
