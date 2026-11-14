package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.agg.AggRows;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;

import static dev.marregui.comando.cdb.exec.agg.AggRows.field;
import static dev.marregui.comando.cdb.exec.agg.AggRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [SampleByCursor] buckets rows by calendar-aligned UTC strides, in bucket order, and fills empty buckets as
/// written. Expected values marked QuestDB were read from the local QuestDB's `/exec` for the query in the comment;
/// `timestamp_sequence` there numbers only the rows that pass the filter, as the input rows here do.
class SampleByCursorTest {

    // 2023-11-14T22:00:00Z: timestamp_sequence(1700000000000000, …) starts at 22:13:20
    private static final long H22 = 1_699_999_200_000_000L;
    private static final long HOUR = 3_600_000_000L;
    private static final long START = 1_700_000_000_000_000L;
    private static final int TS_COLUMN = 0;
    private static final Bound X = new Bound.Column(1, CdbType.LONG);
    private static final Bound K = new Bound.Column(2, CdbType.LONG);

    /// QuestDB: `SELECT ts, count(), sum(x) FROM (SELECT timestamp_sequence(1700000000000000, 1000000000) ts, x
    /// FROM long_sequence(20)) timestamp(ts) SAMPLE BY 1h`, then `SAMPLE BY 3h`, aligned to the epoch.
    @Test
    void hourlyBucketsMatchQuestDb() {
        final List<List<Object>> rows = sequence(1_000_000_000L, 20);
        assertThat(run(rows, 1, "h", List.of(), List.of())).containsExactly(
                row(H22, 3L, 6L),
                row(H22 + HOUR, 4L, 22L),
                row(H22 + 2 * HOUR, 3L, 27L),
                row(H22 + 3 * HOUR, 4L, 50L),
                row(H22 + 4 * HOUR, 4L, 66L),
                row(H22 + 5 * HOUR, 2L, 39L));
        assertThat(run(rows, 3, "h", List.of(), List.of())).containsExactly(
                row(H22 - HOUR, 7L, 28L),
                row(H22 + 2 * HOUR, 11L, 143L),
                row(H22 + 5 * HOUR, 2L, 39L));
    }

    /// QuestDB: `SELECT ts, k, count(), last(x) FROM (SELECT timestamp_sequence(1700000000000000, 1000000000) ts,
    /// x % 2 k, x FROM long_sequence(20)) timestamp(ts) SAMPLE BY 2h`: the keys of a bucket in first-seen order.
    @Test
    void keyedBucketsMatchQuestDb() {
        final List<List<Object>> rows = sequence(1_000_000_000L, 20);
        assertThat(run(rows, 2, "h", List.of(K), List.of())).containsExactly(
                row(H22, 1L, 4L, 7L),
                row(H22, 0L, 3L, 6L),
                row(H22 + 2 * HOUR, 0L, 4L, 14L),
                row(H22 + 2 * HOUR, 1L, 3L, 13L),
                row(H22 + 4 * HOUR, 1L, 3L, 19L),
                row(H22 + 4 * HOUR, 0L, 3L, 20L));
    }

    /// QuestDB: `SELECT ts, count(), sum(x) FROM (SELECT timestamp_sequence(1700000000000000, 10000000000) ts, x
    /// FROM long_sequence(6)) timestamp(ts) WHERE x != 3 AND x != 4 SAMPLE BY 1h FILL(PREV)`, then `FILL(LINEAR)`
    /// (truncated by bucket ordinal), then `FILL(NULL)` (count NULL too).
    @Test
    void fillsMatchQuestDb() {
        final List<List<Object>> rows = gaps();
        assertThat(column(run(rows, 1, "h", List.of(), List.of("PREV")), 2))
                .containsExactly(1L, 1L, 1L, 2L, 2L, 5L, 5L, 5L, 6L);
        assertThat(column(run(rows, 1, "h", List.of(), List.of("PREV")), 1))
                .containsExactly(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L);
        assertThat(column(run(rows, 1, "h", List.of(), List.of("LINEAR")), 2))
                .containsExactly(1L, 1L, 1L, 2L, 3L, 5L, 5L, 5L, 6L);
        final List<List<Object>> nulls = run(rows, 1, "h", List.of(), List.of("NULL"));
        assertThat(column(nulls, 0)).containsExactly(H22, H22 + HOUR, H22 + 2 * HOUR, H22 + 3 * HOUR,
                H22 + 4 * HOUR, H22 + 5 * HOUR, H22 + 6 * HOUR, H22 + 7 * HOUR, H22 + 8 * HOUR);
        assertThat(column(nulls, 1)).containsExactly(1L, null, null, 1L, null, 1L, null, null, 1L);
        assertThat(column(nulls, 2)).containsExactly(1L, null, null, 2L, null, 5L, null, null, 6L);
    }

    /// QuestDB: `SELECT ts, k, sum(x) FROM (SELECT timestamp_sequence(1700000000000000, 10000000000) ts, x % 2 k, x
    /// FROM long_sequence(6)) timestamp(ts) WHERE x != 3 AND x != 4 SAMPLE BY 1h FILL(NULL)`: every bucket holds every
    /// key, in first-seen order over the whole input.
    @Test
    void keyedFillHoldsEveryKeyInEveryBucket() {
        final List<List<Object>> out = run(gaps(), 1, "h", List.of(K), List.of("NULL"));
        assertThat(out).hasSize(18);
        assertThat(column(out, 1)).containsExactly(1L, 0L, 1L, 0L, 1L, 0L, 1L, 0L, 1L, 0L, 1L, 0L, 1L, 0L, 1L, 0L,
                1L, 0L);
        assertThat(column(out, 2)).containsExactly(1L, null, null, null, null, null, null, 2L, null, null, 5L, null,
                null, null, null, null, null, 6L);
    }

    /// A number fills every empty bucket with itself; one fill per aggregate applies each to its own.
    @Test
    void aValueAndAFillPerAggregate() {
        final List<List<Object>> out = run(gaps(), 1, "h", List.of(), List.of("0", "PREV"));
        assertThat(column(out, 1)).containsExactly(1L, 0L, 0L, 1L, 0L, 1L, 0L, 0L, 1L);
        assertThat(column(out, 2)).containsExactly(1L, 1L, 1L, 2L, 2L, 5L, 5L, 5L, 6L);
    }

    /// FILL values that match neither one aggregate nor all, or an unknown unit, fail and close the child.
    @Test
    void badFillsAndUnitsFail() {
        final AggRows.Source source = AggRows.source(fields(), 8, List.of());
        assertThatThrownBy(() -> new SampleByCursor(source, spec(1, "h", List.of(), List.of("NULL", "PREV", "NULL")),
                0)).isInstanceOf(CdbException.class);
        assertThat(source.closed()).isTrue();
        assertThatThrownBy(() -> new SampleByCursor(AggRows.source(fields(), 8, List.of()),
                spec(1, "w", List.of(), List.of()), 0)).isInstanceOf(CdbException.class);
    }

    /// Rows with a NULL timestamp fall in no bucket.
    @Test
    void nullTimestampsAreDropped() {
        final List<List<Object>> rows = List.of(row(null, 5L, 1L), row(START, 1L, 1L), row(null, 7L, 1L));
        assertThat(run(rows, 1, "h", List.of(), List.of())).containsExactly(row(H22, 1L, 1L));
    }

    private static List<List<Object>> sequence(final long stepMicros, final int n) {
        final List<List<Object>> rows = new ArrayList<>();
        for (long x = 1; x <= n; x++) {
            rows.add(row(START + (x - 1) * stepMicros, x, x % 2));
        }
        return rows;
    }

    // timestamp_sequence(1700000000000000, 10000000000) over the rows x != 3 AND x != 4 of long_sequence(6)
    private static List<List<Object>> gaps() {
        final long step = 10_000_000_000L;
        return List.of(row(START, 1L, 1L), row(START + step, 2L, 0L), row(START + 2 * step, 5L, 1L),
                row(START + 3 * step, 6L, 0L));
    }

    private static List<Object> column(final List<List<Object>> rows, final int column) {
        final List<Object> values = new ArrayList<>();
        for (final List<Object> row : rows) {
            values.add(row.get(column));
        }
        return values;
    }

    private static List<Field> fields() {
        return List.of(field("ts", CdbType.TIMESTAMP), field("x", CdbType.LONG), field("k", CdbType.LONG));
    }

    private static List<List<Object>> run(
            final List<List<Object>> rows,
            final long count,
            final String unit,
            final List<Bound> keys,
            final List<String> fill
    ) {
        return AggRows.drain(new SampleByCursor(AggRows.source(fields(), 3, rows), spec(count, unit, keys, fill), 0));
    }

    private static Plan.SampleBy spec(
            final long count,
            final String unit,
            final List<Bound> keys,
            final List<String> fill
    ) {
        final List<Bound.Agg> aggs = keys.isEmpty()
                ? List.of(new Bound.Agg("count", List.of(), false, CdbType.LONG),
                        new Bound.Agg("sum", List.of(X), false, CdbType.LONG))
                : fill.isEmpty()
                        ? List.of(new Bound.Agg("count", List.of(), false, CdbType.LONG),
                                new Bound.Agg("last", List.of(X), false, CdbType.LONG))
                        : List.of(new Bound.Agg("sum", List.of(X), false, CdbType.LONG));
        final List<Field> out = new ArrayList<>();
        out.add(field("ts", CdbType.TIMESTAMP));
        for (final Bound key : keys) {
            out.add(field("k", key.type()));
        }
        for (final Bound.Agg agg : aggs) {
            out.add(field(agg.function(), agg.type()));
        }
        return new Plan.SampleBy(null, TS_COLUMN, count, unit, keys, aggs, fill, out);
    }
}
