package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.AggRows;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

import static dev.marregui.comando.cdb.exec.agg.AggRows.field;
import static dev.marregui.comando.cdb.exec.agg.AggRows.row;
import static org.assertj.core.api.Assertions.assertThat;

/// [SampleByCursor] answers the same, bit for bit, whatever the page size: one row a page goes to the accumulators
/// row by row, full pages hand them the runs of one bucket and key as ranges, and the runs cross page boundaries. The
/// rows are random: timestamps from before the epoch on, some equal, some NULL; keys in runs of up to 3,000 rows,
/// some NULL; values with NaN and -0.0. Seed in the output; replay with `-Dcdb.agg.seed=<seed>`.
class SampleByRunsTest {

    private static final long SEED = Long.getLong("cdb.agg.seed", System.nanoTime());
    // three days before the epoch, so the strides floor negative timestamps
    private static final long START = -259_200_000_000L;
    private static final int TS_COLUMN = 0;
    private static final Bound TS = new Bound.Column(0, CdbType.TIMESTAMP);
    private static final Bound X = new Bound.Column(1, CdbType.DOUBLE);
    private static final Bound K = new Bound.Column(2, CdbType.LONG);
    private static final Bound Y = new Bound.Column(3, CdbType.LONG);
    private static final int[] PAGE_SIZES = {Page.CAPACITY, 1_000, 7};

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// Fixed strides of a minute to a day, with and without a key.
    @Test
    void fixedStridesAnswerAlikeForAnyPageSize() {
        final List<List<Object>> rows = rows(30_000, 2_000_000);
        for (final String unit : new String[] {"m", "h", "d"}) {
            for (final List<Bound> keys : List.of(List.<Bound>of(), List.of(K))) {
                alike(rows, 15, unit, keys, List.of());
            }
        }
    }

    /// Month buckets keep their calendar path and answer alike too.
    @Test
    void calendarStridesAnswerAlikeForAnyPageSize() {
        final List<List<Object>> rows = rows(20_000, 7_200_000_000L);
        alike(rows, 1, "M", List.of(K), List.of());
        alike(rows, 1, "y", List.of(), List.of());
    }

    /// A FILL lays out every key in every bucket from the same groups whatever the page size.
    @Test
    void filledBucketsAnswerAlikeForAnyPageSize() {
        final List<List<Object>> rows = rows(20_000, 20_000_000);
        alike(rows, 1, "h", List.of(K), List.of("PREV"));
        alike(rows, 1, "h", List.of(), List.of("NULL"));
    }

    /// Each row lands in the bucket `floorDiv(ts, stride) * stride`: the count and the exact sum per bucket match a
    /// plain map built over the rows, NULL timestamps dropped.
    @Test
    void fixedStrideBucketsHoldTheirRows() {
        final List<List<Object>> rows = rows(30_000, 2_000_000);
        final long strideMicros = 15 * 60_000_000L;
        final Map<Long, long[]> buckets = new LinkedHashMap<>();
        for (final List<Object> row : rows) {
            if (row.get(TS_COLUMN) instanceof final Long ts) {
                final long[] bucket = buckets.computeIfAbsent(Math.floorDiv(ts, strideMicros) * strideMicros,
                        _ -> new long[2]);
                bucket[0]++;
                bucket[1] += (Long) row.get(3);
            }
        }
        final List<List<Object>> expected = new ArrayList<>();
        for (final Map.Entry<Long, long[]> bucket : buckets.entrySet()) {
            expected.add(row(bucket.getKey(), bucket.getValue()[0], bucket.getValue()[1]));
        }
        final List<Bound.Agg> aggs = List.of(new Bound.Agg("count", List.of(), false, CdbType.LONG),
                new Bound.Agg("sum", List.of(Y), false, CdbType.LONG));
        final List<Field> out = List.of(field("ts", CdbType.TIMESTAMP), field("count", CdbType.LONG),
                field("sum", CdbType.LONG));
        final List<List<Object>> got = AggRows.drain(new SampleByCursor(AggRows.source(fields(), Page.CAPACITY, rows),
                new Plan.SampleBy(null, TS_COLUMN, 15, "m", List.of(), aggs, List.of(), out), 0));
        assertThat(got).containsExactlyElementsOf(expected);
    }

    // the cursor over pages of each size answers as over pages of one row
    private static void alike(
            final List<List<Object>> rows,
            final long count,
            final String unit,
            final List<Bound> keys,
            final List<String> fill
    ) {
        final List<List<Object>> expected = run(rows, 1, count, unit, keys, fill);
        assertThat(expected).isNotEmpty();
        for (final int pageSize : PAGE_SIZES) {
            assertThat(run(rows, pageSize, count, unit, keys, fill)).as("%d%s by %s over pages of %d", count, unit,
                    keys, pageSize).containsExactlyElementsOf(expected);
        }
    }

    private static List<List<Object>> run(
            final List<List<Object>> rows,
            final int pageSize,
            final long count,
            final String unit,
            final List<Bound> keys,
            final List<String> fill
    ) {
        final List<Bound.Agg> aggs = List.of(new Bound.Agg("count", List.of(), false, CdbType.LONG),
                new Bound.Agg("count", List.of(X), false, CdbType.LONG),
                new Bound.Agg("sum", List.of(X), false, CdbType.DOUBLE),
                new Bound.Agg("avg", List.of(X), false, CdbType.DOUBLE),
                new Bound.Agg("min", List.of(X), false, CdbType.DOUBLE),
                new Bound.Agg("max", List.of(X), false, CdbType.DOUBLE),
                new Bound.Agg("first", List.of(X), false, CdbType.DOUBLE),
                new Bound.Agg("last", List.of(TS), false, CdbType.TIMESTAMP),
                new Bound.Agg("sum", List.of(Y), false, CdbType.LONG),
                new Bound.Agg("percentile_cont", List.of(X, new Bound.Constant(0.9, CdbType.DOUBLE)), false,
                        CdbType.DOUBLE));
        final List<Field> out = new ArrayList<>();
        out.add(field("ts", CdbType.TIMESTAMP));
        for (final Bound key : keys) {
            out.add(field("k", key.type()));
        }
        for (final Bound.Agg agg : aggs) {
            out.add(field(agg.function(), agg.type()));
        }
        final List<String> fills = new ArrayList<>();
        for (int a = 0; a < aggs.size() && !fill.isEmpty(); a++) {
            fills.add(fill.getFirst());
        }
        return AggRows.drain(new SampleByCursor(AggRows.source(fields(), pageSize, rows),
                new Plan.SampleBy(null, TS_COLUMN, count, unit, keys, aggs, fills, out), 0));
    }

    private static List<Field> fields() {
        return List.of(field("ts", CdbType.TIMESTAMP), field("x", CdbType.DOUBLE), field("k", CdbType.LONG),
                field("y", CdbType.LONG));
    }

    // n rows, the timestamp rising by 0 to maxStepMicros a row; the key held for runs of 1 to 3,000 rows
    private List<List<Object>> rows(final int n, final long maxStepMicros) {
        final List<List<Object>> rows = new ArrayList<>();
        long ts = START + random.nextLong(maxStepMicros + 1);
        Long key = 0L;
        int keyRun = 0;
        for (int i = 0; i < n; i++) {
            ts += random.nextInt(4) == 0 ? 0 : random.nextLong(maxStepMicros + 1);
            if (keyRun-- == 0) {
                keyRun = random.nextInt(3_000);
                key = random.nextInt(10) == 0 ? null : Long.valueOf(random.nextInt(5));
            }
            final int pick = random.nextInt(20);
            final double x = pick == 0 ? Double.NaN : pick == 1 ? -0.0 : pick == 2 ? 0.0 : random.nextGaussian() * 100;
            rows.add(row(random.nextInt(500) == 0 ? null : ts, x, key, (long) random.nextInt(1_000_000)));
        }
        return rows;
    }
}
