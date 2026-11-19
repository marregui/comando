package dev.marregui.comando.cdb.plan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.Compare;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.TableDef;

import static org.assertj.core.api.Assertions.assertThat;

/// The planner gives a scan the terms of its filter's top-level AND comparisons of a column with a plan-time constant,
/// in the filter's domain, leaves out what it cannot coerce exactly, reads a LATEST ON's scan newest first behind its
/// switch, and tells an ORDER BY's sort how many rows a LIMIT reads.
class ScanTermsTest {

    private static final long NOW = Times.parse("2026-11-14T12:00:00", 0);
    private static final long AT = Times.parse("2026-11-14T00:00:00", 0);

    private static final Map<String, TableDef> TABLES = Map.of("t", new TableDef("t", List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("x", CdbType.LONG),
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("at", CdbType.TIMESTAMP)), 0, PartitionBy.DAY, List.of(), 0));

    private static Plan plan(final String sql, final boolean scanShortcuts) {
        return Planner.plan(Parser.parse(sql), name -> TABLES.get(name.toLowerCase(Locale.ROOT)), NOW,
                PlannerTest::sourceFields, scanShortcuts);
    }

    /// The first plan of `type` on the way down from `plan` through single-child plans.
    private static <T extends Plan> T find(final Plan plan, final Class<T> type) {
        Plan p = plan;
        while (!type.isInstance(p)) {
            p = switch (p) {
                case Plan.Filter(final Plan child, final Bound _) -> child;
                case Plan.Project(final Plan child, final List<Bound> _, final List<?> _) -> child;
                case Plan.LatestOn(final Plan child, final int _, final int[] _, final boolean _) -> child;
                case Plan.Sort(final Plan child, final List<Plan.SortKey> _, final long _) -> child;
                case Plan.Limit(final Plan child, final long _, final long _) -> child;
                case Plan.Window(final Plan child, final List<Bound.WindowCall> _, final List<?> _) -> child;
                case Plan.Scan _, Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.Distinct _, Plan.SetOp _,
                     Plan.Join _, Plan.CreateTable _, Plan.Update _, Plan.Insert _, Plan.AddColumns _,
                     Plan.SetTtl _, Plan.Explain _, Plan.DropTable _, Plan.Truncate _, Plan.DropPartitions _,
                     Plan.Delete _, Plan.DropColumn _, Plan.RenameColumn _, Plan.Backup _ ->
                        throw new AssertionError("cannot find " + type.getSimpleName() + " in " + plan);
            };
        }
        return type.cast(p);
    }

    private static List<Plan.ScanTerm> terms(final String where) {
        return find(plan("SELECT * FROM t WHERE " + where, true), Plan.Scan.class).terms();
    }

    private static Plan.ScanTerm longTerm(final int column, final Compare op, final long value) {
        return new Plan.ScanTerm(column, op, value, Double.NaN, null);
    }

    private static Plan.ScanTerm doubleTerm(final Compare op, final double value) {
        return new Plan.ScanTerm(2, op, Long.MIN_VALUE, value, null);
    }

    private static Plan.ScanTerm textTerm(final int column, final Compare op, final String value) {
        return new Plan.ScanTerm(column, op, Long.MIN_VALUE, Double.NaN, value);
    }

    /// Each operator on a LONG, DOUBLE and non-designated TIMESTAMP column, `=` and `!=` on SYMBOL and VARCHAR, each
    /// AND term in order.
    @Test
    void comparisonsWithConstantsBecomeTerms() {
        assertThat(terms("x = 1 AND x != 2 AND x < 3 AND x <= 4 AND x > 5 AND x >= 6")).containsExactly(
                longTerm(1, Compare.EQ, 1), longTerm(1, Compare.NE, 2), longTerm(1, Compare.LT, 3),
                longTerm(1, Compare.LE, 4), longTerm(1, Compare.GT, 5), longTerm(1, Compare.GE, 6));
        assertThat(terms("d = 1.5 AND d != -2.5 AND d < 3.5 AND d <= 4.5 AND d > 5.5 AND d >= 6.5")).containsExactly(
                doubleTerm(Compare.EQ, 1.5), doubleTerm(Compare.NE, -2.5), doubleTerm(Compare.LT, 3.5),
                doubleTerm(Compare.LE, 4.5), doubleTerm(Compare.GT, 5.5), doubleTerm(Compare.GE, 6.5));
        assertThat(terms("s = 'a' AND v != 'q' AND at >= '2026-11-14T00:00:00.000000Z'")).containsExactly(
                textTerm(3, Compare.EQ, "a"), textTerm(4, Compare.NE, "q"), longTerm(6, Compare.GE, AT));
    }

    /// A constant on the left flips the operator; BETWEEN is two terms from the smaller bound to the larger.
    @Test
    void constantsOnTheLeftAndBetween() {
        assertThat(terms("5 < x AND 2.5 >= d")).containsExactly(longTerm(1, Compare.GT, 5),
                doubleTerm(Compare.LE, 2.5));
        assertThat(terms("x BETWEEN 9 AND 3")).containsExactly(longTerm(1, Compare.GE, 3), longTerm(1, Compare.LE, 9));
        assertThat(terms("d BETWEEN 1.5 AND -1.5")).containsExactly(doubleTerm(Compare.GE, -1.5),
                doubleTerm(Compare.LE, 1.5));
    }

    /// Constants are coerced as the comparison coerces them: an integer against DOUBLE is that double, `now()` and an
    /// ISO string against TIMESTAMP are their micros; a fraction against LONG is compared as DOUBLE, so it is left out.
    @Test
    void constantsAreCoercedAsTheComparisonCoercesThem() {
        assertThat(terms("d = 2")).containsExactly(doubleTerm(Compare.EQ, 2.0));
        assertThat(terms("at < now()")).containsExactly(longTerm(6, Compare.LT, NOW));
        assertThat(terms("at != '2026-11-14T00:00:00.000000Z'")).containsExactly(longTerm(6, Compare.NE, AT));
        assertThat(terms("x = 2.5")).isEmpty();
    }

    /// Left out: NULL, OR, NOT, an expression of a column, a column against a column, a DOUBLE zero of either sign,
    /// a SYMBOL or VARCHAR range, a BOOLEAN, and the designated timestamp's bounds (the range holds them); its `!=` is
    /// a term.
    @Test
    void whatCannotBeCoercedExactlyIsLeftOut() {
        for (final String where : List.of("x = NULL", "x != NULL", "d = NULL", "s = NULL", "x > 1 OR d < 2",
                "NOT (x > 1)", "x + 1 > 1", "x = x", "d = 0.0", "d != -0.0", "d BETWEEN -0.0 AND 1.5", "d < 0",
                "s > 'a'", "v <= 'q'", "b = true", "ts >= '2026-11-14T00:00:00.000000Z'",
                "ts BETWEEN '2026-11-14T00:00:00.000000Z' AND '2026-11-15T00:00:00.000000Z'")) {
            assertThat(terms(where)).as(where).isEmpty();
        }
        assertThat(terms("ts != '2026-11-14T00:00:00.000000Z' AND (x > 1 OR x < 0) AND x < 9"))
                .containsExactly(longTerm(0, Compare.NE, AT), longTerm(1, Compare.LT, 9));
    }

    /// BETWEEN on the designated timestamp bounds the scan from the smaller bound to just after the larger, reversed
    /// bounds too.
    @Test
    void betweenOnTheTimestampBoundsTheRangeEitherWay() {
        final long day = Times.parse("2026-11-15T00:00:00", 0);
        for (final String where : List.of("ts BETWEEN '2026-11-14T00:00:00.000000Z' AND '2026-11-15T00:00:00.000000Z'",
                "ts BETWEEN '2026-11-15T00:00:00.000000Z' AND '2026-11-14T00:00:00.000000Z'")) {
            final Plan.Scan scan = find(plan("SELECT * FROM t WHERE " + where, true), Plan.Scan.class);
            assertThat(scan.fromMicros()).as(where).isEqualTo(AT);
            assertThat(scan.toMicros()).as(where).isEqualTo(day + 1);
        }
    }

    /// A filter made only of text `=` and `!=` terms is planned as the scan alone, as the text terms are exact; a numeric
    /// term, a NULL constant, a constant that does not survive UTF-8 (a lone surrogate) or an OR keep the filter, and
    /// such a constant gives no term.
    @Test
    void aFilterOfExactTextTermsIsTheScanAlone() {
        for (final String where : List.of("s = 'a'", "v != 'x'", "s = 'a' AND v != 'x'", "'a' = s")) {
            assertThat(plan("SELECT * FROM t WHERE " + where, true)).as(where).isInstanceOf(Plan.Scan.class);
        }
        for (final String where : List.of("s = 'a' AND x > 1", "s = NULL", "s = 'a' OR v = 'x'", "s = '\uD800'")) {
            assertThat(plan("SELECT * FROM t WHERE " + where, true)).as(where).isInstanceOf(Plan.Filter.class);
        }
        assertThat(terms("s = '\uD800'")).isEmpty();
        assertThat(plan("SELECT * FROM t WHERE s = 'a'", false)).isInstanceOf(Plan.Filter.class);
        assertThat(find(plan("SELECT * FROM t WHERE s = 'a'", false), Plan.Scan.class).terms()).isEmpty();
    }

    /// `ORDER BY … LIMIT n` and `LIMIT lo, hi` tell the sort the first n or the larger bound's rows are read (bounds
    /// given high to low are swapped by the limit); a LIMIT from the end leaves it unbounded.
    @Test
    void limitTellsTheSortHowManyRowsAreRead() {
        assertThat(find(plan("SELECT * FROM t ORDER BY x LIMIT 5", true), Plan.Sort.class).limit()).isEqualTo(5);
        assertThat(find(plan("SELECT * FROM t ORDER BY x DESC LIMIT 2, 7", true), Plan.Sort.class).limit())
                .isEqualTo(7);
        assertThat(find(plan("SELECT * FROM t ORDER BY x LIMIT 7, 0", true), Plan.Sort.class).limit()).isEqualTo(7);
        assertThat(find(plan("SELECT * FROM t ORDER BY x LIMIT -3", true), Plan.Sort.class).limit()).isEqualTo(-1);
        assertThat(find(plan("SELECT * FROM t ORDER BY x", true), Plan.Sort.class).limit()).isEqualTo(-1);
    }

    /// LATEST ON the designated timestamp over a scan or a filtered scan reads it newest first when the switch is on,
    /// in time order when it is off; LATEST ON another timestamp column keeps time order.
    @Test
    void latestOnReadsNewestFirstBehindTheSwitch() {
        for (final String sql : List.of("SELECT * FROM t LATEST ON ts PARTITION BY s",
                "SELECT * FROM t WHERE x > 1 LATEST ON ts PARTITION BY s")) {
            final Plan on = plan(sql, true);
            assertThat(find(on, Plan.LatestOn.class).newestFirst()).as(sql).isTrue();
            assertThat(find(on, Plan.Scan.class).newestFirst()).as(sql).isTrue();
            final Plan off = plan(sql, false);
            assertThat(find(off, Plan.LatestOn.class).newestFirst()).as(sql).isFalse();
            assertThat(find(off, Plan.Scan.class).newestFirst()).as(sql).isFalse();
        }
        final Plan other = plan("SELECT * FROM t LATEST ON at PARTITION BY s", true);
        assertThat(find(other, Plan.LatestOn.class).newestFirst()).isFalse();
        assertThat(find(other, Plan.Scan.class).newestFirst()).isFalse();
    }
}
