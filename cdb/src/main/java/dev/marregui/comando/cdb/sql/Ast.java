package dev.marregui.comando.cdb.sql;

import java.util.List;

/**
 * The parse tree of one statement, as written: names unresolved, types unchecked. Identifiers keep the case they were
 * written in, without their double quotes; keywords and function names are lower case. A field that can be absent is
 * null, a list that can be absent is empty. {@code pos} is the offset in the SQL text the node starts at.
 */
public sealed interface Ast {

    /** A statement {@link Parser#parse} returns. */
    sealed interface Statement extends Ast permits Query, CreateTable, Update, Show {
    }

    /** A query: a select, a set operation, or a {@code WITH}. */
    sealed interface QueryBody extends Ast permits Select, SetOp, With {
    }

    /** Something a query reads from. */
    sealed interface From extends Ast permits TableName, TableFunction, Subquery, Join {
    }

    /** An expression. */
    sealed interface Expr extends Ast permits Literal, Ident, Star, Unary, Binary, Call, Cast, Case, In, Between, IsNull,
            Like, ScalarQuery {
    }

    /** {@code SELECT …} or any query as a statement. */
    record Query(QueryBody body) implements Statement {
    }

    record Select(
            boolean distinct,
            List<SelectItem> items,
            From from,
            Expr where,
            LatestOn latestOn,
            SampleBy sampleBy,
            List<Expr> groupBy,
            List<OrderItem> orderBy,
            Limit limit,
            int pos
    ) implements QueryBody {
    }

    /** {@code op} is {@code union}, {@code union all}, {@code except} or {@code intersect}. */
    record SetOp(String op, QueryBody left, QueryBody right, List<OrderItem> orderBy, Limit limit, int pos)
            implements QueryBody {
    }

    record With(List<Cte> ctes, QueryBody body) implements QueryBody {
    }

    record Cte(String name, QueryBody body) {
    }

    /** One output column; {@code alias} null when not given. */
    record SelectItem(Expr expr, String alias) {
    }

    record TableName(String name, String alias, int pos) implements From {
    }

    /** {@code tables()}, {@code table_columns('t')}, {@code long_sequence(10)}. */
    record TableFunction(String name, List<Expr> args, String alias, int pos) implements From {
    }

    record Subquery(QueryBody body, String alias, int pos) implements From {
    }

    /** {@code kind} is {@code inner}, {@code left}, {@code cross}; {@code on} null for a cross join. */
    record Join(From left, From right, String kind, Expr on, int pos) implements From {
    }

    /** {@code LATEST ON ts PARTITION BY a, b}. */
    record LatestOn(String timestamp, List<String> partitionBy) {
    }

    /** {@code SAMPLE BY 1h [FILL(…)] [ALIGN TO CALENDAR]}: {@code unit} one of U, T, s, m, h, d, M, y. */
    record SampleBy(long count, String unit, List<String> fill, boolean alignToCalendar) {
    }

    record OrderItem(Expr expr, boolean descending) {
    }

    /** {@code LIMIT n} has {@code hi} null; {@code LIMIT a, b} both. A negative {@code n} counts from the end. */
    record Limit(Expr lo, Expr hi) {
    }

    /** One column of {@code CREATE TABLE}: its name and type as written, upper case ({@code SYMBOL}, {@code INT}). */
    record ColumnSpec(String name, String type) {
    }

    /**
     * {@code CREATE TABLE [IF NOT EXISTS] t (…) [TIMESTAMP(ts)] [PARTITION BY DAY] [TTL n UNIT] [WAL|BYPASS WAL]
     * [DEDUP UPSERT KEYS(…)]}; {@code ttl} as written ({@code 30 DAYS}) or null; other clauses ({@code CAPACITY},
     * {@code WAL}) are read and dropped.
     */
    record CreateTable(
            String name,
            boolean ifNotExists,
            List<ColumnSpec> columns,
            String timestamp,
            String partitionBy,
            String ttl,
            List<String> dedupKeys,
            int pos
    ) implements Statement {
    }

    record Assignment(String column, Expr value) {
    }

    record Update(String table, List<Assignment> assignments, Expr where, int pos) implements Statement {
    }

    /** {@code SHOW TABLES} has {@code table} null; {@code SHOW COLUMNS FROM t}. */
    record Show(String what, String table, int pos) implements Statement {
    }

    /** A Long, Double, Boolean or String; null for {@code NULL}. */
    record Literal(Object value, int pos) implements Expr {
    }

    /** {@code a} or {@code t.a}. */
    record Ident(List<String> parts, int pos) implements Expr {
    }

    /** {@code *} or {@code t.*}; {@code qualifier} null for {@code *}. */
    record Star(String qualifier, int pos) implements Expr {
    }

    /** {@code -}, {@code not}. */
    record Unary(String op, Expr operand, int pos) implements Expr {
    }

    /** {@code + - * / % || = != <> < <= > >= and or}; {@code <>} is written {@code !=}. */
    record Binary(String op, Expr left, Expr right, int pos) implements Expr {
    }

    /**
     * A function call; {@code count(*)} has {@code star} and no args; {@code over} non-null for a window function.
     */
    record Call(String name, List<Expr> args, boolean distinct, boolean star, Over over, int pos) implements Expr {
    }

    record Over(List<Expr> partitionBy, List<OrderItem> orderBy) {
    }

    /** {@code CAST(x AS T)} and {@code x::T}; {@code type} upper case. */
    record Cast(Expr operand, String type, int pos) implements Expr {
    }

    /** {@code CASE [operand] WHEN … THEN … [ELSE …] END}. */
    record Case(Expr operand, List<When> whens, Expr otherwise, int pos) implements Expr {
    }

    record When(Expr condition, Expr value) {
    }

    /** {@code x [NOT] IN (a, b)} or {@code x IN (SELECT …)} ({@code query} non-null, {@code values} empty). */
    record In(Expr operand, List<Expr> values, QueryBody query, boolean not, int pos) implements Expr {
    }

    record Between(Expr operand, Expr low, Expr high, boolean not, int pos) implements Expr {
    }

    record IsNull(Expr operand, boolean not, int pos) implements Expr {
    }

    /** {@code [NOT] LIKE} or {@code ILIKE}. */
    record Like(Expr operand, Expr pattern, boolean not, boolean caseInsensitive, int pos) implements Expr {
    }

    /** {@code (SELECT …)} as a value. */
    record ScalarQuery(QueryBody body, int pos) implements Expr {
    }
}
