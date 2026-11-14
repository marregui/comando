package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.sql.Ast;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.TableDef;

import static dev.marregui.comando.cdb.plan.Asts.asc;
import static dev.marregui.comando.cdb.plan.Asts.bin;
import static dev.marregui.comando.cdb.plan.Asts.call;
import static dev.marregui.comando.cdb.plan.Asts.cast;
import static dev.marregui.comando.cdb.plan.Asts.countStar;
import static dev.marregui.comando.cdb.plan.Asts.desc;
import static dev.marregui.comando.cdb.plan.Asts.distinct;
import static dev.marregui.comando.cdb.plan.Asts.groupBy;
import static dev.marregui.comando.cdb.plan.Asts.id;
import static dev.marregui.comando.cdb.plan.Asts.item;
import static dev.marregui.comando.cdb.plan.Asts.latestOn;
import static dev.marregui.comando.cdb.plan.Asts.limit;
import static dev.marregui.comando.cdb.plan.Asts.lit;
import static dev.marregui.comando.cdb.plan.Asts.orderBy;
import static dev.marregui.comando.cdb.plan.Asts.over;
import static dev.marregui.comando.cdb.plan.Asts.query;
import static dev.marregui.comando.cdb.plan.Asts.sampleBy;
import static dev.marregui.comando.cdb.plan.Asts.select;
import static dev.marregui.comando.cdb.plan.Asts.star;
import static dev.marregui.comando.cdb.plan.Asts.table;
import static dev.marregui.comando.cdb.plan.Asts.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Planner] turns hand-built parse trees into plans; each test pins the plan's text ([PlanText]) for one statement,
/// with output names as QuestDB gives them.
class PlannerTest {

    private static final long NOW = Times.parse("2026-11-14T12:00:00", 0);

    private static final Map<String, TableDef> TABLES = Map.of(
            "trades", new TableDef("trades", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("sym", CdbType.SYMBOL),
                    new ColumnDef("price", CdbType.DOUBLE),
                    new ColumnDef("qty", CdbType.LONG)), 0, PartitionBy.DAY, List.of(), 0),
            "comando_usage", new TableDef("comando_usage", List.of(
                    new ColumnDef("ts", CdbType.TIMESTAMP),
                    new ColumnDef("provider", CdbType.SYMBOL),
                    new ColumnDef("window_name", CdbType.SYMBOL),
                    new ColumnDef("pct", CdbType.DOUBLE)), 0, PartitionBy.DAY, List.of(), 0)
    );

    /// Test stand-in for the catalog's table-function fields.
    static List<Field> sourceFields(final String function, final List<String> args) {
        return switch (function) {
            case "long_sequence" -> List.of(new Field("x", CdbType.LONG));
            case "tables", "show_tables" -> List.of(new Field("id", CdbType.LONG),
                    new Field("table_name", CdbType.VARCHAR));
            case "table_columns", "show_columns" -> List.of(new Field("column", CdbType.VARCHAR),
                    new Field("type", CdbType.VARCHAR));
            default -> throw new IllegalArgumentException(
                    "cannot find source fields [function=" + function + ", args=" + args + ']');
        };
    }

    static Plan plan(final Ast.Statement statement) {
        return Planner.plan(statement, name -> TABLES.get(name.toLowerCase(Locale.ROOT)), NOW,
                PlannerTest::sourceFields);
    }

    private static void assertPlan(final Ast.Statement statement, final String expected) {
        assertThat(PlanText.of(plan(statement))).isEqualTo(expected);
    }

    /// Selected column expressions without an aggregate are the implicit GROUP BY keys; the aggregate is named
    /// `count()` and no projection sits above it.
    @Test
    void implicitGroupBy() {
        assertPlan(query(select(table("trades"), item(id("sym")), item(countStar()))), """
                Aggregate keys=[sym:SYMBOL=$1] aggs=[count():LONG=count()]
                  Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// An aggregate inside an expression is computed by the Aggregate; the expression is a Project above it.
    @Test
    void aggregateInsideExpression() {
        assertPlan(query(select(table("trades"), item(id("sym")),
                item(call("round", call("sum", id("price")), lit(2L)), "total"))), """
                Project [sym:SYMBOL=$0, total:DOUBLE=round($1, 2)]
                  Aggregate keys=[sym:SYMBOL=$1] aggs=[sum(price):DOUBLE=sum($2)]
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// ORDER BY an alias sorts by that output column.
    @Test
    void orderByAlias() {
        assertPlan(query(orderBy(select(table("trades"), item(id("sym")), item(countStar(), "n")), desc(id("n")))),
                """
                Sort [$1 desc]
                  Aggregate keys=[sym:SYMBOL=$1] aggs=[n:LONG=count()]
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// ORDER BY a 1-based position sorts by that output column.
    @Test
    void orderByPosition() {
        assertPlan(query(orderBy(select(table("trades"), item(id("sym")), item(id("price"))), asc(lit(2L)))), """
                Sort [$1]
                  Project [sym:SYMBOL=$1, price:DOUBLE=$2]
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// ORDER BY an expression not selected is projected for the sort and dropped after it.
    @Test
    void orderByHiddenExpression() {
        assertPlan(query(orderBy(select(table("trades"), item(id("sym"))),
                desc(bin("*", id("price"), id("qty"))))), """
                Project [sym:SYMBOL=$0]
                  Sort [$1 desc]
                    Project [sym:SYMBOL=$1, $order0:DOUBLE=*($2, $3)]
                      Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// ORDER BY the text of a selected aggregate sorts by its output column.
    @Test
    void orderBySelectedAggregate() {
        assertPlan(query(orderBy(groupBy(select(table("trades"), item(id("sym")), item(countStar())), id("sym")),
                desc(countStar()))), """
                Sort [$1 desc]
                  Aggregate keys=[sym:SYMBOL=$1] aggs=[count():LONG=count()]
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// SAMPLE BY buckets the designated timestamp: the bucket first, then the keys and aggregates, as the corpus's
    /// usage query reads them.
    @Test
    void sampleByBucketsTheTimestamp() {
        final Ast.Select s = where(select(table("comando_usage"), item(id("ts")), item(id("window_name")),
                item(call("last", id("pct")), "pct")), bin("=", id("provider"), lit("claude")));
        assertPlan(query(sampleBy(s, 1, "h")), """
                SampleBy 1h ts=$0 keys=[window_name:SYMBOL=$2] aggs=[pct:DOUBLE=last($3)]
                  Filter =($1, 'claude')
                    Scan comando_usage [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// LATEST ON keeps the table's columns and names the timestamp and partition columns by index.
    @Test
    void latestOnPartitions() {
        assertPlan(query(latestOn(select(table("comando_usage"), star()), "ts", "provider", "window_name")), """
                LatestOn ts=$0 partition=[1, 2]
                  Scan comando_usage [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// UNION ALL of two subqueries: a typed NULL on the left meets an average on the right, both DOUBLE; ORDER BY and
    /// LIMIT inside the left subquery stay there.
    @Test
    void unionAllOfSubqueriesWithCastNull() {
        final Ast.Select left = limit(orderBy(groupBy(select(table("trades"), item(lit("a"), "part"),
                item(cast(id("sym"), "VARCHAR"), "key"), item(countStar(), "n"),
                item(cast(lit(null), "DOUBLE"), "minutes")), id("sym")), desc(id("n"))), lit(5L), null);
        final Ast.Select right = groupBy(select(table("comando_usage"), item(lit("b"), "part"),
                item(cast(id("provider"), "VARCHAR"), "key"), item(countStar(), "n"),
                item(call("avg", id("pct")), "minutes")), id("provider"));
        final Ast.QueryBody union = new Ast.SetOp("union all",
                select(new Ast.Subquery(left, null, 0), star()),
                select(new Ast.Subquery(right, null, 0), star()), List.of(), null, 0);
        assertPlan(query(union), """
                SetOp union all
                  Limit 0, 5
                    Sort [$2 desc]
                      Project [part:VARCHAR='a', key:VARCHAR=cast($0)::VARCHAR, n:LONG=$1, minutes:DOUBLE=NULL:DOUBLE]
                        Aggregate keys=[sym:SYMBOL=$1] aggs=[count():LONG=count()]
                          Scan trades [0, 1, 2, 3] [-inf, +inf)
                  Project [part:VARCHAR='b', key:VARCHAR=cast($0)::VARCHAR, n:LONG=$1, minutes:DOUBLE=$2]
                    Aggregate keys=[provider:SYMBOL=$1] aggs=[count():LONG=count(), avg(pct):DOUBLE=avg($3)]
                      Scan comando_usage [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// A UNION ALL widens LONG on one side and DOUBLE on the other to DOUBLE, casting the narrower side.
    @Test
    void unionWidensTypes() {
        final Ast.QueryBody union = new Ast.SetOp("union all",
                select(table("trades"), item(id("qty"), "v")),
                select(table("trades"), item(id("price"))), List.of(), null, 0);
        assertPlan(query(union), """
                SetOp union all
                  Project [v:DOUBLE=cast($0)::DOUBLE]
                    Project [v:LONG=$3]
                      Scan trades [0, 1, 2, 3] [-inf, +inf)
                  Project [price:DOUBLE=$2]
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// A window call over a CTE: partition and order columns by index, the call's output after the input's columns.
    @Test
    void windowCallOverCte() {
        final Ast.Select cte = select(table("trades"), item(id("ts")), item(id("sym")), item(id("price")));
        final Ast.Select body = select(table("s"), item(id("ts")), item(id("sym")),
                item(over(call("lag", id("price")), List.of(id("sym")), asc(id("ts"))), "prev"));
        assertPlan(query(new Ast.With(List.of(new Ast.Cte("s", cte)), body)), """
                Project [ts:TIMESTAMP=$0, sym:SYMBOL=$1, prev:DOUBLE=$3]
                  Window [lag(price):DOUBLE=lag($2) over(partition=[$1] order=[$0])]
                    Project [ts:TIMESTAMP=$0, sym:SYMBOL=$1, price:DOUBLE=$2]
                      Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// Comparisons of the designated timestamp with string literals bound the scan to `[from, to)`; the filter stays.
    @Test
    void timeRangePruning() {
        final Ast.Expr p = bin("and", bin(">=", id("ts"), lit("2026-11-01")), bin("<", id("ts"), lit("2026-11-14")));
        assertPlan(query(where(select(table("trades"), item(id("price"))), p)), """
                Project [price:DOUBLE=$2]
                  Filter and(>=($0, 2026-11-01T00:00:00Z), <($0, 2026-11-14T00:00:00Z))
                    Scan trades [0, 1, 2, 3] [2026-11-01T00:00:00Z, 2026-11-14T00:00:00Z)
                """);
    }

    /// `dateadd('d', -1, now())` is folded at plan time to bound the scan.
    @Test
    void timeRangePruningFromNow() {
        final Ast.Expr p = bin(">", id("ts"),
                call("dateadd", lit("d"), new Ast.Unary("-", lit(1L), 0), call("now")));
        assertPlan(query(where(select(table("trades"), item(id("price"))), p)), """
                Project [price:DOUBLE=$2]
                  Filter >($0, dateadd('d', -1, now()))
                    Scan trades [0, 1, 2, 3] [2026-11-13T12:00:00.000001Z, +inf)
                """);
    }

    /// `ts IN '2026-11'` is the month's range, and bounds the scan to it.
    @Test
    void timeRangePruningFromInterval() {
        final Ast.Expr p = new Ast.In(id("ts"), List.of(lit("2026-11")), null, false, 0);
        assertPlan(query(where(select(table("trades"), item(id("price"))), p)), """
                Project [price:DOUBLE=$2]
                  Filter and(>=($0, 2026-11-01T00:00:00Z), <($0, 2026-12-01T00:00:00Z))
                    Scan trades [0, 1, 2, 3] [2026-11-01T00:00:00Z, 2026-12-01T00:00:00Z)
                """);
    }

    /// CREATE TABLE maps INT to LONG, keeps the designated timestamp, the partitioning, the dedup keys and the TTL
    /// in microseconds.
    @Test
    void createTableWithDedupAndTtl() {
        final Ast.CreateTable create = new Ast.CreateTable("kit_run", true, List.of(
                new Ast.ColumnSpec("ts", "TIMESTAMP"), new Ast.ColumnSpec("task", "SYMBOL"),
                new Ast.ColumnSpec("n", "INT"), new Ast.ColumnSpec("ok", "BOOLEAN")),
                "ts", "DAY", "30 DAYS", List.of("ts", "task"), 0);
        assertPlan(create, """
                CreateTable kit_run if not exists (ts:TIMESTAMP, task:SYMBOL, n:LONG, ok:BOOLEAN) timestamp=0 partition=DAY dedup=[ts, task] ttlMicros=2592000000000
                """);
    }

    /// A dedup key list without the designated timestamp is refused.
    @Test
    void createTableDedupWithoutTimestampIsRefused() {
        final Ast.CreateTable create = new Ast.CreateTable("kit_run", false, List.of(
                new Ast.ColumnSpec("ts", "TIMESTAMP"), new Ast.ColumnSpec("task", "SYMBOL")),
                "ts", "DAY", null, List.of("task"), 0);
        assertThatThrownBy(() -> plan(create)).isInstanceOf(CdbException.class);
    }

    /// UPDATE casts each value to its column's type and binds the filter with timestamp literals read.
    @Test
    void updateCastsValues() {
        final Ast.Update update = new Ast.Update("trades", List.of(new Ast.Assignment("qty", lit(0L)),
                new Ast.Assignment("sym", lit("x"))), bin("<", id("ts"), lit("2026-01-01")), 0);
        assertPlan(update, """
                Update trades set $3=0, $1=cast('x')::SYMBOL where <($0, 2026-01-01T00:00:00Z)
                """);
    }

    /// UPDATE of the designated timestamp is refused.
    @Test
    void updateOfDesignatedTimestampIsRefused() {
        final Ast.Update update = new Ast.Update("trades", List.of(new Ast.Assignment("TS", lit("2026-01-01"))),
                null, 0);
        assertThatThrownBy(() -> plan(update)).isInstanceOf(CdbException.class);
    }

    /// SHOW COLUMNS FROM a table is the catalog listing of that table, by its stored name.
    @Test
    void showColumns() {
        assertPlan(new Ast.Show("columns", "TRADES", 0), """
                Source show_columns[trades]
                """);
    }

    /// SHOW TABLES is the catalog listing of tables.
    @Test
    void showTables() {
        assertPlan(new Ast.Show("tables", null, 0), """
                Source show_tables[]
                """);
    }

    /// Names resolve without regard to case, through a table alias; a lone column keeps its name as written.
    @Test
    void namesResolveWithoutRegardToCase() {
        assertPlan(query(select(table("Trades", "t"), item(id("T", "PRICE")))), """
                Project [PRICE:DOUBLE=$2]
                  Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// An unaliased expression is named by its text, `x + 1`; beside an aggregate it is an implicit key.
    @Test
    void expressionNamedByItsText() {
        final Ast.Select s = select(new Ast.TableFunction("long_sequence", List.of(lit(3L)), null, 0),
                item(bin("+", id("x"), lit(1L))), item(call("count_distinct", id("x"))));
        assertPlan(query(s), """
                Aggregate keys=[x + 1:LONG=+($0, 1)] aggs=[count_distinct(x):LONG=count_distinct($0)]
                  Source long_sequence[3]
                """);
    }

    /// `LIMIT -2` keeps the last two rows; `LIMIT 1, 3` rows one and two.
    @Test
    void limits() {
        final Ast.Select s = select(new Ast.TableFunction("long_sequence", List.of(lit(5L)), null, 0), item(id("x")));
        assertPlan(query(limit(s, new Ast.Unary("-", lit(2L), 0), null)), """
                Limit -2, 0
                  Source long_sequence[5]
                """);
        assertPlan(query(limit(s, lit(1L), lit(3L))), """
                Limit 1, 3
                  Source long_sequence[5]
                """);
    }

    /// A join of a table with itself: both sides' columns, the second `sym` named `sym_2`.
    @Test
    void joinNamesDuplicates() {
        final Ast.From join = new Ast.Join(table("trades", "a"), table("trades", "b"), "inner",
                bin("=", id("a", "sym"), id("b", "sym")), 0);
        final Plan plan = plan(query(select(join, item(id("a", "sym")), item(id("b", "sym")))));
        assertThat(PlanText.of(plan)).isEqualTo("""
                Project [sym:SYMBOL=$1, sym_2:SYMBOL=$5]
                  Join inner on =($1, $5)
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
        assertThat(((Plan.Project) plan).child().fields()).extracting(Field::name)
                .containsExactly("ts", "sym", "price", "qty", "ts_2", "sym_2", "price_2", "qty_2");
    }

    /// DISTINCT sits above the projection.
    @Test
    void distinctRows() {
        assertPlan(query(distinct(select(table("trades"), item(id("sym"))))), """
                Distinct
                  Project [sym:SYMBOL=$1]
                    Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }

    /// `tables()` is a source with the catalog's fields.
    @Test
    void tablesFunction() {
        assertPlan(query(select(new Ast.TableFunction("tables", List.of(), null, 0), item(id("table_name")))), """
                Project [table_name:VARCHAR=$1]
                  Source tables[]
                """);
    }

    /// A column selected beside an aggregate but outside an explicit GROUP BY is refused.
    @Test
    void columnOutsideGroupByIsRefused() {
        final Ast.Select s = groupBy(select(table("trades"), item(id("sym")), item(id("price")), item(countStar())),
                id("sym"));
        assertThatThrownBy(() -> plan(query(s))).isInstanceOf(CdbException.class);
    }

    /// An unknown column is refused at its name.
    @Test
    void unknownColumnIsRefused() {
        assertThatThrownBy(() -> plan(query(select(table("trades"), item(id("nope"))))))
                .isInstanceOf(CdbException.class);
    }

    /// Two items aliased alike are refused; two derived alike are numbered.
    @Test
    void duplicateNames() {
        assertThatThrownBy(() -> plan(query(select(table("trades"), item(id("sym"), "a"), item(id("qty"), "a")))))
                .isInstanceOf(CdbException.class);
        assertPlan(query(select(table("trades"), item(id("sym")), item(id("sym")))), """
                Project [sym:SYMBOL=$1, sym_2:SYMBOL=$1]
                  Scan trades [0, 1, 2, 3] [-inf, +inf)
                """);
    }
}
