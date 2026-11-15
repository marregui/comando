package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.AggRows;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

import static dev.marregui.comando.cdb.exec.agg.AggRows.field;
import static dev.marregui.comando.cdb.exec.agg.AggRows.row;
import static org.assertj.core.api.Assertions.assertThat;

/// The runs of one bucket (SAMPLE BY without keys) and of one LONG key (GROUP BY) at their edges: a bucket of one
/// row, a run longer than a page or ending on a page's last row, timestamps that leave a bucket and come back, and
/// timestamps at the ends of the `long` range. Each expected answer is built from the rows by a plain map.
class SampleByRunEdgesTest {

    private static final long HOUR_MICROS = 3_600_000_000L;
    private static final long MINUTE_MICROS = 60_000_000L;
    private static final int[] PAGE_SIZES = {Page.CAPACITY, 1_000, 7, 1};
    // two days before the epoch, so the buckets floor negative timestamps
    private static final long START = -172_800_000_000L;
    private static final Bound X = new Bound.Column(1, CdbType.DOUBLE);
    private static final Bound Y = new Bound.Column(2, CdbType.LONG);
    private static final List<Bound.Agg> AGGS = List.of(new Bound.Agg("count", List.of(), false, CdbType.LONG),
            new Bound.Agg("sum", List.of(Y), false, CdbType.LONG),
            new Bound.Agg("percentile_cont", List.of(X, new Bound.Constant(0.95, CdbType.DOUBLE)), false,
                    CdbType.DOUBLE));

    /// 3,000 buckets of one row each: every row is a run of its own, whatever the page size.
    @Test
    void bucketsOfOneRowKeepTheirRow() {
        final List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            rows.add(row(START + i * MINUTE_MICROS + i % 60, i * 0.25, (long) i));
        }
        final List<List<Object>> expected = expected(rows, MINUTE_MICROS);
        assertThat(expected).hasSize(3_000);
        for (final int pageSize : PAGE_SIZES) {
            assertThat(sampled(rows, pageSize, 1, "m")).as("pages of %d", pageSize)
                    .containsExactlyElementsOf(expected);
        }
    }

    /// Buckets of 2,500, 1, 1,500, 1,024, 1 and 1 rows: runs that span pages, start or end on a page's edge, and
    /// single rows between them.
    @Test
    void runsAcrossPageBoundariesAnswerAlike() {
        final List<List<Object>> rows = new ArrayList<>();
        final int[] sizes = {2_500, 1, 1_500, 1_024, 1, 1};
        long y = 0;
        for (int b = 0; b < sizes.length; b++) {
            for (int i = 0; i < sizes[b]; i++) {
                // values out of order inside a bucket, so the percentile selects
                rows.add(row(START + b * HOUR_MICROS + i * 1_000L, (double) ((i * 7_919) % 4_093) - 2_000, y++));
            }
        }
        final List<List<Object>> expected = expected(rows, HOUR_MICROS);
        assertThat(expected).hasSize(sizes.length);
        for (final int pageSize : PAGE_SIZES) {
            assertThat(sampled(rows, pageSize, 1, "h")).as("pages of %d", pageSize)
                    .containsExactlyElementsOf(expected);
        }
    }

    /// Timestamps that leave a bucket and come back, NULL ones, and ones at the ends of the `long` range: the end of
    /// the bucket of `Long.MAX_VALUE` wraps below its start, and the floor of `Long.MIN_VALUE + 1` wraps to just
    /// below `Long.MAX_VALUE`, where `Long.MAX_VALUE - 1` must not take it. Every row lands in
    /// `floorDiv(ts, stride) * stride`, wrapped as the multiplication wraps.
    @Test
    void timestampsOutOfOrderFloorEachRow() {
        final Long[] times = {
            START, START + HOUR_MICROS, START + 1, null, START + HOUR_MICROS - 1, -1L, 0L, -1L, HOUR_MICROS - 1,
            Long.MAX_VALUE, Long.MAX_VALUE - 1, START + 2, Long.MAX_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1,
            Long.MIN_VALUE + 2, 0L
        };
        final List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            rows.add(row(times[i], (double) i, (long) i));
        }
        final List<List<Object>> expected = expected(rows, HOUR_MICROS);
        assertThat(expected).hasSize(6);
        for (final int pageSize : PAGE_SIZES) {
            assertThat(sampled(rows, pageSize, 1, "h")).as("pages of %d", pageSize)
                    .containsExactlyElementsOf(expected);
        }
    }

    /// GROUP BY one LONG key finds its runs on the key's values: runs of 1 to 2,000 rows, NULL keys among them, a
    /// key that comes back after others; any page size answers as pages of one row.
    @Test
    void groupByOneLongKeyAnswersAlikeForAnyPageSize() {
        final List<List<Object>> rows = new ArrayList<>();
        final int[] runs = {2_000, 1, 1, 1_023, 1_025, 3, 1};
        final Long[] keys = {5L, 6L, null, 5L, 7L, null, 6L};
        for (int r = 0; r < runs.length; r++) {
            for (int i = 0; i < runs[r]; i++) {
                rows.add(row(keys[r], (double) ((i * 31) % 97), (long) i));
            }
        }
        final Bound key = new Bound.Column(0, CdbType.LONG);
        final List<Field> fields = List.of(field("k", CdbType.LONG), field("x", CdbType.DOUBLE),
                field("y", CdbType.LONG));
        final List<Field> out = List.of(field("k", CdbType.LONG), field("count", CdbType.LONG),
                field("sum", CdbType.LONG), field("percentile_cont", CdbType.DOUBLE));
        final Plan.Aggregate spec = new Plan.Aggregate(null, List.of(key), AGGS, out);
        final List<List<Object>> expected = AggRows.drain(new GroupByCursor(AggRows.source(fields, 1, rows), spec, 0));
        assertThat(expected).hasSize(4);
        for (final int pageSize : PAGE_SIZES) {
            assertThat(AggRows.drain(new GroupByCursor(AggRows.source(fields, pageSize, rows), spec, 0)))
                    .as("pages of %d", pageSize).containsExactlyElementsOf(expected);
        }
    }

    // per bucket in ascending order: its start, the count, the sum of y and percentile_cont(x, 0.95) by a full sort
    private static List<List<Object>> expected(final List<List<Object>> rows, final long strideMicros) {
        final Map<Long, List<List<Object>>> buckets = new TreeMap<>();
        for (final List<Object> row : rows) {
            if (row.getFirst() instanceof final Long ts) {
                buckets.computeIfAbsent(Math.floorDiv(ts, strideMicros) * strideMicros, _ -> new ArrayList<>())
                        .add(row);
            }
        }
        final List<List<Object>> expected = new ArrayList<>();
        for (final Map.Entry<Long, List<List<Object>>> bucket : buckets.entrySet()) {
            final List<List<Object>> in = bucket.getValue();
            final double[] xs = new double[in.size()];
            long sum = 0;
            for (int i = 0; i < xs.length; i++) {
                xs[i] = (Double) in.get(i).get(1);
                sum += (Long) in.get(i).get(2);
            }
            Arrays.sort(xs);
            final double position = 0.95 * (xs.length - 1);
            final int lo = (int) Math.floor(position);
            final int hi = (int) Math.ceil(position);
            expected.add(row(bucket.getKey(), (long) xs.length, sum, xs[lo] + (position - lo) * (xs[hi] - xs[lo])));
        }
        return expected;
    }

    private static List<List<Object>> sampled(
            final List<List<Object>> rows,
            final int pageSize,
            final long count,
            final String unit
    ) {
        final List<Field> fields = List.of(field("ts", CdbType.TIMESTAMP), field("x", CdbType.DOUBLE),
                field("y", CdbType.LONG));
        final List<Field> out = List.of(field("ts", CdbType.TIMESTAMP), field("count", CdbType.LONG),
                field("sum", CdbType.LONG), field("percentile_cont", CdbType.DOUBLE));
        return AggRows.drain(new SampleByCursor(AggRows.source(fields, pageSize, rows),
                new Plan.SampleBy(null, 0, count, unit, List.of(), AGGS, List.of(), out), 0));
    }
}
