package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Bound;

import static dev.marregui.comando.cdb.exec.expr.Exprs.call;
import static dev.marregui.comando.cdb.exec.expr.Exprs.eval;
import static dev.marregui.comando.cdb.exec.expr.Exprs.lit;
import static dev.marregui.comando.cdb.exec.expr.Exprs.nul;
import static dev.marregui.comando.cdb.exec.expr.Exprs.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Each expression evaluates to what QuestDB returned for it. The expected values were captured from the local
/// QuestDB with read-only `GET /exec` queries; the comment above each group is the query that was run, and each row
/// is named by its expression in that query. INT and CHAR results of QuestDB are LONG and VARCHAR here.
class FunctionsQuestDbParityTest {

    private static final CdbType L = CdbType.LONG;
    private static final CdbType D = CdbType.DOUBLE;
    private static final CdbType B = CdbType.BOOLEAN;
    private static final CdbType V = CdbType.VARCHAR;
    private static final CdbType T = CdbType.TIMESTAMP;
    private static final String T10 = "2026-11-14T10:00:00.000000Z";
    private static final String NOW = "2026-11-14T10:49:45.097165Z";
    private static final String MARCH = "2026-03-04T05:06:07.089012Z";

    /// One expression, the Bound tree the planner would give it, and QuestDB's value as `/exec` prints it.
    record Case(String sql, Bound expr, String expected) {

        @Override
        public String toString() {
            return sql;
        }
    }

    static List<Case> cases() {
        return List.of(
                // SELECT 7 / 2, -7 / 2, 7 % 3, -7 % 3, 7.0 / 2, 1 / 0, 1.0 / 0, 0.0 / 0, 1 % 0
                // -> [3, -3, 1, -1, 3.5, null, null, null, null]
                row("7 / 2", call("/", L, lit(7), lit(2)), "3"),
                row("-7 / 2", call("/", L, lit(-7), lit(2)), "-3"),
                row("-7 % 3", call("%", L, lit(-7), lit(3)), "-1"),
                row("7.0 / 2", call("/", D, lit(7.0), lit(2)), "3.5"),
                row("1 / 0", call("/", L, lit(1), lit(0)), "null"),
                row("1.0 / 0", call("/", D, lit(1.0), lit(0)), "null"),
                row("1 % 0", call("%", L, lit(1), lit(0)), "null"),
                // SELECT NULL + 1, CAST(NULL AS LONG) + 1, CAST(NULL AS DOUBLE) * 2, 3 - 5, 2 * 3.5, -(4), -(2.5)
                // -> [null, null, null, -2, 7.0, -4, -2.5]
                row("CAST(NULL AS LONG) + 1", call("+", L, nul(L), lit(1)), "null"),
                row("CAST(NULL AS DOUBLE) * 2", call("*", D, nul(D), lit(2)), "null"),
                row("2 * 3.5", call("*", D, lit(2), lit(3.5)), "7.0"),
                row("-(2.5)", call("neg", D, lit(2.5)), "-2.5"),
                // SELECT -CAST(NULL AS LONG), -CAST(NULL AS DOUBLE), 5 - CAST(NULL AS DOUBLE), 6 * 7, 6 * 7.0,
                // 20 % 6, 2.5 * 4, -9223372036854775807 - 1 -> [null, null, null, 42, 42.0, 2, 10.0, null]
                row("-CAST(NULL AS LONG)", call("neg", L, nul(L)), "null"),
                row("-9223372036854775807 - 1", call("-", L, lit(-9223372036854775807L), lit(1)), "null"),
                row("20 % 6", call("%", L, lit(20), lit(6)), "2"),
                // SELECT 1 + 2.5, 10 - 0.5, 10 / 4.0, 10.0 % 3, 9223372036854775807 + 1, CAST(NULL AS LONG) / 2
                // -> [3.5, 9.5, 2.5, 1.0, null, null]
                row("10.0 % 3", call("%", D, lit(10.0), lit(3)), "1.0"),
                row("9223372036854775807 + 1", call("+", L, lit(Long.MAX_VALUE), lit(1)), "null"),
                // SELECT now() - now(), ts + 1, 1 + ts, ts - '2026-11-14T09:00:00.000000Z'::timestamp
                // with ts = '2026-11-14T10:00:00.000000Z'::timestamp
                // -> [0, "2026-11-14T10:00:00.000001Z", 1794650400000001, "1970-01-01T01:00:00.000000Z"]
                row("now() - now()", call("-", L, call("now", T), call("now", T)), "0"),
                row("ts + 1", call("+", T, ts(T10), lit(1)), "2026-11-14T10:00:00.000001Z"),
                row("1 + ts", call("+", L, lit(1), ts(T10)), "1794650400000001"),
                row("ts - ts", call("-", T, ts(T10), ts("2026-11-14T09:00:00.000000Z")), "1970-01-01T01:00:00.000000Z"),
                // SELECT 1 = 1, 1 = 2, 1 < 2.5, CAST(NULL AS LONG) = CAST(NULL AS LONG), CAST(NULL AS LONG) < 1,
                // CAST(NULL AS LONG) != 1, CAST(NULL AS DOUBLE) = CAST(NULL AS DOUBLE)
                // -> [true, false, true, true, false, true, true]
                row("1 < 2.5", call("<", B, lit(1), lit(2.5)), "true"),
                row("NULL LONG = NULL LONG", call("=", B, nul(L), nul(L)), "true"),
                row("NULL LONG < 1", call("<", B, nul(L), lit(1)), "false"),
                row("NULL LONG != 1", call("!=", B, nul(L), lit(1)), "true"),
                row("NULL DOUBLE = NULL DOUBLE", call("=", B, nul(D), nul(D)), "true"),
                // SELECT 1 < CAST(NULL AS DOUBLE), CAST(NULL AS DOUBLE) > 1, 1 <= CAST(NULL AS LONG),
                // CAST(NULL AS LONG) >= CAST(NULL AS LONG), CAST(NULL AS LONG) <= CAST(NULL AS LONG),
                // CAST(NULL AS VARCHAR) < 'a', CAST(NULL AS VARCHAR) != 'a', CAST(NULL AS VARCHAR) != CAST(NULL AS VARCHAR)
                // -> [false, false, false, true, true, false, true, false]
                row("1 <= NULL LONG", call("<=", B, lit(1), nul(L)), "false"),
                row("NULL LONG >= NULL LONG", call(">=", B, nul(L), nul(L)), "true"),
                row("NULL VARCHAR < 'a'", call("<", B, nul(V), lit("a")), "false"),
                row("NULL VARCHAR != 'a'", call("!=", B, nul(V), lit("a")), "true"),
                row("NULL VARCHAR != NULL VARCHAR", call("!=", B, nul(V), nul(V)), "false"),
                // SELECT 1 = 1.0, 3 > 2.9, '2026-11-14T10:00:00.000000Z'::timestamp = 1794650400000000,
                // CAST(NULL AS LONG) = CAST(NULL AS DOUBLE), 2 != 2.0 -> [true, true, true, true, false]
                row("1 = 1.0", call("=", B, lit(1), lit(1.0)), "true"),
                row("ts = 1794650400000000", call("=", B, ts(T10), lit(1794650400000000L)), "true"),
                row("NULL LONG = NULL DOUBLE", call("=", B, nul(L), nul(D)), "true"),
                row("2 != 2.0", call("!=", B, lit(2), lit(2.0)), "false"),
                // SELECT 'a' = 'a', 'a' < 'b', CAST(NULL AS VARCHAR) = CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR) = 'a',
                // 'b' >= 'a' -> [true, true, true, false, true]
                row("'a' < 'b'", call("<", B, lit("a"), lit("b")), "true"),
                row("NULL VARCHAR = 'a'", call("=", B, nul(V), lit("a")), "false"),
                // SELECT CAST(NULL AS DOUBLE) >= CAST(NULL AS DOUBLE), CAST(NULL AS DOUBLE) <= 1.0 (of a longer query)
                // -> [true, false]
                row("NULL DOUBLE <= 1.0", call("<=", B, nul(D), lit(1.0)), "false"),
                // SELECT 3 IN (1, 2, 3), 4 IN (1, 2, 3), 'x' IN ('x', 'y'), 4 NOT IN (1, 2), CAST(NULL AS LONG) IN (1, 2),
                // 2.0 IN (1, 2) -> [true, false, true, true, false, true]
                row("3 IN (1, 2, 3)", call("in", B, lit(3), lit(1), lit(2), lit(3)), "true"),
                row("4 IN (1, 2, 3)", call("in", B, lit(4), lit(1), lit(2), lit(3)), "false"),
                row("NULL LONG IN (1, 2)", call("in", B, nul(L), lit(1), lit(2)), "false"),
                row("2.0 IN (1, 2)", call("in", B, lit(2.0), lit(1), lit(2)), "true"),
                // SELECT 'a' IN ('a', CAST(NULL AS VARCHAR)), CAST(NULL AS VARCHAR) IN ('a', CAST(NULL AS VARCHAR)),
                // 1 IN (CAST(NULL AS LONG), 1), CAST(NULL AS LONG) NOT IN (1, 2), 'z' NOT IN ('a')
                // -> [true, true, true, true, true]
                row("NULL VARCHAR IN ('a', NULL)", call("in", B, nul(V), lit("a"), nul(V)), "true"),
                row("NULL LONG NOT IN (1, 2)", call("not_in", B, nul(L), lit(1), lit(2)), "true"),
                row("'z' NOT IN ('a')", call("not_in", B, lit("z"), lit("a")), "true"),
                // SELECT 5 BETWEEN 1 AND 10, 0 BETWEEN 1 AND 10, 10 BETWEEN 1 AND 10, 5 BETWEEN 10 AND 1,
                // CAST(NULL AS LONG) BETWEEN 1 AND 10 -> [true, false, true, true, false]
                row("0 BETWEEN 1 AND 10", call("between", B, lit(0), lit(1), lit(10)), "false"),
                row("10 BETWEEN 1 AND 10", call("between", B, lit(10), lit(1), lit(10)), "true"),
                row("5 BETWEEN 10 AND 1", call("between", B, lit(5), lit(10), lit(1)), "true"),
                row("NULL LONG BETWEEN 1 AND 10", call("between", B, nul(L), lit(1), lit(10)), "false"),
                // SELECT true AND false, true OR false, NOT true, NULL AND true, CAST(NULL AS BOOLEAN)
                // -> [false, true, false, false, false]
                row("true AND false", call("and", B, lit(true), lit(false)), "false"),
                row("true OR false", call("or", B, lit(true), lit(false)), "true"),
                row("NOT true", call("not", B, lit(true)), "false"),
                row("NULL AND true", call("and", B, nul(B), lit(true)), "false"),
                // SELECT 'kit_abc' LIKE 'kit_%', 'kitXabc' LIKE 'kit\_%', 'Week 3' LIKE 'Week%', 'week 3' LIKE 'Week%',
                // 'week 3' ILIKE 'WEEK%', 'a.b' LIKE 'a_b', CAST(NULL AS VARCHAR) LIKE 'a%', 'abc' NOT LIKE 'a%'
                // -> [true, false, true, false, true, true, false, false]
                row("'kit_abc' LIKE 'kit_%'", call("like", B, lit("kit_abc"), lit("kit_%")), "true"),
                row("'kitXabc' LIKE 'kit\\_%'", call("like", B, lit("kitXabc"), lit("kit\\_%")), "false"),
                row("'week 3' LIKE 'Week%'", call("like", B, lit("week 3"), lit("Week%")), "false"),
                row("'week 3' ILIKE 'WEEK%'", call("ilike", B, lit("week 3"), lit("WEEK%")), "true"),
                row("'a.b' LIKE 'a_b'", call("like", B, lit("a.b"), lit("a_b")), "true"),
                row("NULL VARCHAR LIKE 'a%'", call("like", B, nul(V), lit("a%")), "false"),
                row("'abc' NOT LIKE 'a%'", call("not", B, call("like", B, lit("abc"), lit("a%"))), "false"),
                // SELECT 'Week 3' ILIKE 'week_3', 'abc' LIKE 'ABC', 'abc' LIKE '%', '' LIKE '%', 'a%b' LIKE 'a\%b',
                // 'axb' LIKE 'a\%b', 'a.c' LIKE 'a.c', 'abc' LIKE 'a.c', 'a[b]c' LIKE 'a[b]c', 'abc' LIKE 'b'
                // -> [true, false, true, true, true, false, true, false, true, false]
                row("'Week 3' ILIKE 'week_3'", call("ilike", B, lit("Week 3"), lit("week_3")), "true"),
                row("'' LIKE '%'", call("like", B, lit(""), lit("%")), "true"),
                row("'a%b' LIKE 'a\\%b'", call("like", B, lit("a%b"), lit("a\\%b")), "true"),
                row("'axb' LIKE 'a\\%b'", call("like", B, lit("axb"), lit("a\\%b")), "false"),
                row("'abc' LIKE 'a.c'", call("like", B, lit("abc"), lit("a.c")), "false"),
                row("'a[b]c' LIKE 'a[b]c'", call("like", B, lit("a[b]c"), lit("a[b]c")), "true"),
                row("'abc' LIKE 'b'", call("like", B, lit("abc"), lit("b")), "false"),
                // SELECT '/x/runs-2026-11-03' LIKE '%runs-2026-11%', 'sys.x' LIKE 'sys.%', 'sysXx' LIKE 'sys.%',
                // 'ab' LIKE 'a', 'ab' LIKE '_', 'ab' LIKE '__', 'a\b' LIKE 'a\\b', 'a+b' LIKE 'a+b', 'a(b' LIKE 'a(b'
                // -> [true, true, false, false, false, true, true, true, true]
                row("'/x/runs-2026-11-03' LIKE '%runs-2026-11%'",
                        call("like", B, lit("/x/runs-2026-11-03"), lit("%runs-2026-11%")), "true"),
                row("'sysXx' LIKE 'sys.%'", call("like", B, lit("sysXx"), lit("sys.%")), "false"),
                row("'ab' LIKE '_'", call("like", B, lit("ab"), lit("_")), "false"),
                row("'ab' LIKE '__'", call("like", B, lit("ab"), lit("__")), "true"),
                row("'a\\b' LIKE 'a\\\\b'", call("like", B, lit("a\\b"), lit("a\\\\b")), "true"),
                // SELECT CASE WHEN 1 > 2 THEN 'a' WHEN 2 > 1 THEN 'b' ELSE 'c' END, CASE WHEN 1 > 2 THEN 'a' END,
                // CASE WHEN 1 > 2 THEN 1 ELSE 2 END -> ["b", "", 2]
                row("CASE WHEN 1 > 2 THEN 'a' WHEN 2 > 1 THEN 'b' ELSE 'c' END", call("case", V,
                        call(">", B, lit(1), lit(2)), lit("a"), call(">", B, lit(2), lit(1)), lit("b"), lit("c")), "b"),
                row("CASE WHEN 1 > 2 THEN 1 ELSE 2 END", call("case", L, call(">", B, lit(1), lit(2)), lit(1), lit(2)), "2"),
                // SELECT CASE WHEN CAST(NULL AS BOOLEAN) THEN 1 ELSE 0 END, CASE WHEN 1 = 1 THEN CAST(NULL AS DOUBLE)
                // ELSE 1.5 END, CASE WHEN 2 > 3 THEN 1.5 END, CASE WHEN 2 > 1 THEN 1 ELSE 2.5 END -> [0, null, null, 1.0]
                row("CASE WHEN NULL BOOLEAN THEN 1 ELSE 0 END", call("case", L, nul(B), lit(1), lit(0)), "0"),
                row("CASE WHEN 2 > 3 THEN 1.5 END", call("case", D, call(">", B, lit(2), lit(3)), lit(1.5)), "null"),
                row("CASE WHEN 2 > 1 THEN 1 ELSE 2.5 END",
                        call("case", D, call(">", B, lit(2), lit(1)), lit(1), lit(2.5)), "1.0"),
                // SELECT CAST(2.7 AS LONG), CAST(-2.7 AS LONG), CAST(3 AS DOUBLE), CAST(1 AS BOOLEAN), CAST(0 AS BOOLEAN),
                // CAST('12' AS LONG), CAST('2.5' AS DOUBLE) -> [2, -2, 3.0, true, false, 12, 2.5]
                row("CAST(2.7 AS LONG)", call("cast", L, lit(2.7)), "2"),
                row("CAST(-2.7 AS LONG)", call("cast", L, lit(-2.7)), "-2"),
                row("CAST(3 AS DOUBLE)", call("cast", D, lit(3)), "3.0"),
                row("CAST(0 AS BOOLEAN)", call("cast", B, lit(0)), "false"),
                row("CAST('12' AS LONG)", call("cast", L, lit("12")), "12"),
                // SELECT CAST(CAST(NULL AS TIMESTAMP) AS VARCHAR), CAST(CAST(NULL AS BOOLEAN) AS VARCHAR), CAST(12.0 AS LONG),
                // CAST(1e19 AS LONG), CAST('9223372036854775807' AS LONG), CAST('-5' AS LONG), CAST('+5' AS LONG),
                // CAST('5.0' AS LONG) -> ["null", "false", 12, null, 9223372036854775807, -5, null, null]
                row("CAST(1e19 AS LONG)", call("cast", L, lit(1e19)), "null"),
                row("CAST('9223372036854775807' AS LONG)", call("cast", L, lit("9223372036854775807")),
                        "9223372036854775807"),
                row("CAST('-5' AS LONG)", call("cast", L, lit("-5")), "-5"),
                row("CAST('+5' AS LONG)", call("cast", L, lit("+5")), "null"),
                row("CAST('5.0' AS LONG)", call("cast", L, lit("5.0")), "null"),
                // SELECT CAST(true AS LONG), CAST(false AS DOUBLE), CAST(2.5 AS BOOLEAN), CAST('true' AS BOOLEAN),
                // CAST('yes' AS BOOLEAN), CAST('x' AS SYMBOL), CAST(CAST(NULL AS LONG) AS BOOLEAN)
                // -> [1, 0.0, true, true, false, "x", false]
                row("CAST(true AS LONG)", call("cast", L, lit(true)), "1"),
                row("CAST(2.5 AS BOOLEAN)", call("cast", B, lit(2.5)), "true"),
                row("CAST('yes' AS BOOLEAN)", call("cast", B, lit("yes")), "false"),
                row("CAST('x' AS SYMBOL)", call("cast", CdbType.SYMBOL, lit("x")), "x"),
                row("CAST(NULL LONG AS BOOLEAN)", call("cast", B, nul(L)), "false"),
                // SELECT CAST('TRUE' AS BOOLEAN), CAST('True' AS BOOLEAN), CAST(CAST(NULL AS DOUBLE) AS BOOLEAN), ...
                // -> [true, true, false, ...]
                row("CAST('True' AS BOOLEAN)", call("cast", B, lit("True")), "true"),
                row("CAST(NULL DOUBLE AS BOOLEAN)", call("cast", B, nul(D)), "false"),
                // SELECT CAST('abc' AS LONG) -> [null]; SELECT CAST('abc' AS DOUBLE) -> [null]
                row("CAST('abc' AS LONG)", call("cast", L, lit("abc")), "null"),
                row("CAST('abc' AS DOUBLE)", call("cast", D, lit("abc")), "null"),
                // SELECT CAST(2.5 AS TIMESTAMP), CAST(CAST(NULL AS LONG) AS TIMESTAMP), CAST(CAST(NULL AS VARCHAR) AS
                // TIMESTAMP), CAST('1.5e3' AS DOUBLE), CAST(' 12' AS LONG)
                // -> ["1970-01-01T00:00:00.000002Z", null, null, 1500.0, null]
                row("CAST(2.5 AS TIMESTAMP)", call("cast", T, lit(2.5)), "1970-01-01T00:00:00.000002Z"),
                row("CAST(NULL VARCHAR AS TIMESTAMP)", call("cast", T, nul(V)), "null"),
                row("CAST('1.5e3' AS DOUBLE)", call("cast", D, lit("1.5e3")), "1500.0"),
                row("CAST(' 12' AS LONG)", call("cast", L, lit(" 12")), "null"),
                // SELECT CAST(1.0 AS VARCHAR), CAST(2.5 AS VARCHAR), CAST(1e20 AS VARCHAR), CAST(0.1 AS VARCHAR),
                // CAST(123456789.125 AS VARCHAR), CAST(1e-5 AS VARCHAR), CAST(true AS VARCHAR), CAST(42 AS VARCHAR)
                // -> ["1.0", "2.5", "1.0E20", "0.1", "1.23456789125E8", "1.0E-5", "true", "42"]
                row("CAST(1e20 AS VARCHAR)", call("cast", V, lit(1e20)), "1.0E20"),
                row("CAST(123456789.125 AS VARCHAR)", call("cast", V, lit(123456789.125)), "1.23456789125E8"),
                row("CAST(1e-5 AS VARCHAR)", call("cast", V, lit(1e-5)), "1.0E-5"),
                row("CAST(true AS VARCHAR)", call("cast", V, lit(true)), "true"),
                row("CAST(42 AS VARCHAR)", call("cast", V, lit(42)), "42"),
                // SELECT abs(-9223372036854775807), CAST(-0.0 AS VARCHAR), 0.1 + 0.2, CAST(100.0 AS VARCHAR),
                // CAST(1e7 AS VARCHAR), CAST(1234567.0 AS VARCHAR), CAST(0.001 AS VARCHAR), CAST(1e-3 * 1 AS VARCHAR)
                // -> [9223372036854775807, "-0.0", 0.30000000000000004, "100.0", "1.0E7", "1234567.0", "0.001", "0.001"]
                row("abs(-9223372036854775807)", call("abs", L, lit(-9223372036854775807L)), "9223372036854775807"),
                row("CAST(-0.0 AS VARCHAR)", call("cast", V, lit(-0.0)), "-0.0"),
                row("0.1 + 0.2", call("+", D, lit(0.1), lit(0.2)), "0.30000000000000004"),
                row("CAST(1e7 AS VARCHAR)", call("cast", V, lit(1e7)), "1.0E7"),
                row("CAST(0.001 AS VARCHAR)", call("cast", V, lit(0.001)), "0.001"),
                // SELECT CAST(ts AS VARCHAR), CAST(ts AS STRING), CAST(ts AS LONG), CAST(ts AS DOUBLE), concat('x', ts),
                // 'x' || ts FROM comando_usage LIMIT 1 -> ["2026-10-28T11:13:15.876000Z", ..., 1793185995876000,
                // 1793185995876000.0, "x2026-10-28T11:13:15.876000Z", "x2026-10-28T11:13:15.876000Z"]
                row("CAST(ts AS VARCHAR)", call("cast", V, ts("2026-10-28T11:13:15.876000Z")),
                        "2026-10-28T11:13:15.876000Z"),
                row("CAST(ts AS LONG)", call("cast", L, ts("2026-10-28T11:13:15.876000Z")), "1793185995876000"),
                row("CAST(ts AS DOUBLE)", call("cast", D, ts("2026-10-28T11:13:15.876000Z")), "1.793185995876E15"),
                row("'x' || ts", call("||", V, lit("x"), ts("2026-10-28T11:13:15.876000Z")),
                        "x2026-10-28T11:13:15.876000Z"),
                // SELECT CAST('2026-11-14T10:49:45.097165Z' AS TIMESTAMP), ..., CAST(1763117385097165 AS TIMESTAMP)
                // -> ["2026-11-14T10:49:45.097165Z", ..., "2025-11-14T10:49:45.097165Z"]
                row("CAST('2026-11-14T10:49:45.097165Z' AS TIMESTAMP)", call("cast", T, lit(NOW)), NOW),
                row("CAST(1763117385097165 AS TIMESTAMP)", call("cast", T, lit(1763117385097165L)),
                        "2025-11-14T10:49:45.097165Z"),
                // SELECT CAST('2026-11-14' AS TIMESTAMP), CAST('2026-11-14T10:49' AS TIMESTAMP), CAST('2026-11-14
                // 10:49:45' AS TIMESTAMP), CAST('2026-11-14T10:49:45.1Z' AS TIMESTAMP), CAST('junk' AS TIMESTAMP)
                // -> ["2026-11-14T00:00:00.000000Z", "2026-11-14T10:49:00.000000Z", "2026-11-14T10:49:45.000000Z",
                // "2026-11-14T10:49:45.100000Z", null]
                row("CAST('2026-11-14' AS TIMESTAMP)", call("cast", T, lit("2026-11-14")), "2026-11-14T00:00:00.000000Z"),
                row("CAST('2026-11-14 10:49:45' AS TIMESTAMP)", call("cast", T, lit("2026-11-14 10:49:45")),
                        "2026-11-14T10:49:45.000000Z"),
                row("CAST('2026-11-14T10:49:45.1Z' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:49:45.1Z")),
                        "2026-11-14T10:49:45.100000Z"),
                row("CAST('junk' AS TIMESTAMP)", call("cast", T, lit("junk")), "null"),
                // SELECT CAST('2026-11-14T10:49:45.097165' AS TIMESTAMP), CAST('2026-11-14T10:49:45.097165+01:00' AS
                // TIMESTAMP), CAST('2026-11' AS TIMESTAMP), CAST('2026' AS TIMESTAMP), CAST('2026-11-14T10' AS TIMESTAMP),
                // CAST('2026-11-14T10:49:45.097165123Z' AS TIMESTAMP), CAST('2026-11-14T10:49:45Z' AS TIMESTAMP)
                // -> ["2026-11-14T10:49:45.097165Z", "2026-11-14T09:49:45.097165Z", "2026-11-01T00:00:00.000000Z",
                // "2026-01-01T00:00:00.000000Z", "2026-11-14T10:00:00.000000Z", "2026-11-14T10:49:45.097165Z",
                // "2026-11-14T10:49:45.000000Z"]
                row("CAST('...097165+01:00' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:49:45.097165+01:00")),
                        "2026-11-14T09:49:45.097165Z"),
                row("CAST('2026-11' AS TIMESTAMP)", call("cast", T, lit("2026-11")), "2026-11-01T00:00:00.000000Z"),
                row("CAST('2026' AS TIMESTAMP)", call("cast", T, lit("2026")), "2026-01-01T00:00:00.000000Z"),
                row("CAST('2026-11-14T10' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10")),
                        "2026-11-14T10:00:00.000000Z"),
                row("CAST('...097165123Z' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:49:45.097165123Z")), NOW),
                // SELECT CAST('2026-11-14T10:00:00+0100' AS TIMESTAMP), CAST('2026-11-14T10:00:00+01' AS TIMESTAMP),
                // CAST('2026-11-14T10:00:00-0530' AS TIMESTAMP), CAST('2026-11-14T10:00:00+0130' AS TIMESTAMP),
                // CAST('2026-11-14T10:00:00.5-03:00' AS TIMESTAMP), CAST('2026-11-14T10:00:00+2359' AS TIMESTAMP),
                // CAST('2026-11-14T10:00:00.' AS TIMESTAMP), CAST('2026-11-14T10:00:00.5Z' AS TIMESTAMP)
                // -> ["2026-11-14T09:00:00.000000Z", "2026-11-14T09:00:00.000000Z", "2026-11-14T15:30:00.000000Z",
                // "2026-11-14T08:30:00.000000Z", "2026-11-14T13:00:00.500000Z", "2026-11-13T10:01:00.000000Z",
                // "2026-11-14T10:00:00.000000Z", "2026-11-14T10:00:00.500000Z"]
                row("CAST('...10:00:00+0100' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00+0100")),
                        "2026-11-14T09:00:00.000000Z"),
                row("CAST('...10:00:00+01' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00+01")),
                        "2026-11-14T09:00:00.000000Z"),
                row("CAST('...10:00:00-0530' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00-0530")),
                        "2026-11-14T15:30:00.000000Z"),
                row("CAST('...10:00:00+0130' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00+0130")),
                        "2026-11-14T08:30:00.000000Z"),
                row("CAST('...10:00:00.5-03:00' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00.5-03:00")),
                        "2026-11-14T13:00:00.500000Z"),
                row("CAST('...10:00:00+2359' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00+2359")),
                        "2026-11-13T10:01:00.000000Z"),
                row("CAST('...10:00:00.' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00.")),
                        "2026-11-14T10:00:00.000000Z"),
                row("CAST('...10:00:00.5Z' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00.5Z")),
                        "2026-11-14T10:00:00.500000Z"),
                // SELECT CAST('2026-11-14T10:00+01:00' AS TIMESTAMP), CAST('2026-11-14T10Z' AS TIMESTAMP),
                // CAST('2026-11-14+01:00' AS TIMESTAMP), CAST('2026-11-14T10:00:00+24' AS TIMESTAMP)
                // -> [null, null, null, null]: a zone is read only after the seconds field, and +24 is no zone
                row("CAST('...T10:00+01:00' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00+01:00")), "null"),
                row("CAST('...T10Z' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10Z")), "null"),
                row("CAST('2026-11-14+01:00' AS TIMESTAMP)", call("cast", T, lit("2026-11-14+01:00")), "null"),
                row("CAST('...10:00:00+24' AS TIMESTAMP)", call("cast", T, lit("2026-11-14T10:00:00+24")), "null"),
                // with ts = '2026-11-14T10:00:00.000000Z'::timestamp: SELECT ts = '2026-11-14T11:00:00+01:00',
                // ts = '2026-11-14T11:00:00+0100', ts > '2026-11-14T10:30:00-0100', '2026-11-14T11:00:00+01' = ts,
                // ts != '2026-11-14T11:00:00+01:00', ts = CAST(NULL AS VARCHAR) -> [true, true, false, true, false, false]
                row("ts = '...11:00:00+01:00'", call("=", B, ts(T10), lit("2026-11-14T11:00:00+01:00")), "true"),
                row("ts = '...11:00:00+0100'", call("=", B, ts(T10), lit("2026-11-14T11:00:00+0100")), "true"),
                row("ts > '...10:30:00-0100'", call(">", B, ts(T10), lit("2026-11-14T10:30:00-0100")), "false"),
                row("'...11:00:00+01' = ts", call("=", B, lit("2026-11-14T11:00:00+01"), ts(T10)), "true"),
                row("ts != '...11:00:00+01:00'", call("!=", B, ts(T10), lit("2026-11-14T11:00:00+01:00")), "false"),
                row("ts = NULL VARCHAR", call("=", B, ts(T10), nul(V)), "false"),
                // SELECT 'a' || 'b', 'a' || CAST(NULL AS VARCHAR), concat('a', ' ', 'b'), concat('a', CAST(NULL AS
                // VARCHAR), 'b'), 'n' || 5, concat('x', 1.5) -> ["ab", "a", "a b", "ab", "n5", "x1.5"]
                row("'a' || NULL VARCHAR", call("||", V, lit("a"), nul(V)), "a"),
                row("concat('a', NULL VARCHAR, 'b')", call("concat", V, lit("a"), nul(V), lit("b")), "ab"),
                row("'n' || 5", call("||", V, lit("n"), lit(5)), "n5"),
                row("concat('x', 1.5)", call("concat", V, lit("x"), lit(1.5)), "x1.5"),
                // SELECT concat('a', 1, true), concat(CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR)), ...
                // -> ["a1true", "", ...]
                row("concat('a', 1, true)", call("concat", V, lit("a"), lit(1), lit(true)), "a1true"),
                row("concat(NULL VARCHAR, NULL VARCHAR)", call("concat", V, nul(V), nul(V)), ""),
                // SELECT dateadd('d', -7, ts), dateadd('h', 3, ts), dateadd('m', -90, ts), dateadd('M', 1,
                // '2026-01-31T10:00:00.000000Z'::timestamp), dateadd('y', 1, '2024-02-29T00:00:00.000000Z'::timestamp)
                // with ts = '2026-11-14T10:00:00.000000Z'::timestamp -> ["2026-11-07T10:00:00.000000Z",
                // "2026-11-14T13:00:00.000000Z", "2026-11-14T08:30:00.000000Z", "2026-02-28T10:00:00.000000Z",
                // "2025-02-28T00:00:00.000000Z"]
                row("dateadd('d', -7, ts)", call("dateadd", T, lit("d"), lit(-7), ts(T10)), "2026-11-07T10:00:00.000000Z"),
                row("dateadd('m', -90, ts)", call("dateadd", T, lit("m"), lit(-90), ts(T10)),
                        "2026-11-14T08:30:00.000000Z"),
                row("dateadd('M', 1, 01-31)", call("dateadd", T, lit("M"), lit(1), ts("2026-01-31T10:00:00.000000Z")),
                        "2026-02-28T10:00:00.000000Z"),
                row("dateadd('y', 1, 2024-02-29)", call("dateadd", T, lit("y"), lit(1), ts("2024-02-29T00:00:00.000000Z")),
                        "2025-02-28T00:00:00.000000Z"),
                // SELECT dateadd('d', -7, CAST(NULL AS TIMESTAMP)), dateadd('s', 30, ts), dateadd('w', 1, ts),
                // dateadd('T', 5, ts), dateadd('u', 5, ts), dateadd('M', -1, '2026-03-31T10:00:00.000000Z'::timestamp)
                // -> [null, "2026-11-14T10:00:30.000000Z", "2026-11-21T10:00:00.000000Z", "2026-11-14T10:00:00.005000Z",
                // "2026-11-14T10:00:00.000005Z", "2026-02-28T10:00:00.000000Z"]
                row("dateadd('d', -7, NULL)", call("dateadd", T, lit("d"), lit(-7), nul(T)), "null"),
                row("dateadd('w', 1, ts)", call("dateadd", T, lit("w"), lit(1), ts(T10)), "2026-11-21T10:00:00.000000Z"),
                row("dateadd('T', 5, ts)", call("dateadd", T, lit("T"), lit(5), ts(T10)), "2026-11-14T10:00:00.005000Z"),
                row("dateadd('M', -1, 03-31)", call("dateadd", T, lit("M"), lit(-1), ts("2026-03-31T10:00:00.000000Z")),
                        "2026-02-28T10:00:00.000000Z"),
                // SELECT datediff('s', ts, ts + 90.5 s), datediff('s', ts + 90.5 s, ts), datediff('m', 10:00, 10:59:59),
                // datediff('d', 11-01, 11-14T10), datediff('h', 10:00, 13:30) -> [90, 90, 59, 13, 3]
                row("datediff('s', a, b)", call("datediff", L, lit("s"), ts(T10), ts("2026-11-14T10:01:30.500000Z")), "90"),
                row("datediff('s', b, a)", call("datediff", L, lit("s"), ts("2026-11-14T10:01:30.500000Z"), ts(T10)), "90"),
                row("datediff('d', 11-01, 11-14T10)",
                        call("datediff", L, lit("d"), ts("2026-11-01T00:00:00.000000Z"), ts(T10)), "13"),
                // SELECT datediff('M', 2026-01-31, 2026-03-01), datediff('y', 2024-02-29, 2026-02-28),
                // datediff('w', 2026-11-01, 2026-11-14) -> [1, 1, 1]
                row("datediff('M', 01-31, 03-01)", call("datediff", L, lit("M"), ts("2026-01-31T00:00:00.000000Z"),
                        ts("2026-03-01T00:00:00.000000Z")), "1"),
                row("datediff('y', 2024-02-29, 2026-02-28)", call("datediff", L, lit("y"),
                        ts("2024-02-29T00:00:00.000000Z"), ts("2026-02-28T00:00:00.000000Z")), "1"),
                // SELECT datediff('d', CAST(NULL AS TIMESTAMP), ts), datediff('s', 10:00, 10:00:00.999999),
                // datediff('M', 01-15, 02-14T23:59:59), datediff('M', 03-01, 01-31), datediff('T', 10:00, 10:00:01.5),
                // datediff('u', 10:00, 10:00:01.5) -> [null, 0, 0, 1, 1500, 1500000]
                row("datediff('d', NULL, ts)", call("datediff", L, lit("d"), nul(T), ts(T10)), "null"),
                row("datediff('M', 01-15, 02-14T23:59:59)", call("datediff", L, lit("M"),
                        ts("2026-01-15T00:00:00.000000Z"), ts("2026-02-14T23:59:59.000000Z")), "0"),
                row("datediff('M', 03-01, 01-31)", call("datediff", L, lit("M"), ts("2026-03-01T00:00:00.000000Z"),
                        ts("2026-01-31T00:00:00.000000Z")), "1"),
                row("datediff('T', 10:00, 10:00:01.5)",
                        call("datediff", L, lit("T"), ts(T10), ts("2026-11-14T10:00:01.500000Z")), "1500"),
                // SELECT timestamp_floor('d', now), timestamp_floor('h', now), timestamp_floor('m', now),
                // timestamp_floor('M', now), timestamp_floor('y', now), timestamp_floor('w', now),
                // timestamp_floor('15m', now) with now = '2026-11-14T10:49:45.097165Z'::timestamp
                // -> [11-14T00:00, 11-14T10:00, 11-14T10:49, 11-01, 2026-01-01, 11-09, 11-14T10:45]
                row("timestamp_floor('d', now)", call("timestamp_floor", T, lit("d"), ts(NOW)),
                        "2026-11-14T00:00:00.000000Z"),
                row("timestamp_floor('M', now)", call("timestamp_floor", T, lit("M"), ts(NOW)),
                        "2026-11-01T00:00:00.000000Z"),
                row("timestamp_floor('y', now)", call("timestamp_floor", T, lit("y"), ts(NOW)),
                        "2026-01-01T00:00:00.000000Z"),
                row("timestamp_floor('w', now)", call("timestamp_floor", T, lit("w"), ts(NOW)),
                        "2026-11-09T00:00:00.000000Z"),
                row("timestamp_floor('15m', now)", call("timestamp_floor", T, lit("15m"), ts(NOW)),
                        "2026-11-14T10:45:00.000000Z"),
                // SELECT timestamp_floor('d', CAST(NULL AS TIMESTAMP)), timestamp_floor('s', now), timestamp_floor('T',
                // now), timestamp_floor('2h', 11:49:45), timestamp_floor('3d', now), timestamp_floor('d', 1969-12-31T10)
                // -> [null, 10:49:45.000000, 10:49:45.097000, 10:00, 11-12, 1969-12-31T00:00]
                row("timestamp_floor('d', NULL)", call("timestamp_floor", T, lit("d"), nul(T)), "null"),
                row("timestamp_floor('T', now)", call("timestamp_floor", T, lit("T"), ts(NOW)),
                        "2026-11-14T10:49:45.097000Z"),
                row("timestamp_floor('2h', 11:49:45)", call("timestamp_floor", T, lit("2h"),
                        ts("2026-11-14T11:49:45.097165Z")), "2026-11-14T10:00:00.000000Z"),
                row("timestamp_floor('3d', now)", call("timestamp_floor", T, lit("3d"), ts(NOW)),
                        "2026-11-12T00:00:00.000000Z"),
                row("timestamp_floor('d', 1969-12-31T10)", call("timestamp_floor", T, lit("d"),
                        ts("1969-12-31T10:00:00.000000Z")), "1969-12-31T00:00:00.000000Z"),
                // SELECT date_trunc('hour', now), date_trunc('day', now), date_trunc('month', now), date_trunc('week',
                // now), date_trunc('minute', now), date_trunc('year', now)
                // -> [10:00, 11-14, 11-01, 11-09, 10:49, 2026-01-01]
                row("date_trunc('hour', now)", call("date_trunc", T, lit("hour"), ts(NOW)), "2026-11-14T10:00:00.000000Z"),
                row("date_trunc('week', now)", call("date_trunc", T, lit("week"), ts(NOW)), "2026-11-09T00:00:00.000000Z"),
                // SELECT date_trunc('second', now), date_trunc('millisecond', now), date_trunc('quarter', now),
                // date_trunc('day', CAST(NULL AS TIMESTAMP)) -> [10:49:45.000000, 10:49:45.097000, 10-01, null]
                row("date_trunc('millisecond', now)", call("date_trunc", T, lit("millisecond"), ts(NOW)),
                        "2026-11-14T10:49:45.097000Z"),
                row("date_trunc('quarter', now)", call("date_trunc", T, lit("quarter"), ts(NOW)),
                        "2026-10-01T00:00:00.000000Z"),
                row("date_trunc('day', NULL)", call("date_trunc", T, lit("day"), nul(T)), "null"),
                // SELECT to_str(now, 'yyyy-MM-dd'), to_str(now, 'yyyy-MM-ddTHH:mm'), to_str(now, 'yyyy-MM-dd HH:mm:ss'),
                // to_str(now, 'dd/MM/yy'), to_str(CAST(NULL AS TIMESTAMP), 'yyyy-MM-dd')
                // -> ["2026-11-14", "2026-11-14T10:49", "2026-11-14 10:49:45", "14/11/26", null]
                row("to_str(now, 'yyyy-MM-ddTHH:mm')", call("to_str", V, ts(NOW), lit("yyyy-MM-ddTHH:mm")),
                        "2026-11-14T10:49"),
                row("to_str(now, 'yyyy-MM-dd HH:mm:ss')", call("to_str", V, ts(NOW), lit("yyyy-MM-dd HH:mm:ss")),
                        "2026-11-14 10:49:45"),
                row("to_str(now, 'dd/MM/yy')", call("to_str", V, ts(NOW), lit("dd/MM/yy")), "14/11/26"),
                row("to_str(NULL, 'yyyy-MM-dd')", call("to_str", V, nul(T), lit("yyyy-MM-dd")), "null"),
                // SELECT to_str(m, 'yyyy-M-d H:m:s.SSS UUU'), to_str(m + 10 h, 'yy MMM MMMM E EE hh a'),
                // to_str(m, 'HH:mm:ss.SSSUUU'), to_str(m, 'dd.MM.yyyy') with m = '2026-03-04T05:06:07.089012Z'::timestamp
                // -> ["2026-3-4 5:6:7.089 012", "26 Mar March Wed Wednesday 03 PM", "05:06:07.089012", "04.03.2026"]
                row("to_str(m, 'yyyy-M-d H:m:s.SSS UUU')", call("to_str", V, ts(MARCH), lit("yyyy-M-d H:m:s.SSS UUU")),
                        "2026-3-4 5:6:7.089 012"),
                row("to_str(m + 10 h, 'yy MMM MMMM E EE hh a')", call("to_str", V, ts("2026-03-04T15:06:07.089012Z"),
                        lit("yy MMM MMMM E EE hh a")), "26 Mar March Wed Wednesday 03 PM"),
                row("to_str(m, 'HH:mm:ss.SSSUUU')", call("to_str", V, ts(MARCH), lit("HH:mm:ss.SSSUUU")),
                        "05:06:07.089012"),
                // SELECT to_str(m, 'S SS SSSS U UU'), to_str(m, 'y yyy'), to_str(00:06, 'h hh K a'),
                // to_str(12:06, 'h hh a'), to_str(m, 'M MM MMM d dd E')
                // -> ["89 8989 08989 12 1212", "2026 026", "12 12 0 AM", "12 12 PM", "3 03 Mar 4 04 Wed"]
                row("to_str(m, 'S SS SSSS U UU')", call("to_str", V, ts(MARCH), lit("S SS SSSS U UU")),
                        "89 8989 08989 12 1212"),
                row("to_str(m, 'y yyy')", call("to_str", V, ts(MARCH), lit("y yyy")), "2026 026"),
                row("to_str(00:06, 'h hh K a')", call("to_str", V, ts("2026-03-04T00:06:07.089012Z"), lit("h hh K a")),
                        "12 12 0 AM"),
                row("to_str(12:06, 'h hh a')", call("to_str", V, ts("2026-03-04T12:06:07.089012Z"), lit("h hh a")),
                        "12 12 PM"),
                row("to_str(m, 'M MM MMM d dd E')", call("to_str", V, ts(MARCH), lit("M MM MMM d dd E")),
                        "3 03 Mar 4 04 Wed"),
                // SELECT hour(now), hour(CAST(NULL AS TIMESTAMP)), abs(CAST(NULL AS DOUBLE)), length('héllo'),
                // upper('straße'), lower(CAST('ÀB' AS VARCHAR)) -> [10, null, null, 5, "STRAßE", "àb"]
                row("hour(now)", call("hour", L, ts(NOW)), "10"),
                row("hour(NULL)", call("hour", L, nul(T)), "null"),
                row("abs(NULL DOUBLE)", call("abs", D, nul(D)), "null"),
                row("length('héllo')", call("length", L, lit("héllo")), "5"),
                row("upper('straße')", call("upper", V, lit("straße")), "STRAßE"),
                row("lower('ÀB')", call("lower", V, lit("ÀB")), "àb"),
                // SELECT round(2.345, 2), round(2.5), round(-2.5), round(2.4), round(1.005, 2), round(-1.555, 2),
                // round(1234.5678, -2), round(CAST(NULL AS DOUBLE), 2), round(7, 1)
                // -> [2.35, 3.0, -2.0, 2.0, 1.01, -1.56, 1200.0, null, 7.0]
                row("round(2.345, 2)", call("round", D, lit(2.345), lit(2)), "2.35"),
                row("round(-2.5)", call("round", D, lit(-2.5)), "-2.0"),
                row("round(1.005, 2)", call("round", D, lit(1.005), lit(2)), "1.01"),
                row("round(-1.555, 2)", call("round", D, lit(-1.555), lit(2)), "-1.56"),
                row("round(1234.5678, -2)", call("round", D, lit(1234.5678), lit(-2)), "1200.0"),
                row("round(NULL DOUBLE, 2)", call("round", D, nul(D), lit(2)), "null"),
                row("round(7, 1)", call("round", D, lit(7), lit(1)), "7.0"),
                // SELECT round(2.5, 0), round(0.125, 2), round(-0.5), round(CAST(NULL AS LONG), 1), round(1e300, 2),
                // round(123.456, 20), round(5), round(-1.0), round(1.4999999999999999)
                // -> [3.0, 0.13, 0.0, null, 9.223372036854776e+16, null, 5.0, -1.0, 2.0]
                row("round(2.5, 0)", call("round", D, lit(2.5), lit(0)), "3.0"),
                row("round(0.125, 2)", call("round", D, lit(0.125), lit(2)), "0.13"),
                row("round(-0.5)", call("round", D, lit(-0.5)), "0.0"),
                row("round(1e300, 2)", call("round", D, lit(1e300), lit(2)), "9.223372036854776E16"),
                row("round(123.456, 20)", call("round", D, lit(123.456), lit(20)), "null"),
                row("round(5)", call("round", D, lit(5)), "5.0"),
                // SELECT abs(-3), abs(-2.5), abs(CAST(NULL AS LONG)), coalesce(CAST(NULL AS LONG), 5),
                // coalesce(CAST(NULL AS VARCHAR), 'x', 'y'), coalesce(CAST(NULL AS DOUBLE), CAST(NULL AS DOUBLE))
                // -> [3, 2.5, null, 5, "x", null]
                row("abs(-3)", call("abs", L, lit(-3)), "3"),
                row("abs(-2.5)", call("abs", D, lit(-2.5)), "2.5"),
                row("abs(NULL LONG)", call("abs", L, nul(L)), "null"),
                row("coalesce(NULL VARCHAR, 'x', 'y')", call("coalesce", V, nul(V), lit("x"), lit("y")), "x"),
                row("coalesce(NULL DOUBLE, NULL DOUBLE)", call("coalesce", D, nul(D), nul(D)), "null"),
                // SELECT coalesce(CAST(NULL AS LONG), 2.5), coalesce(1, 2), coalesce(CAST(NULL AS TIMESTAMP), ts),
                // coalesce(CAST(NULL AS VARCHAR), CAST(NULL AS VARCHAR)) -> [2.5, 1, "2026-11-14T10:00:00.000000Z", null]
                row("coalesce(NULL LONG, 2.5)", call("coalesce", D, nul(L), lit(2.5)), "2.5"),
                row("coalesce(NULL TIMESTAMP, ts)", call("coalesce", T, nul(T), ts(T10)), T10),
                // SELECT substring('hello world', 1, 5), substring('hello', 2, 100), substring('hello', 0, 3),
                // substring(CAST(NULL AS VARCHAR), 1, 2), replace('/a/b/c', '/a/', ''), replace('aaa', 'a', 'bb')
                // -> ["hello", "ello", "he", null, "b/c", "bbbbbb"]
                row("substring('hello world', 1, 5)", call("substring", V, lit("hello world"), lit(1), lit(5)), "hello"),
                row("substring('hello', 2, 100)", call("substring", V, lit("hello"), lit(2), lit(100)), "ello"),
                row("substring('hello', 0, 3)", call("substring", V, lit("hello"), lit(0), lit(3)), "he"),
                row("substring(NULL, 1, 2)", call("substring", V, nul(V), lit(1), lit(2)), "null"),
                row("replace('/a/b/c', '/a/', '')", call("replace", V, lit("/a/b/c"), lit("/a/"), lit("")), "b/c"),
                row("replace('aaa', 'a', 'bb')", call("replace", V, lit("aaa"), lit("a"), lit("bb")), "bbbbbb"),
                // SELECT substring('hello', -1, 3), substring('hello', 2, 0), substring('hello', 6, 2),
                // replace(CAST(NULL AS VARCHAR), 'a', 'b'), replace('abc', NULL, 'x'), replace('abc', 'b', NULL),
                // replace('abc', '', 'x') -> ["h", "", "", null, null, null, "abc"]
                row("substring('hello', -1, 3)", call("substring", V, lit("hello"), lit(-1), lit(3)), "h"),
                row("substring('hello', 6, 2)", call("substring", V, lit("hello"), lit(6), lit(2)), ""),
                row("replace('abc', NULL, 'x')", call("replace", V, lit("abc"), nul(V), lit("x")), "null"),
                row("replace('abc', '', 'x')", call("replace", V, lit("abc"), lit(""), lit("x")), "abc"),
                // SELECT substring('hello', 1, CAST(NULL AS INT)), substring('hello', CAST(NULL AS INT), 2) -> [null, null]
                row("substring('hello', 1, NULL)", call("substring", V, lit("hello"), lit(1), nul(L)), "null"),
                // SELECT length('hello'), length(''), length(CAST(NULL AS VARCHAR)), lower('HeLLo'), upper('HeLLo'),
                // lower(CAST(NULL AS VARCHAR)) -> [5, 0, -1, "hello", "HELLO", null]
                row("length('')", call("length", L, lit("")), "0"),
                row("length(NULL)", call("length", L, nul(V)), "-1"),
                row("lower('HeLLo')", call("lower", V, lit("HeLLo")), "hello"),
                row("upper('HeLLo')", call("upper", V, lit("HeLLo")), "HELLO"),
                row("lower(NULL)", call("lower", V, nul(V)), "null"),
                // SELECT CAST(1.0/0 AS VARCHAR), CAST(-1.0/0 AS VARCHAR), 1.0/0 IS NULL, ... -> ["NaN", "NaN", true, ...]
                row("1.0/0 IS NULL", call("is_null", B, call("/", D, lit(1.0), lit(0))), "true"),
                // SELECT now() > '2026-01-01'::timestamp, ts < '2026-11-15T00:00:00.000000Z'::timestamp, ts - 1000000
                // -> [true, true, "2026-11-14T09:59:59.000000Z"]
                row("now() > '2026-01-01'", call(">", B, call("now", T), ts("2026-01-01T00:00:00Z")), "true"),
                row("ts - 1000000", call("-", T, ts(T10), lit(1000000)), "2026-11-14T09:59:59.000000Z")
        );
    }

    /// The expression evaluates to QuestDB's value for it.
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesQuestDb(final Case c) {
        assertThat(eval(c.expr())).as(c.sql()).isEqualTo(c.expected());
    }

    /// A text that is no timestamp fails when it meets a TIMESTAMP in a comparison, as QuestDB's
    /// `ts = '2026-11-14T10:00:00Tzz'` and `ts = ''` fail with `Invalid date`, and so does `ts < '2026-11-14T11:00+01:00'`
    /// (a zone before the seconds field).
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2026-11-14T10:00:00Tzz", "", "2026-11-14T11:00+01:00"})
    void textThatIsNoTimestampFails(final String text) {
        assertThatThrownBy(() -> eval(call("=", B, ts(T10), lit(text)))).isInstanceOf(CdbException.class);
    }

    private static Case row(final String sql, final Bound expr, final String expected) {
        return new Case(sql, expr, expected);
    }
}
