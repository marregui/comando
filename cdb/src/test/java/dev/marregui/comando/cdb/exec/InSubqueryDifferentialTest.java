package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbResult;

import static org.assertj.core.api.Assertions.assertThat;

/// `x [NOT] IN (SELECT y ...)` against the same statement with the subquery's values written out as an IN list, over
/// seeded random tables with NULLs and repeats: both answer the same rows. The subquery is run alone first and its rows
/// rendered as literals (an empty answer as `false` for IN and `true` for NOT IN, which an empty list would mean); its
/// shapes are a whole column, a filtered one, a DISTINCT one, a LIMITed one and an empty one, and the pairs of operand
/// and subquery column include the ones whose types widen (LONG and DOUBLE, SYMBOL and VARCHAR).
class InSubqueryDifferentialTest {

    // 2026-11-01T00:00:00Z, the first designated timestamp of every table
    private static final long BASE_MICROS = 1_793_491_200_000_000L;
    private static final long SECOND_MICROS = 1_000_000L;
    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final String COLUMNS = " (k SYMBOL, s VARCHAR, l LONG, d DOUBLE, t TIMESTAMP, ts TIMESTAMP)"
            + " TIMESTAMP(ts) PARTITION BY DAY";
    // the outer table's columns and, at the same index, the subquery table's
    private static final String[] OUTER = {"k", "s", "l", "d", "t"};
    private static final String[] INNER = {"bk", "bs", "bl", "bd", "bt"};
    // operand and subquery column, as indexes into OUTER and INNER: same types, then types that widen
    private static final int[][] PAIRS = {{0, 0}, {1, 1}, {2, 2}, {3, 3}, {4, 4}, {0, 1}, {1, 0}, {2, 3}, {3, 2}};
    private static final int ROUNDS = 150;
    private static final int OUTER_ROWS = 60;
    private static final int INNER_ROWS = 25;

    @TempDir
    private Path root;

    /// A SELECT filtered by an IN subquery answers, as a multiset, the rows the same SELECT answers with the IN list
    /// written out.
    @Test
    void selectMatchesTheWrittenOutList() {
        final TwoSeedRandom rnd = TwoSeedRandom.of("cdb.insubquery", "selectMatchesTheWrittenOutList");
        try (final Cdb cdb = Cdb.open(root)) {
            create(cdb, rnd, "a", "", OUTER_ROWS);
            create(cdb, rnd, "b", "b", INNER_ROWS);
            for (int round = 0; round < ROUNDS; round++) {
                final int[] pair = PAIRS[rnd.nextInt(PAIRS.length)];
                final String subquery = subquery(rnd, "b", INNER[pair[1]]);
                final boolean negated = rnd.nextBoolean();
                final String head = "SELECT k, s, l, d, t, ts FROM a WHERE ";
                final String viaSubquery = head + predicate(OUTER[pair[0]], negated, "(" + subquery + ")");
                final String viaList = head + predicate(OUTER[pair[0]], negated, written(cdb, subquery));
                assertThat(answer(cdb, viaSubquery)).as("round %d: %s", round, viaSubquery)
                        .containsExactlyInAnyOrderElementsOf(answer(cdb, viaList));
            }
        }
    }

    /// UPDATE and DELETE filtered by an IN subquery change, on one of two twin tables, the rows the same statement with
    /// the IN list written out changes on the other: the same count, then the same contents. The subquery reads the
    /// other table or the changed table itself, which it reads before the change, as the written-out list was.
    @Test
    void updateAndDeleteMatchTheWrittenOutList() {
        final TwoSeedRandom rnd = TwoSeedRandom.of("cdb.insubquery", "updateAndDeleteMatchTheWrittenOutList");
        try (final Cdb cdb = Cdb.open(root)) {
            create(cdb, rnd, "b", "b", INNER_ROWS);
            for (int round = 0; round < ROUNDS / 3; round++) {
                cdb.run("DROP TABLE IF EXISTS p");
                cdb.run("DROP TABLE IF EXISTS q");
                cdb.run("CREATE TABLE p" + COLUMNS);
                cdb.run("CREATE TABLE q" + COLUMNS);
                final String values = values(rnd, OUTER_ROWS);
                cdb.run("INSERT INTO p VALUES " + values);
                cdb.run("INSERT INTO q VALUES " + values);
                for (int change = 0; change < 3; change++) {
                    final int[] pair = PAIRS[rnd.nextInt(PAIRS.length)];
                    final boolean self = rnd.percent(30);
                    final String subquery = self
                            ? subquery(rnd, "p", OUTER[pair[1]])
                            : subquery(rnd, "b", INNER[pair[1]]);
                    final boolean negated = rnd.nextBoolean();
                    final String operand = OUTER[pair[0]];
                    final String written = written(cdb, subquery);
                    final String head = rnd.nextBoolean() ? "UPDATE %s SET l = " + rnd.between(-5, 5) + " WHERE "
                            : "DELETE FROM %s WHERE ";
                    final String viaSubquery = head.formatted("p") + predicate(operand, negated, "(" + subquery + ")");
                    final String viaList = head.formatted("q") + predicate(operand, negated, written);
                    assertThat(changed(cdb, viaSubquery)).as("round %d: %s", round, viaSubquery)
                            .isEqualTo(changed(cdb, viaList));
                    assertThat(answer(cdb, "SELECT * FROM p")).as("round %d: %s", round, viaSubquery)
                            .containsExactlyInAnyOrderElementsOf(answer(cdb, "SELECT * FROM q"));
                }
            }
        }
    }

    // a table of `rows` random rows, its columns named with `prefix`
    private static void create(
            final Cdb cdb,
            final TwoSeedRandom rnd,
            final String table,
            final String prefix,
            final int rows
    ) {
        cdb.run("CREATE TABLE " + table + " (" + prefix + "k SYMBOL, " + prefix + "s VARCHAR, " + prefix + "l LONG, "
                + prefix + "d DOUBLE, " + prefix + "t TIMESTAMP, " + prefix + "ts TIMESTAMP) TIMESTAMP(" + prefix
                + "ts) PARTITION BY DAY");
        cdb.run("INSERT INTO " + table + " VALUES " + values(rnd, rows));
    }

    // `rows` value tuples over small domains, so values repeat and meet across tables; one in ten is NULL
    private static String values(final TwoSeedRandom rnd, final int rows) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            sb.append(i == 0 ? "(" : ", (")
                    .append(nullOr(rnd, "'v" + rnd.between(0, 7) + '\'')).append(", ")
                    .append(nullOr(rnd, "'v" + rnd.between(0, 7) + '\'')).append(", ")
                    .append(nullOr(rnd, Integer.toString(rnd.between(-5, 5)))).append(", ")
                    .append(nullOr(rnd, Double.toString(rnd.between(-10, 10) * 0.5))).append(", ")
                    .append(nullOr(rnd, timestamp(BASE_MICROS + rnd.between(0, 5) * SECOND_MICROS
                            + rnd.between(0, 2)))).append(", ")
                    .append(timestamp(BASE_MICROS + i * 60 * SECOND_MICROS)).append(')');
        }
        return sb.toString();
    }

    private static String nullOr(final TwoSeedRandom rnd, final String literal) {
        return rnd.percent(10) ? "NULL" : literal;
    }

    // a TIMESTAMP literal to the microsecond, so a list of them is a list of instants, not of intervals
    private static String timestamp(final long micros) {
        return "'" + ISO_MICROS.format(Instant.EPOCH.plus(micros, ChronoUnit.MICROS)) + "'::TIMESTAMP";
    }

    // one subquery of `column` over `table`: whole, filtered, distinct, limited or empty
    private static String subquery(final TwoSeedRandom rnd, final String table, final String column) {
        final String filter = table.equals("b") ? "bl" : "l";
        return switch (rnd.nextInt(5)) {
            case 0 -> "SELECT " + column + " FROM " + table;
            case 1 -> "SELECT " + column + " FROM " + table + " WHERE " + filter + " > " + rnd.between(-5, 5);
            case 2 -> "SELECT DISTINCT " + column + " FROM " + table;
            case 3 -> "SELECT " + column + " FROM " + table + " LIMIT " + rnd.between(1, 10);
            default -> "SELECT " + column + " FROM " + table + " WHERE " + filter + " > 100";
        };
    }

    // the subquery's rows written out as an IN list's literals in parentheses, or null when it has none
    private static String written(final Cdb cdb, final String subquery) {
        final CdbResult result = cdb.run(subquery);
        if (result.rowCount() == 0) {
            return null;
        }
        final List<String> literals = new ArrayList<>(result.rowCount());
        for (int row = 0; row < result.rowCount(); row++) {
            if (result.isNull(row, 0)) {
                literals.add("NULL");
            } else {
                literals.add(switch (result.type(0)) {
                    case BOOLEAN -> String.valueOf(result.getBoolean(row, 0));
                    case LONG -> Long.toString(result.getLong(row, 0));
                    case DOUBLE -> Double.toString(result.getDouble(row, 0));
                    case TIMESTAMP -> timestamp(result.getLong(row, 0));
                    case SYMBOL, VARCHAR -> "'" + result.getText(row, 0) + "'";
                });
            }
        }
        return "(" + String.join(", ", literals) + ")";
    }

    // `operand [NOT] IN values`; no values is an empty set, which holds nothing, so IN is false and NOT IN true
    private static String predicate(final String operand, final boolean negated, final String values) {
        if (values == null) {
            return negated ? "true" : "false";
        }
        return operand + (negated ? " NOT IN " : " IN ") + values;
    }

    private static List<String> answer(final Cdb cdb, final String sql) {
        return SqlRows.rows(cdb.run(sql));
    }

    private static long changed(final Cdb cdb, final String sql) {
        return cdb.run(sql).updateCount();
    }
}
