package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.plan.Bound;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.Compare;
import dev.marregui.comando.cdb.store.PartitionReader;

import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static dev.marregui.comando.cdb.exec.RowsFakes.partition;
import static dev.marregui.comando.cdb.exec.RowsFakes.range;
import static dev.marregui.comando.cdb.exec.RowsFakes.table;
import static org.assertj.core.api.Assertions.assertThat;

/// [ScanCursor] reads only the rows of each partition inside `[from, to)`, keeps only the rows its terms may keep (a
/// NULL always, the filter above decides), and with `newestFirst` gives exactly the time-order rows in reverse.
class ScanRangeTest {

    /// The scan's columns: the table's `ts, x, d, s`.
    private static final int[] ALL = {0, 1, 2, 3};
    private static final List<Field> FIELDS = List.of(field("ts", CdbType.TIMESTAMP), field("x", CdbType.LONG),
            field("d", CdbType.DOUBLE), field("s", CdbType.SYMBOL));
    private static final List<Field> X = List.of(field("x", CdbType.LONG));
    private static final Compare[] OPS = Compare.values();

    private static Plan.Scan scan(final long from, final long to, final List<Plan.ScanTerm> terms, final boolean newest) {
        return new Plan.Scan("t", ALL, from, to, terms, newest, FIELDS);
    }

    private static List<Long> xs(final List<PartitionReader> parts, final Plan.Scan scan) {
        final List<Long> out = new ArrayList<>();
        for (final List<Object> row : drain(new ScanCursor(scan, new RowsFakes.FakeReader(table(), parts))).rows()) {
            out.add((Long) row.get(1));
        }
        return out;
    }

    private static List<Long> xs(final List<PartitionReader> parts, final long from, final long to) {
        return drain(new ScanCursor(new Plan.Scan("t", new int[] {1}, from, to, List.of(), false, X),
                new RowsFakes.FakeReader(table(), parts))).longs();
    }

    /// A partition of the table `ts, x, d, s` over the given timestamps, x numbering the rows from `firstX`.
    private static RowsFakes.FakePartition rows(final long firstX, final long[] ts, final double[] d, final String[] s) {
        final long[] x = new long[ts.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = firstX + i;
        }
        return new RowsFakes.FakePartition(ts[0], ts[ts.length - 1], ts.length, new Object[] {ts, x, d, s});
    }

    private static RowsFakes.FakePartition rows(final long firstX, final long... ts) {
        return rows(firstX, ts, new double[ts.length], new String[ts.length]);
    }

    private static Plan.ScanTerm longTerm(final int column, final Compare op, final long value) {
        return new Plan.ScanTerm(column, op, value, Double.NaN, null);
    }

    private static Plan.ScanTerm doubleTerm(final Compare op, final double value) {
        return new Plan.ScanTerm(2, op, Long.MIN_VALUE, value, null);
    }

    private static Plan.ScanTerm textTerm(final Compare op, final String value) {
        return new Plan.ScanTerm(3, op, Long.MIN_VALUE, Double.NaN, value);
    }

    /// The rows every term keeps, as the kernels' contract keeps them: a NULL always for a numeric term, only for `!=`
    /// for a text one (text terms are exact), else the comparison's answer.
    private static boolean keeps(final List<Object> row, final List<Plan.ScanTerm> terms) {
        for (final Plan.ScanTerm t : terms) {
            final Object v = row.get(t.column());
            if (v == null) {
                if (t.textValue() != null && t.op() != Compare.NE) {
                    return false;
                }
                continue;
            }
            final boolean holds = switch (v) {
                case final Long l -> t.op().holds(Long.compare(l, t.longValue()));
                case final Double d -> t.op().holds(d < t.doubleValue() ? -1 : d > t.doubleValue() ? 1 : 0);
                case final String s -> t.op() == Compare.EQ ? s.equals(t.textValue()) : !s.equals(t.textValue());
                default -> throw new IllegalStateException("refuse value [value=" + v + ']');
            };
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    /// Every row of the partitions with a timestamp in `[from, to)` that the terms keep, in time order.
    private static List<List<Object>> expected(
            final List<PartitionReader> parts,
            final long from,
            final long to,
            final List<Plan.ScanTerm> terms
    ) {
        final List<List<Object>> out = new ArrayList<>();
        for (final List<Object> row : drain(new ScanCursor(scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(), false),
                new RowsFakes.FakeReader(table(), parts))).rows()) {
            final long ts = (Long) row.getFirst();
            if (ts >= from && ts < to && keeps(row, terms)) {
                out.add(row);
            }
        }
        return out;
    }

    /// Bounds on rows: the lower is inclusive, the upper exclusive, within a partition and across two.
    @Test
    void boundsOnRowsKeepOnlyTheRowsInside() {
        final List<PartitionReader> parts = List.of(partition(0, 10), partition(10, 10), partition(20, 10));
        assertThat(xs(parts, 19, 21)).isEqualTo(range(19, 21));
        assertThat(xs(parts, 10, 20)).isEqualTo(range(10, 20));
        assertThat(xs(parts, 3, 27)).isEqualTo(range(3, 27));
        assertThat(xs(parts, 5, 6)).containsExactly(5L);
    }

    /// Bounds between rows take the rows after the lower one and before the upper one; an empty range takes none.
    @Test
    void boundsBetweenRowsTakeTheRowsBetween() {
        final List<PartitionReader> parts = List.of(rows(0, 0, 2, 4, 6, 8), rows(5, 10, 12, 14));
        assertThat(xs(parts, 3, 8)).containsExactly(2L, 3L);
        assertThat(xs(parts, 7, 13)).containsExactly(4L, 5L, 6L);
        assertThat(xs(parts, 9, 10)).isEmpty();
        assertThat(xs(parts, 4, 4)).isEmpty();
    }

    /// Bounds off every row: before the first or after the last take nothing, around all take everything.
    @Test
    void boundsOffEveryRowTakeAllOrNothing() {
        final List<PartitionReader> parts = List.of(partition(0, 10), partition(10, 10));
        assertThat(xs(parts, -10, -1)).isEmpty();
        assertThat(xs(parts, 20, 40)).isEmpty();
        assertThat(xs(parts, -5, 5)).isEqualTo(range(0, 5));
        assertThat(xs(parts, 15, 400)).isEqualTo(range(15, 20));
        assertThat(xs(parts, Long.MIN_VALUE, Long.MAX_VALUE)).isEqualTo(range(0, 20));
    }

    /// Empty partitions are skipped, a one-row partition is in or out by its one timestamp, and the reader is
    /// closed once.
    @Test
    void emptyAndOneRowPartitions() {
        final List<PartitionReader> parts = List.of(partition(0, 0), partition(5, 1), partition(6, 0), partition(7, 1));
        assertThat(xs(parts, 5, 8)).containsExactly(5L, 7L);
        assertThat(xs(parts, 6, 7)).isEmpty();
        assertThat(xs(parts, 7, 8)).containsExactly(7L);
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), parts);
        assertThat(drain(new ScanCursor(scan(0, 100, List.of(), true), reader)).rows()).hasSize(2);
        assertThat(reader.closes()).isEqualTo(1);
    }

    /// A range inside a large partition comes out in full pages but the last.
    @Test
    void aRangeInsideALargePartitionFillsPages() {
        final RowsFakes.Drained out = drain(new ScanCursor(new Plan.Scan("t", new int[] {1}, 700, 4000, List.of(),
                false, X), new RowsFakes.FakeReader(table(), List.of(partition(0, 5000)))));
        assertThat(out.longs()).isEqualTo(range(700, 4000));
        assertThat(out.packed()).isTrue();
    }

    /// Newest first is the time-order scan in reverse: partitions from the newest, rows with equal timestamps in
    /// reverse too, across two partitions, with terms and under a filter.
    @Test
    void newestFirstIsTheExactReverse() {
        final List<PartitionReader> parts = List.of(rows(0, 1, 1, 2, 2), rows(4, 2, 2, 3));
        assertThat(xs(parts, scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(), true)))
                .containsExactly(6L, 5L, 4L, 3L, 2L, 1L, 0L);
        assertThat(xs(parts, scan(1, 3, List.of(longTerm(1, Compare.NE, 3)), true)))
                .containsExactly(5L, 4L, 2L, 1L, 0L);
        final Bound xAbove1 = new Bound.Call(">", List.of(new Bound.Column(1, CdbType.LONG),
                new Bound.Constant(1L, CdbType.LONG)), CdbType.BOOLEAN);
        final RowsFakes.Drained filtered = drain(new FilterCursor(new ScanCursor(scan(Long.MIN_VALUE, Long.MAX_VALUE,
                List.of(), true), new RowsFakes.FakeReader(table(), parts)), Functions.compile(xAbove1, 0)));
        final List<Long> x = new ArrayList<>();
        for (final List<Object> row : filtered.rows()) {
            x.add((Long) row.get(1));
        }
        assertThat(x).containsExactly(6L, 5L, 4L, 3L, 2L);
    }

    /// LONG and TIMESTAMP terms of every operator keep the rows the comparison keeps, and every NULL.
    @Test
    void longTermsOfEveryOperatorKeepNulls() {
        final long nul = Long.MIN_VALUE;
        final long[] ts = {0, 1, 2, 3, 4, 5};
        final long[] x = {-1, nul, 0, 1, nul, 2};
        final List<PartitionReader> parts = List.of(new RowsFakes.FakePartition(0, 5, 6,
                new Object[] {ts, x, new double[6], new String[6]}));
        for (final Compare op : OPS) {
            for (final long value : new long[] {-1, 0, 1, 3}) {
                final List<Plan.ScanTerm> terms = List.of(longTerm(1, op, value), longTerm(0, Compare.NE, 5));
                final Plan.Scan s = scan(Long.MIN_VALUE, Long.MAX_VALUE, terms, false);
                assertThat(drain(new ScanCursor(s, new RowsFakes.FakeReader(table(), parts))).rows())
                        .as("x %s %d", op, value).isEqualTo(expected(parts, Long.MIN_VALUE, Long.MAX_VALUE, terms));
            }
        }
    }

    /// DOUBLE terms of every operator keep the rows the comparison keeps by value (-0.0 below every positive value,
    /// above every negative one) and every NULL (NaN).
    @Test
    void doubleTermsOfEveryOperatorKeepNaN() {
        final double[] d = {-0.0, Double.NaN, 0.0, 1.5, -2.5, Double.NEGATIVE_INFINITY, 7.0};
        final long[] ts = {0, 1, 2, 3, 4, 5, 6};
        final List<PartitionReader> parts = List.of(rows(0, ts, d, new String[ts.length]));
        for (final Compare op : OPS) {
            for (final double value : new double[] {-2.5, -1.0, 1.5, 7.0, Double.POSITIVE_INFINITY}) {
                final List<Plan.ScanTerm> terms = List.of(doubleTerm(op, value));
                final Plan.Scan s = scan(Long.MIN_VALUE, Long.MAX_VALUE, terms, false);
                assertThat(drain(new ScanCursor(s, new RowsFakes.FakeReader(table(), parts))).rows())
                        .as("d %s %s", op, value).isEqualTo(expected(parts, Long.MIN_VALUE, Long.MAX_VALUE, terms));
            }
        }
    }

    /// SYMBOL terms are exact: `=` keeps the matching rows, `!=` the others and every NULL; an absent symbol matches no
    /// row.
    @Test
    void textTermsAreExactAndAbsentSymbolsMatchNothing() {
        final long[] ts = {0, 1, 2, 3, 4};
        final String[] s = {"a", null, "b", "a", ""};
        final List<PartitionReader> parts = List.of(rows(0, ts, new double[ts.length], s));
        assertThat(xs(parts, scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(textTerm(Compare.EQ, "a")), false)))
                .containsExactly(0L, 3L);
        assertThat(xs(parts, scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(textTerm(Compare.NE, "a")), false)))
                .containsExactly(1L, 2L, 4L);
        assertThat(xs(parts, scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(textTerm(Compare.EQ, "zz")), false)))
                .isEmpty();
        assertThat(xs(parts, scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(textTerm(Compare.NE, "zz")), true)))
                .containsExactly(4L, 3L, 2L, 1L, 0L);
    }

    /// Terms intersect across 1,024-row chunks and partition ends, the rows a full page leaves of a chunk come in the
    /// next page, and pages are full but the last, in either direction.
    @Test
    void termsIntersectAcrossChunksAndPages() {
        final List<PartitionReader> parts = List.of(partition(0, 1500), partition(1500, 1500), partition(3000, 100));
        final List<Plan.ScanTerm> terms = List.of(longTerm(1, Compare.GE, 100), longTerm(1, Compare.LT, 3050),
                textTerm(Compare.NE, "s3"));
        final List<List<Object>> want = expected(parts, 50, 3090, terms);
        final RowsFakes.Drained forward = drain(new ScanCursor(scan(50, 3090, terms, false),
                new RowsFakes.FakeReader(table(), parts)));
        assertThat(forward.rows()).isEqualTo(want);
        assertThat(forward.packed()).isTrue();
        final RowsFakes.Drained backward = drain(new ScanCursor(scan(50, 3090, terms, true),
                new RowsFakes.FakeReader(table(), parts)));
        assertThat(backward.rows()).isEqualTo(want.reversed());
        assertThat(backward.packed()).isTrue();
    }

    /// Random partitions (equal timestamps, gaps, NULLs, empty partitions), ranges and terms: the scan gives the
    /// oracle's rows, and newest first their reverse. Seed in the output; replay with `-Dcdb.scan.seed=<seed>`.
    @Test
    void randomScansMatchTheOracle() {
        final long seed = Long.getLong("cdb.scan.seed", System.nanoTime());
        System.out.println("randomScansMatchTheOracle seed=" + seed);
        final Random random = new Random(seed);
        for (int round = 0; round < 300; round++) {
            final List<PartitionReader> parts = new ArrayList<>();
            long t = random.nextInt(5);
            long x = 0;
            for (int p = random.nextInt(4); p >= 0; p--) {
                final int n = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(random.nextBoolean() ? 8 : 2500);
                if (n == 0) {
                    parts.add(partition(t, 0));
                    continue;
                }
                final long[] ts = new long[n];
                final double[] d = new double[n];
                final String[] s = new String[n];
                for (int i = 0; i < n; i++) {
                    t += random.nextInt(3);
                    ts[i] = t;
                    d[i] = random.nextInt(6) == 0 ? Double.NaN : random.nextInt(9) - 4 + (random.nextBoolean() ? 0.5 : 0);
                    s[i] = random.nextInt(5) == 0 ? null : "s" + random.nextInt(4);
                }
                parts.add(rows(x, ts, d, s));
                x += n;
                t += 1 + random.nextInt(3);
            }
            final List<Plan.ScanTerm> terms = new ArrayList<>();
            for (int k = random.nextInt(4); k > 0; k--) {
                final Compare op = OPS[random.nextInt(OPS.length)];
                terms.add(switch (random.nextInt(3)) {
                    case 0 -> longTerm(1, op, random.nextLong(x + 1));
                    case 1 -> doubleTerm(op, random.nextInt(9) - 4 + 0.5);
                    default -> textTerm(random.nextBoolean() ? Compare.EQ : Compare.NE, "s" + random.nextInt(5));
                });
            }
            final long from = random.nextBoolean() ? Long.MIN_VALUE : random.nextLong(t + 1);
            final long to = random.nextBoolean() ? Long.MAX_VALUE
                    : (from == Long.MIN_VALUE ? 0 : from) + random.nextLong(t + 2);
            final List<List<Object>> want = expected(parts, from, to, terms);
            final List<List<Object>> forward = drain(new ScanCursor(scan(from, to, terms, false),
                    new RowsFakes.FakeReader(table(), parts))).rows();
            final List<List<Object>> backward = drain(new ScanCursor(scan(from, to, terms, true),
                    new RowsFakes.FakeReader(table(), parts))).rows();
            assertThat(forward).as("seed=%d round=%d", seed, round).isEqualTo(want);
            assertThat(backward).as("seed=%d round=%d", seed, round).isEqualTo(want.reversed());
        }
    }
}
