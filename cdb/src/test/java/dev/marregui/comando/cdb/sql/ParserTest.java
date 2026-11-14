package dev.marregui.comando.cdb.sql;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;

/// The exact tree the parser builds for each clause and expression form, and where its errors point. A node's
/// position is the offset of the text it starts at, found here by searching the statement for that text.
class ParserTest {

    /// Keywords match in any case; an alias follows `AS`; a quoted identifier keeps its case.
    @Test
    void selectListAndAliases() {
        final String sql = "SELECT a, b AS bee, \"Mixed\" FROM t";
        assertQuery(sql, select(
                List.of(item(ident(at(sql, "a,"), "a")), item(ident(at(sql, "b AS"), "b"), "bee"),
                        item(ident(at(sql, "\"Mixed\""), "Mixed"))),
                table("t", null, sql.lastIndexOf("t")), null, 0));
    }

    /// `DISTINCT`, a table alias, a double literal and a comparison.
    @Test
    void distinctAndTableAlias() {
        final String sql = "select distinct sym from trades t where price > 1.5";
        assertQuery(sql, new Ast.Select(true, List.of(item(ident(at(sql, "sym"), "sym"))),
                table("trades", "t", at(sql, "trades")),
                new Ast.Binary(">", ident(at(sql, "price"), "price"), literal(1.5, at(sql, "1.5")), at(sql, "price")),
                null, null, List.of(), List.of(), null, 0));
    }

    /// `<>` is read as `!=`.
    @Test
    void notEqualsIsNormalised() {
        final String sql = "select * from t where a <> 1";
        assertQuery(sql, select(List.of(item(new Ast.Star(null, at(sql, "*")))), table("t", null, after(sql, "from ")),
                new Ast.Binary("!=", ident(at(sql, "a <>"), "a"), literal(1L, at(sql, "1")), at(sql, "a <>")), 0));
    }

    /// `*` binds tighter than `+`, and `+ -` associate to the left.
    @Test
    void arithmeticPrecedence() {
        final String sql = "select 1 + 2 * 3 - 4";
        final Ast.Expr product = new Ast.Binary("*", literal(2L, at(sql, "2")), literal(3L, at(sql, "3")),
                at(sql, "2"));
        final Ast.Expr sum = new Ast.Binary("+", literal(1L, at(sql, "1")), product, at(sql, "1"));
        assertQuery(sql, select(List.of(item(new Ast.Binary("-", sum, literal(4L, at(sql, "4")), at(sql, "1")))),
                null, null, 0));
    }

    /// `OR` is looser than `AND`, `AND` looser than `NOT`, `NOT` looser than a comparison.
    @Test
    void logicalPrecedence() {
        final String sql = "select a or b and not c = d";
        final Ast.Expr comparison = new Ast.Binary("=", ident(at(sql, "c ="), "c"), ident(sql.lastIndexOf("d"), "d"),
                at(sql, "c ="));
        final Ast.Expr conjunction = new Ast.Binary("and", ident(at(sql, "b"), "b"),
                new Ast.Unary("not", comparison, at(sql, "not")), at(sql, "b"));
        assertQuery(sql, select(List.of(item(new Ast.Binary("or", ident(at(sql, "a "), "a"), conjunction,
                at(sql, "a ")))), null, null, 0));
    }

    /// A doubled quote is one quote; numbers with a fraction or an exponent are doubles, others longs.
    @Test
    void literals() {
        final String sql = "select 'it''s', 1.5, 2e3, 42, null, true";
        assertQuery(sql, select(List.of(item(literal("it's", at(sql, "'it"))), item(literal(1.5, at(sql, "1.5"))),
                item(literal(2000.0, at(sql, "2e3"))), item(literal(42L, at(sql, "42"))),
                item(literal(null, at(sql, "null"))), item(literal(true, at(sql, "true")))), null, null, 0));
    }

    /// A minus before a number is part of the literal, unless `::` follows the number; before anything else it is
    /// a unary minus.
    @Test
    void negation() {
        final String sql = "select -5, -x, -2::double";
        final Ast.Expr cast = new Ast.Cast(literal(2L, at(sql, "2")), "DOUBLE", at(sql, "2"));
        assertQuery(sql, select(List.of(item(literal(-5L, at(sql, "-5"))),
                item(new Ast.Unary("-", ident(at(sql, "x"), "x"), at(sql, "-x"))),
                item(new Ast.Unary("-", cast, at(sql, "-2")))), null, null, 0));
    }

    /// `||` binds tighter than a comparison.
    @Test
    void concatenation() {
        final String sql = "select a || 'x' = 'y'";
        final Ast.Expr concat = new Ast.Binary("||", ident(at(sql, "a"), "a"), literal("x", at(sql, "'x'")),
                at(sql, "a"));
        assertQuery(sql, select(List.of(item(new Ast.Binary("=", concat, literal("y", at(sql, "'y'")),
                at(sql, "a")))), null, null, 0));
    }

    /// `IN` over a list, `NOT IN` over a subquery.
    @Test
    void inListAndSubquery() {
        final String sql = "select x from t where x in (1, 2) and y not in (select y from u)";
        final Ast.Expr inList = new Ast.In(ident(at(sql, "x in"), "x"),
                List.of(literal(1L, at(sql, "1")), literal(2L, at(sql, "2"))), null, false, at(sql, "x in"));
        final Ast.Select sub = select(List.of(item(ident(at(sql, "y from"), "y"))), table("u", null, at(sql, "u)")),
                null, at(sql, "select y"));
        final Ast.Expr notIn = new Ast.In(ident(at(sql, "y not"), "y"), List.of(), sub, true, at(sql, "y not"));
        assertQuery(sql, select(List.of(item(ident(at(sql, "x "), "x"))), table("t", null, after(sql, "from ")),
                new Ast.Binary("and", inList, notIn, at(sql, "x in")), 0));
    }

    /// `IN` without parentheses takes one value: QuestDB's interval form.
    @Test
    void inInterval() {
        final String sql = "select x from t where ts in '2024-01'";
        assertQuery(sql, select(List.of(item(ident(at(sql, "x"), "x"))), table("t", null, after(sql, "from ")),
                new Ast.In(ident(at(sql, "ts"), "ts"), List.of(literal("2024-01", at(sql, "'"))), null, false,
                        at(sql, "ts")), 0));
    }

    /// `BETWEEN … AND …` takes its `AND`, so a following `AND` is the conjunction.
    @Test
    void between() {
        final String sql = "select x from t where x between 1 and 2 and y not between 3 and 4";
        final Ast.Expr first = new Ast.Between(ident(at(sql, "x between"), "x"), literal(1L, at(sql, "1")),
                literal(2L, at(sql, "2")), false, at(sql, "x between"));
        final Ast.Expr second = new Ast.Between(ident(at(sql, "y"), "y"), literal(3L, at(sql, "3")),
                literal(4L, at(sql, "4")), true, at(sql, "y"));
        assertQuery(sql, select(List.of(item(ident(at(sql, "x "), "x"))), table("t", null, after(sql, "from ")),
                new Ast.Binary("and", first, second, at(sql, "x between")), 0));
    }

    /// `IS NULL` and `IS NOT NULL`.
    @Test
    void isNull() {
        final String sql = "select x from t where x is null or y is not null";
        final Ast.Expr where = new Ast.Binary("or", new Ast.IsNull(ident(at(sql, "x is"), "x"), false,
                at(sql, "x is")), new Ast.IsNull(ident(at(sql, "y"), "y"), true, at(sql, "y")), at(sql, "x is"));
        assertQuery(sql, select(List.of(item(ident(at(sql, "x "), "x"))), table("t", null, after(sql, "from ")), where,
                0));
    }

    /// `LIKE` and `NOT ILIKE`.
    @Test
    void like() {
        final String sql = "select x from t where s like 'a%' and s not ilike 'b%'";
        final Ast.Expr where = new Ast.Binary("and",
                new Ast.Like(ident(at(sql, "s like"), "s"), literal("a%", at(sql, "'a%'")), false, false,
                        at(sql, "s like")),
                new Ast.Like(ident(at(sql, "s not"), "s"), literal("b%", at(sql, "'b%'")), true, true,
                        at(sql, "s not")),
                at(sql, "s like"));
        assertQuery(sql, select(List.of(item(ident(at(sql, "x "), "x"))), table("t", null, after(sql, "from ")), where,
                0));
    }

    /// A searched `CASE` with `ELSE`, and a simple `CASE` without.
    @Test
    void caseExpressions() {
        final String sql = "select case when a > 1 then 'big' else 'small' end, case k when 1 then 'one' end";
        final Ast.Expr searched = new Ast.Case(null, List.of(new Ast.When(new Ast.Binary(">",
                ident(at(sql, "a >"), "a"), literal(1L, at(sql, "1 then")), at(sql, "a >")),
                literal("big", at(sql, "'big'")))), literal("small", at(sql, "'small'")), at(sql, "case when"));
        final Ast.Expr simple = new Ast.Case(ident(at(sql, "k"), "k"),
                List.of(new Ast.When(literal(1L, at(sql, "1 then 'one'")), literal("one", at(sql, "'one'")))), null,
                at(sql, "case k"));
        assertQuery(sql, select(List.of(item(searched), item(simple)), null, null, 0));
    }

    /// `CAST(… AS …)`, `::`, `count(*)`, `count(DISTINCT …)` and a call without arguments.
    @Test
    void castsAndCalls() {
        final String sql = "select cast(a as int), a::long, count(*), COUNT(distinct s), now()";
        assertQuery(sql, select(List.of(
                item(new Ast.Cast(ident(at(sql, "a as"), "a"), "INT", at(sql, "cast"))),
                item(new Ast.Cast(ident(at(sql, "a::"), "a"), "LONG", at(sql, "a::"))),
                item(new Ast.Call("count", List.of(), false, true, null, at(sql, "count(*)"))),
                item(new Ast.Call("count", List.of(ident(at(sql, "s)"), "s")), true, false, null,
                        at(sql, "COUNT"))),
                item(new Ast.Call("now", List.of(), false, false, null, at(sql, "now")))), null, null, 0));
    }

    /// A window function's `OVER (PARTITION BY … ORDER BY …)`.
    @Test
    void windowFunction() {
        final String sql = "select row_number() over (partition by s order by ts desc) from t";
        final Ast.Over over = new Ast.Over(List.of(ident(at(sql, "s order"), "s")),
                List.of(new Ast.OrderItem(ident(at(sql, "ts"), "ts"), true)));
        assertQuery(sql, select(List.of(item(new Ast.Call("row_number", List.of(), false, false, over,
                at(sql, "row_number")))), table("t", null, sql.lastIndexOf("t")), null, 0));
    }

    /// A parenthesised select in an expression is a scalar subquery; a bare word after an expression is its alias.
    @Test
    void scalarSubquery() {
        final String sql = "select (select max(x) from t) m";
        final Ast.Select inner = select(List.of(item(new Ast.Call("max", List.of(ident(at(sql, "x)"), "x")), false,
                false, null, at(sql, "max")))), table("t", null, at(sql, "t)")), null, at(sql, "select max"));
        assertQuery(sql, select(List.of(item(new Ast.ScalarQuery(inner, at(sql, "(")), "m")), null, null, 0));
    }

    /// Dotted names are one identifier of several parts; `t.*` is a qualified star.
    @Test
    void qualifiedNames() {
        final String sql = "select t.a, t.* from t";
        assertQuery(sql, select(List.of(item(ident(at(sql, "t.a"), "t", "a")), item(new Ast.Star("t",
                at(sql, "t.*")))), table("t", null, sql.lastIndexOf("t")), null, 0));
    }

    /// `JOIN`, `LEFT OUTER JOIN` and `CROSS JOIN` nest to the left; every join starts where the from clause does.
    @Test
    void joins() {
        final String sql = "select * from t join u on t.id = u.id left outer join v on u.k = v.k cross join w";
        final int from = at(sql, "t join");
        final Ast.From inner = new Ast.Join(table("t", null, from), table("u", null, at(sql, "u on")), "inner",
                new Ast.Binary("=", ident(at(sql, "t.id"), "t", "id"), ident(at(sql, "u.id"), "u", "id"),
                        at(sql, "t.id")), from);
        final Ast.From left = new Ast.Join(inner, table("v", null, at(sql, "v on")), "left",
                new Ast.Binary("=", ident(at(sql, "u.k"), "u", "k"), ident(at(sql, "v.k"), "v", "k"),
                        at(sql, "u.k")), from);
        final Ast.From cross = new Ast.Join(left, table("w", null, at(sql, "w")), "cross", null, from);
        assertQuery(sql, select(List.of(item(new Ast.Star(null, at(sql, "*")))), cross, null, 0));
    }

    /// A table function in a from clause, and a table function alone as the statement.
    @Test
    void tableFunctions() {
        final String sql = "select * from long_sequence(3) ls";
        assertQuery(sql, select(List.of(item(new Ast.Star(null, at(sql, "*")))), new Ast.TableFunction(
                "long_sequence", List.of(literal(3L, at(sql, "3"))), "ls", at(sql, "long")), null, 0));
        assertQuery("tables()", select(List.of(item(new Ast.Star(null, 0))),
                new Ast.TableFunction("tables", List.of(), null, 0), null, 0));
    }

    /// A subquery in a from clause keeps its alias; the designated-timestamp clause is read and dropped.
    @Test
    void fromSubquery() {
        final String sql = "select * from (select a from t) timestamp(ts) sub";
        final Ast.Select inner = select(List.of(item(ident(at(sql, "a "), "a"))), table("t", null, at(sql, "t)")),
                null, at(sql, "select a"));
        assertQuery(sql, select(List.of(item(new Ast.Star(null, at(sql, "*")))),
                new Ast.Subquery(inner, "sub", at(sql, "(")), null, 0));
    }

    /// A single-quoted from item is a table name.
    @Test
    void quotedTableName() {
        final String sql = "select count() from 'trades'";
        assertQuery(sql, select(List.of(item(new Ast.Call("count", List.of(), false, false, null, at(sql, "count")))),
                table("trades", null, at(sql, "'")), null, 0));
    }

    /// `LATEST ON … PARTITION BY …`.
    @Test
    void latestOn() {
        final String sql = "select ts, last(p) from t latest on ts partition by sym, venue";
        assertQuery(sql, new Ast.Select(false, List.of(item(ident(at(sql, "ts"), "ts")), item(new Ast.Call("last",
                List.of(ident(at(sql, "p"), "p")), false, false, null, at(sql, "last")))),
                table("t", null, at(sql, "t latest")), null, new Ast.LatestOn("ts", List.of("sym", "venue")), null,
                List.of(), List.of(), null, 0));
    }

    /// `SAMPLE BY` with a unit glued to its count, `FILL` values and both alignments; `M` and `m` differ.
    @Test
    void sampleBy() {
        assertThat(sampleBy("select avg(p) from t sample by 1h fill(prev, NULL, 0, -1) align to calendar"))
                .isEqualTo(new Ast.SampleBy(1, "h", List.of("prev", "null", "0", "-1"), true));
        assertThat(sampleBy("select avg(p) from t sample by 15m align to first observation"))
                .isEqualTo(new Ast.SampleBy(15, "m", List.of(), false));
        assertThat(sampleBy("select avg(p) from t sample by 1M"))
                .isEqualTo(new Ast.SampleBy(1, "M", List.of(), false));
    }

    /// `GROUP BY`, `ORDER BY` with a direction, and `LIMIT lo, hi`.
    @Test
    void groupOrderLimit() {
        final String sql = "select a, count() from t group by a order by a desc, 2 limit 10, 20";
        assertQuery(sql, new Ast.Select(false, List.of(item(ident(at(sql, "a,"), "a")),
                item(new Ast.Call("count", List.of(), false, false, null, at(sql, "count")))),
                table("t", null, after(sql, "from ")), null, null, null, List.of(ident(at(sql, "a order"), "a")),
                List.of(new Ast.OrderItem(ident(at(sql, "a desc"), "a"), true),
                        new Ast.OrderItem(literal(2L, at(sql, "2")), false)),
                new Ast.Limit(literal(10L, at(sql, "10")), literal(20L, at(sql, "20"))), 0));
    }

    /// An `ORDER BY` and `LIMIT` after the last operand of a set operation apply to the whole of it.
    @Test
    void unionOrderAndLimit() {
        final String sql = "select a from t union all select a from u order by a limit 5";
        final Ast.Select left = select(List.of(item(ident(at(sql, "a from t"), "a"))), table("t", null,
                after(sql, "from ")), null, 0);
        final Ast.Select right = select(List.of(item(ident(at(sql, "a from u"), "a"))), table("u", null,
                at(sql, "u ")), null, at(sql, "select a from u"));
        assertQuery(sql, new Ast.SetOp("union all", left, right,
                List.of(new Ast.OrderItem(ident(at(sql, "a limit"), "a"), false)),
                new Ast.Limit(literal(5L, at(sql, "5")), null), 0));
    }

    /// `INTERSECT` binds tighter than `UNION` and `EXCEPT`, which associate to the left.
    @Test
    void setOperationPrecedence() {
        final String sql = "select 1 union select 2 intersect select 3 except select 4";
        final Ast.QueryBody intersect = new Ast.SetOp("intersect", constant(sql, 2), constant(sql, 3), List.of(),
                null, at(sql, "select 2"));
        final Ast.QueryBody union = new Ast.SetOp("union", constant(sql, 1), intersect, List.of(), null, 0);
        assertQuery(sql, new Ast.SetOp("except", union, constant(sql, 4), List.of(), null, 0));
    }

    /// An `ORDER BY` after a parenthesised query that has its own `LIMIT` orders a select over it.
    @Test
    void orderAfterLimitedQuery() {
        final String sql = "(select a from t limit 1) order by a";
        final Ast.Select inner = new Ast.Select(false, List.of(item(ident(at(sql, "a "), "a"))),
                table("t", null, after(sql, "from ")), null, null, null, List.of(), List.of(),
                new Ast.Limit(literal(1L, at(sql, "1")), null), at(sql, "select"));
        assertQuery(sql, new Ast.Select(false, List.of(item(new Ast.Star(null, 0))), new Ast.Subquery(inner, null, 0),
                null, null, null, List.of(), List.of(new Ast.OrderItem(ident(sql.lastIndexOf("a"), "a"), false)),
                null, 0));
    }

    /// A table name alone is a select of all its columns, here with a negative limit.
    @Test
    void bareTable() {
        final String sql = "t limit -5";
        assertQuery(sql, new Ast.Select(false, List.of(item(new Ast.Star(null, 0))), table("t", null, 0), null, null,
                null, List.of(), List.of(), new Ast.Limit(literal(-5L, at(sql, "-5")), null), 0));
    }

    /// `WITH` names its common table expressions before the body.
    @Test
    void with() {
        final String sql = "with c as (select a from t) select * from c";
        final Ast.Select cte = select(List.of(item(ident(at(sql, "a "), "a"))), table("t", null, at(sql, "t)")),
                null, at(sql, "select a"));
        final Ast.Select body = select(List.of(item(new Ast.Star(null, at(sql, "*")))),
                table("c", null, sql.lastIndexOf("c")), null, at(sql, "select *"));
        assertThat(Parser.parse(sql)).isEqualTo(new Ast.Query(new Ast.With(List.of(new Ast.Cte("c", cte)), body)));
    }

    /// Comments are skipped and a trailing semicolon is accepted.
    @Test
    void commentsAndSemicolon() {
        final String sql = "select a -- the column\nfrom t /* the table */;";
        assertQuery(sql, select(List.of(item(ident(at(sql, "a "), "a"))), table("t", null, at(sql, "t /*")), null,
                0));
    }

    /// `CREATE TABLE` with every clause; column options are read and dropped, types and units upper case.
    @Test
    void createTable() {
        assertThat(Parser.parse("create table if not exists t (ts timestamp, s symbol capacity 256 cache index, "
                + "v double) timestamp(ts) partition by day ttl 30 days wal dedup upsert keys(ts, s)"))
                .isEqualTo(new Ast.CreateTable("t", true, List.of(new Ast.ColumnSpec("ts", "TIMESTAMP"),
                        new Ast.ColumnSpec("s", "SYMBOL"), new Ast.ColumnSpec("v", "DOUBLE")), "ts", "DAY", "30 days",
                        List.of("ts", "s"), 0));
        assertThat(Parser.parse("CREATE TABLE x (a INT) BYPASS WAL"))
                .isEqualTo(new Ast.CreateTable("x", false, List.of(new Ast.ColumnSpec("a", "INT")), null, null,
                        null, List.of(), 0));
    }

    /// `UPDATE … SET … WHERE …`.
    @Test
    void update() {
        final String sql = "update t set a = 1, b = b + 1 where c = 'x'";
        assertThat(Parser.parse(sql)).isEqualTo(new Ast.Update("t", List.of(
                new Ast.Assignment("a", literal(1L, at(sql, "1"))),
                new Ast.Assignment("b", new Ast.Binary("+", ident(at(sql, "b +"), "b"),
                        literal(1L, at(sql, "1 where")), at(sql, "b +")))),
                new Ast.Binary("=", ident(at(sql, "c"), "c"), literal("x", at(sql, "'x'")), at(sql, "c")), 0));
    }

    /// The three `SHOW` forms.
    @Test
    void show() {
        assertThat(Parser.parse("show tables")).isEqualTo(new Ast.Show("tables", null, 0));
        assertThat(Parser.parse("SHOW COLUMNS FROM t")).isEqualTo(new Ast.Show("columns", "t", 0));
        assertThat(Parser.parse("show create table t")).isEqualTo(new Ast.Show("create table", "t", 0));
    }

    /// A malformed statement fails at the offending token, or at the text's length when it ends early.
    @Test
    void errorPositions() {
        assertThat(errorPosition("select from t")).isEqualTo(7);
        assertThat(errorPosition("select a from")).isEqualTo(13);
        assertThat(errorPosition("select a from t where")).isEqualTo(21);
        assertThat(errorPosition("select a b c")).isEqualTo(11);
        assertThat(errorPosition("select a from t sample by 1 x")).isEqualTo(28);
        assertThat(errorPosition("select 99999999999999999999")).isEqualTo(7);
        assertThat(errorPosition("select case end")).isEqualTo(12);
        assertThat(errorPosition("select 'abc")).isEqualTo(7);
    }

    private static int after(final String sql, final String fragment) {
        return at(sql, fragment) + fragment.length();
    }

    private static void assertQuery(final String sql, final Ast.QueryBody expected) {
        assertThat(Parser.parse(sql)).isEqualTo(new Ast.Query(expected));
    }

    private static int at(final String sql, final String fragment) {
        final int pos = sql.indexOf(fragment);
        assertThat(pos).as("offset of %s", fragment).isNotNegative();
        return pos;
    }

    private static Ast.Select constant(final String sql, final long value) {
        final int pos = at(sql, "select " + value);
        return select(List.of(item(literal(value, pos + "select ".length()))), null, null, pos);
    }

    private static int errorPosition(final String sql) {
        try {
            Parser.parse(sql);
        } catch (final CdbException e) {
            return e.position();
        }
        throw new AssertionError("parsed [sql=" + sql + "]");
    }

    private static Ast.Ident ident(final int pos, final String... parts) {
        return new Ast.Ident(List.of(parts), pos);
    }

    private static Ast.SelectItem item(final Ast.Expr expr) {
        return new Ast.SelectItem(expr, null);
    }

    private static Ast.SelectItem item(final Ast.Expr expr, final String alias) {
        return new Ast.SelectItem(expr, alias);
    }

    private static Ast.Literal literal(final Object value, final int pos) {
        return new Ast.Literal(value, pos);
    }

    private static Ast.SampleBy sampleBy(final String sql) {
        if (!(Parser.parse(sql) instanceof Ast.Query(
                Ast.Select(_, _, _, _, _, final Ast.SampleBy sampleBy, _, _, _, _)))) {
            throw new AssertionError("not a select [sql=" + sql + "]");
        }
        return sampleBy;
    }

    /// A select with only items, a from clause and a where clause.
    private static Ast.Select select(
            final List<Ast.SelectItem> items,
            final Ast.From from,
            final Ast.Expr where,
            final int pos
    ) {
        return new Ast.Select(false, items, from, where, null, null, List.of(), List.of(), null, pos);
    }

    private static Ast.TableName table(final String name, final String alias, final int pos) {
        return new Ast.TableName(name, alias, pos);
    }
}
