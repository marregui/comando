package dev.marregui.comando.core.result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.result.ResultStats.Column;
import dev.marregui.comando.testkit.FakeResult;

import static org.assertj.core.api.Assertions.assertThat;

/// [ResultStats] summarises the given columns of a result's first rows.
///
/// - Each kind is ordered by its value: longs and timestamps as numbers, doubles with NaN largest, false below true,
///   text by `String.compareTo`; min and max come back as display text, null for a column with no value.
/// - Distinct values are exact below [ResultStats#DISTINCT_CAP] and reported as the cap above it.
/// - Only the first `rows` rows are read, and a passed deadline stops the scan at the first clock read.
class ResultStatsTest {

    /// Every kind gets its nulls, its min and max in its own order as display text, and its distinct count; the
    /// columns come back in the order asked for, with their names and types.
    @Test
    void eachKindHasItsNullsMinMaxAndDistinct() {
        final FakeResult r = new FakeResult(
                1,
                List.of("l", "d", "b", "t", "s"),
                List.of(ColumnKind.LONG, ColumnKind.DOUBLE, ColumnKind.BOOLEAN, ColumnKind.TIMESTAMP, ColumnKind.TEXT),
                List.of(
                        Arrays.asList(5L, 1.5, true, 2_000_000L, "pear"),
                        Arrays.asList(-3L, Double.NaN, false, null, "apple"),
                        Arrays.asList(null, -0.5, null, 0L, null),
                        Arrays.asList(9L, null, true, 1_000_000L, "Zebra"),
                        Arrays.asList(5L, 1.5, true, 0L, "pear")));
        final ResultStats stats = ResultStats.of(r, r.rowCount(), new int[] {4, 0, 1, 2, 3}, far());
        assertThat(stats.rowCount()).isEqualTo(5);
        assertThat(stats.scanned()).isEqualTo(5);
        assertThat(stats.partial()).isFalse();
        assertThat(stats.columns()).containsExactly(
                new Column("s", "TEXT", 1, "Zebra", "pear", 3),
                new Column("l", "LONG", 1, "-3", "9", 3),
                new Column("d", "DOUBLE", 1, "-0.5", "NaN", 3),
                new Column("b", "BOOLEAN", 1, "false", "true", 2),
                new Column("t", "TIMESTAMP", 1, "1970-01-01T00:00:00.000000Z", "1970-01-01T00:00:02.000000Z", 3));
    }

    /// A column whose every cell is NULL has no min, no max and no distinct value, and counts every row as a null.
    @Test
    void anAllNullColumnHasNoMinOrMax() {
        final List<List<Object>> rows = List.of(Arrays.asList(1L, null), Arrays.asList(2L, null));
        final FakeResult r = new FakeResult(1, List.of("id", "v"), List.of(ColumnKind.LONG, ColumnKind.DOUBLE), rows);
        final Column v = ResultStats.of(r, r.rowCount(), new int[] {1}, far()).columns().getFirst();
        assertThat(v).isEqualTo(new Column("v", "DOUBLE", 2, null, null, 0));
    }

    /// Below the cap the distinct count is exact, each value counted once however often it repeats.
    @Test
    void distinctIsExactBelowTheCap() {
        final int values = ResultStats.DISTINCT_CAP - 1;
        final List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 2 * values; i++) {
            rows.add(List.of((long) (i % values)));
        }
        final Column c = ResultStats.of(longs(rows), rows.size(), new int[] {0}, far()).columns().getFirst();
        assertThat(c.distinct()).isEqualTo(values);
        assertThat(c.distinctCapped()).isFalse();
    }

    /// Past the cap the distinct count stops at the cap and says so, while min and max still see every row.
    @Test
    void distinctStopsAtTheCapAndMinMaxSeeEveryRow() {
        final int values = ResultStats.DISTINCT_CAP + 5;
        final List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < values; i++) {
            rows.add(List.of((long) i));
        }
        final Column c = ResultStats.of(longs(rows), rows.size(), new int[] {0}, far()).columns().getFirst();
        assertThat(c.distinct()).isEqualTo(ResultStats.DISTINCT_CAP);
        assertThat(c.distinctCapped()).isTrue();
        assertThat(c.min()).isEqualTo("0");
        assertThat(c.max()).isEqualTo(Integer.toString(values - 1));
    }

    /// A `rows` argument below `rowCount()` scans only that prefix, and a full scan of it is not partial.
    @Test
    void onlyTheFirstRowsAreScanned() {
        final List<List<Object>> rows = List.of(List.of(1L), Arrays.asList((Object) null), List.of(7L));
        final ResultStats stats = ResultStats.of(longs(rows), 2, new int[] {0}, far());
        assertThat(stats.rowCount()).isEqualTo(2);
        assertThat(stats.scanned()).isEqualTo(2);
        assertThat(stats.partial()).isFalse();
        assertThat(stats.columns()).containsExactly(new Column("v", "LONG", 1, "1", "1", 1));
    }

    /// A deadline already passed stops the scan at the first clock read, after 65,536 rows, and the result says it
    /// is partial; with time left the same result is scanned whole.
    @Test
    void aPassedDeadlineStopsAtTheFirstClockRead() {
        final List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 70_000; i++) {
            rows.add(List.of((long) i));
        }
        final FakeResult r = longs(rows);
        final ResultStats stopped = ResultStats.of(r, rows.size(), new int[] {0}, System.nanoTime() - 1);
        assertThat(stopped.rowCount()).isEqualTo(70_000);
        assertThat(stopped.scanned()).isEqualTo(65_536);
        assertThat(stopped.partial()).isTrue();
        assertThat(stopped.columns().getFirst().max()).isEqualTo("65535");
        final ResultStats whole = ResultStats.of(r, rows.size(), new int[] {0}, far());
        assertThat(whole.scanned()).isEqualTo(70_000);
        assertThat(whole.partial()).isFalse();
        assertThat(whole.columns().getFirst().max()).isEqualTo("69999");
    }

    /// A deadline no test reaches.
    /// A min or max longer than 200 characters is cut with an ellipsis, so a cut value is told from an exact one.
    @Test
    void aLongMinOrMaxIsCutWithAnEllipsis() {
        final FakeResult r = new FakeResult(1L, List.of("t"), List.of(ColumnKind.TEXT),
                List.of(List.of("a".repeat(300)), List.of("b")));
        final Column c = ResultStats.of(r, r.rowCount(), new int[] {0}, far()).columns().getFirst();
        assertThat(c.min()).hasSize(200).endsWith("\u2026").startsWith("aaa");
        assertThat(c.max()).isEqualTo("b");
    }

    private static long far() {
        return System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
    }

    private static FakeResult longs(final List<List<Object>> rows) {
        return new FakeResult(1, List.of("v"), List.of(ColumnKind.LONG), rows);
    }
}
