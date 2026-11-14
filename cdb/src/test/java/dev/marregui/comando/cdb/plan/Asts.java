package dev.marregui.comando.cdb.plan;

import java.util.List;

import dev.marregui.comando.cdb.sql.Ast;

/// Parse trees written by hand, as the parser would return them, for the planner's tests.
final class Asts {

    private Asts() {
    }

    static Ast.Ident id(final String... parts) {
        return new Ast.Ident(List.of(parts), 0);
    }

    static Ast.Literal lit(final Object value) {
        return new Ast.Literal(value, 0);
    }

    static Ast.Call call(final String name, final Ast.Expr... args) {
        return new Ast.Call(name, List.of(args), false, false, null, 0);
    }

    static Ast.Call countStar() {
        return new Ast.Call("count", List.of(), false, true, null, 0);
    }

    static Ast.Call over(final Ast.Call call, final List<Ast.Expr> partitionBy, final Ast.OrderItem... orderBy) {
        return new Ast.Call(call.name(), call.args(), false, false, new Ast.Over(partitionBy, List.of(orderBy)), 0);
    }

    static Ast.Binary bin(final String op, final Ast.Expr left, final Ast.Expr right) {
        return new Ast.Binary(op, left, right, 0);
    }

    static Ast.Cast cast(final Ast.Expr operand, final String type) {
        return new Ast.Cast(operand, type, 0);
    }

    static Ast.SelectItem item(final Ast.Expr expr) {
        return new Ast.SelectItem(expr, null);
    }

    static Ast.SelectItem item(final Ast.Expr expr, final String alias) {
        return new Ast.SelectItem(expr, alias);
    }

    static Ast.SelectItem star() {
        return new Ast.SelectItem(new Ast.Star(null, 0), null);
    }

    static Ast.TableName table(final String name) {
        return new Ast.TableName(name, null, 0);
    }

    static Ast.TableName table(final String name, final String alias) {
        return new Ast.TableName(name, alias, 0);
    }

    static Ast.OrderItem asc(final Ast.Expr expr) {
        return new Ast.OrderItem(expr, false);
    }

    static Ast.OrderItem desc(final Ast.Expr expr) {
        return new Ast.OrderItem(expr, true);
    }

    static Ast.Select select(final Ast.From from, final Ast.SelectItem... items) {
        return new Ast.Select(false, List.of(items), from, null, null, null, List.of(), List.of(), null, 0);
    }

    static Ast.Select distinct(final Ast.Select s) {
        return new Ast.Select(true, s.items(), s.from(), s.where(), s.latestOn(), s.sampleBy(), s.groupBy(),
                s.orderBy(), s.limit(), 0);
    }

    static Ast.Select where(final Ast.Select s, final Ast.Expr where) {
        return new Ast.Select(s.distinct(), s.items(), s.from(), where, s.latestOn(), s.sampleBy(), s.groupBy(),
                s.orderBy(), s.limit(), 0);
    }

    static Ast.Select latestOn(final Ast.Select s, final String ts, final String... partitionBy) {
        return new Ast.Select(s.distinct(), s.items(), s.from(), s.where(), new Ast.LatestOn(ts, List.of(partitionBy)),
                s.sampleBy(), s.groupBy(), s.orderBy(), s.limit(), 0);
    }

    static Ast.Select sampleBy(final Ast.Select s, final long count, final String unit) {
        return new Ast.Select(s.distinct(), s.items(), s.from(), s.where(), s.latestOn(),
                new Ast.SampleBy(count, unit, List.of(), false), s.groupBy(), s.orderBy(), s.limit(), 0);
    }

    static Ast.Select groupBy(final Ast.Select s, final Ast.Expr... keys) {
        return new Ast.Select(s.distinct(), s.items(), s.from(), s.where(), s.latestOn(), s.sampleBy(),
                List.of(keys), s.orderBy(), s.limit(), 0);
    }

    static Ast.Select orderBy(final Ast.Select s, final Ast.OrderItem... items) {
        return new Ast.Select(s.distinct(), s.items(), s.from(), s.where(), s.latestOn(), s.sampleBy(), s.groupBy(),
                List.of(items), s.limit(), 0);
    }

    static Ast.Select limit(final Ast.Select s, final Ast.Expr lo, final Ast.Expr hi) {
        return new Ast.Select(s.distinct(), s.items(), s.from(), s.where(), s.latestOn(), s.sampleBy(), s.groupBy(),
                s.orderBy(), new Ast.Limit(lo, hi), 0);
    }

    static Ast.Query query(final Ast.QueryBody body) {
        return new Ast.Query(body);
    }
}
