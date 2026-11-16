package dev.marregui.comando.cdb.exec.agg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Every accumulator answers the same, bit for bit, whether its rows arrive one at a time ([Accumulator#add]), a page
/// at a time ([Accumulator#addAll]) or as runs of one group ([Accumulator#addRange]); the percentiles also answer as
/// the copy-and-sort algorithm the in-place selection replaced, kept here as the reference. Each trial spreads random
/// runs over 1,200 groups, at least 1,000 of them filled, skewed so some groups stay empty, some hold one value and
/// some thousands, with NULLs, NaN, -0.0 and infinities among the values. Seed in the output; replay with `-Dcdb.agg.seed=<seed>`.
class AccumulatorDifferentialTest {

    private static final int GROUPS = 1_200;
    // groups given runs of rows; the next SINGLE_GROUPS get one row each, the rest none
    private static final int HEAVY_GROUPS = 1_100;
    private static final int SINGLE_GROUPS = 50;
    private static final long SEED = Long.getLong("cdb.agg.seed", System.nanoTime());
    private static final double[] FRACTIONS = {0.0, 1.0, 0.5, 0.95, 0.01};

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// `count()` and `count(x)` of every type.
    @Test
    void countsMatchRowByRow() {
        same(() -> new CountAccumulator(true), CdbType.LONG, CdbType.LONG);
        for (final CdbType type : CdbType.values()) {
            same(() -> new CountAccumulator(false), type, CdbType.LONG);
        }
    }

    /// `sum` of LONG and of DOUBLE, whose additions must run in the same order to round alike.
    @Test
    void sumsMatchRowByRow() {
        same(LongSumAccumulator::new, CdbType.LONG, CdbType.LONG);
        same(DoubleSumAccumulator::new, CdbType.DOUBLE, CdbType.DOUBLE);
    }

    /// `avg` of LONG and of DOUBLE.
    @Test
    void averagesMatchRowByRow() {
        same(AvgAccumulator::new, CdbType.LONG, CdbType.DOUBLE);
        same(AvgAccumulator::new, CdbType.DOUBLE, CdbType.DOUBLE);
    }

    /// `min` and `max` of LONG and of DOUBLE: of -0.0 and 0.0 the first seen stays.
    @Test
    void minAndMaxMatchRowByRow() {
        for (final boolean max : new boolean[] {false, true}) {
            same(() -> new LongMinMaxAccumulator(max), CdbType.LONG, CdbType.LONG);
            same(() -> new DoubleMinMaxAccumulator(max), CdbType.DOUBLE, CdbType.DOUBLE);
        }
    }

    /// `first` and `last` of every type, NULL values kept.
    @Test
    void firstAndLastMatchRowByRow() {
        for (final CdbType type : CdbType.values()) {
            same(() -> new FirstLastAccumulator(type, false), type, type);
            same(() -> new FirstLastAccumulator(type, true), type, type);
        }
    }

    /// The accumulators that keep the default range loop: `count_distinct` and text `min`, `max`.
    @Test
    void defaultRangesMatchRowByRow() {
        same(LongCountDistinctAccumulator::new, CdbType.LONG, CdbType.LONG);
        same(TextCountDistinctAccumulator::new, CdbType.VARCHAR, CdbType.LONG);
        same(() -> new TextMinMaxAccumulator(false), CdbType.VARCHAR, CdbType.VARCHAR);
        same(() -> new TextMinMaxAccumulator(true), CdbType.VARCHAR, CdbType.VARCHAR);
    }

    /// `percentile_cont` of DOUBLE and of LONG against copy, sort and interpolate.
    @Test
    void continuousPercentileMatchesTheSort() {
        for (final double p : fractions()) {
            differ(() -> new ContinuousPercentileAccumulator(p), () -> new SortedPercentile(Kind.CONTINUOUS, p, -1),
                    CdbType.DOUBLE, CdbType.DOUBLE, false);
            differ(() -> new ContinuousPercentileAccumulator(p), () -> new SortedPercentile(Kind.CONTINUOUS, p, -1),
                    CdbType.LONG, CdbType.DOUBLE, false);
        }
    }

    /// The DOUBLE percentile (`percentile_disc`, `approx_percentile`) against copy, sort and index.
    @Test
    void doublePercentileMatchesTheSort() {
        for (final double p : fractions()) {
            differ(() -> new DoublePercentileAccumulator(p), () -> new SortedPercentile(Kind.DOUBLE, p, -1),
                    CdbType.DOUBLE, CdbType.DOUBLE, false);
        }
    }

    /// The exact LONG percentile (`percentile_disc`) against copy, sort and index.
    @Test
    void exactLongPercentileMatchesTheSort() {
        for (final double p : fractions()) {
            differ(() -> new LongPercentileAccumulator(p, -1), () -> new SortedPercentile(Kind.LONG, p, -1),
                    CdbType.LONG, CdbType.LONG, false);
        }
    }

    /// The histogram LONG percentile (`approx_percentile`, `approx_median`) at every precision against the sort, on
    /// values of 0 or more.
    @Test
    void approximateLongPercentileMatchesTheSort() {
        for (int precision = 0; precision <= 5; precision++) {
            final int digits = precision;
            for (final double p : fractions()) {
                differ(() -> new LongPercentileAccumulator(p, digits), () -> new SortedPercentile(Kind.LONG, p,
                        digits), CdbType.LONG, CdbType.LONG, true);
            }
        }
    }

    /// The histogram percentile refuses a negative value wherever it sits among a group's values.
    @Test
    void approximatePercentileRefusesANegativeValue() {
        final Vector in = Vector.of(CdbType.LONG, 64);
        for (int i = 0; i < in.longs.length; i++) {
            in.longs[i] = 1_000 + random.nextInt(1_000);
        }
        in.longs[random.nextInt(in.longs.length)] = -7;
        final LongPercentileAccumulator accumulator = new LongPercentileAccumulator(0.5, 2);
        accumulator.ensure(1);
        accumulator.addRange(0, in, 0, in.longs.length);
        assertThatThrownBy(() -> accumulator.write(0, Vector.of(CdbType.LONG, 1), 0))
                .isInstanceOf(CdbException.class);
    }

    /// Selection gives the sorted array's value at every rank, -0.0 before 0.0, and leaves nothing greater before the
    /// rank and nothing smaller after it, on sorted, reversed, constant, two-valued, organ-pipe and random inputs.
    @Test
    void selectionMatchesTheSortAtEveryRank() {
        for (final double[] input : shapes()) {
            final double[] sorted = input.clone();
            Arrays.sort(sorted);
            final long[] longs = new long[input.length];
            for (int i = 0; i < input.length; i++) {
                longs[i] = (long) (input[i] * 1_000);
            }
            final long[] sortedLongs = longs.clone();
            Arrays.sort(sortedLongs);
            for (int k = 0; k < input.length; k++) {
                final double[] a = input.clone();
                final double kth = Select.kthDouble(a, a.length, k);
                assertThat(Double.doubleToRawLongBits(kth)).isEqualTo(Double.doubleToRawLongBits(sorted[k]));
                for (int i = 0; i < a.length; i++) {
                    assertThat(Integer.signum(Double.compare(a[i], kth))).isNotEqualTo(i < k ? 1 : i > k ? -1 : 2);
                }
                final long[] b = longs.clone();
                assertThat(Select.kthLong(b, b.length, k)).isEqualTo(sortedLongs[k]);
            }
        }
    }

    /// A group's percentile written twice answers alike: a FILL writes a bucket's value again for each empty one.
    @Test
    void aPercentileWrittenAgainAnswersAlike() {
        final ContinuousPercentileAccumulator accumulator = new ContinuousPercentileAccumulator(0.95);
        final Input input = input(CdbType.DOUBLE, false);
        feed(accumulator, input, true);
        final Vector first = written(accumulator, CdbType.DOUBLE);
        final Vector second = written(accumulator, CdbType.DOUBLE);
        for (int g = 0; g < GROUPS; g++) {
            assertThat(Double.doubleToRawLongBits(second.doubles[g])).isEqualTo(
                    Double.doubleToRawLongBits(first.doubles[g]));
        }
    }

    // 0, 1, between, and a random one per run
    private double[] fractions() {
        final double[] all = Arrays.copyOf(FRACTIONS, FRACTIONS.length + 1);
        all[FRACTIONS.length] = random.nextDouble();
        return all;
    }

    // one accumulator fed row by row against another fed by pages and runs: the same answer for every group
    private void same(final Supplier<Accumulator> create, final CdbType in, final CdbType out) {
        differ(create, create, in, out, false);
    }

    private void differ(
            final Supplier<Accumulator> create,
            final Supplier<Accumulator> reference,
            final CdbType in,
            final CdbType out,
            final boolean nonNegative
    ) {
        final Input input = input(in, nonNegative);
        final Accumulator subject = create.get();
        final Accumulator expected = reference.get();
        feed(subject, input, true);
        feed(expected, input, false);
        final Vector got = written(subject, out);
        final Vector want = written(expected, out);
        int filled = 0;
        int single = 0;
        for (int g = 0; g < GROUPS; g++) {
            filled += input.sizes[g] > 0 ? 1 : 0;
            single += input.sizes[g] == 1 ? 1 : 0;
            assertThat(cell(got, g)).as("group %d of %s over %s", g, subject.getClass().getSimpleName(), in)
                    .isEqualTo(cell(want, g));
        }
        assertThat(filled).isGreaterThanOrEqualTo(1_000).isLessThan(GROUPS);
        assertThat(single).isGreaterThanOrEqualTo(SINGLE_GROUPS);
    }

    private static Object cell(final Vector v, final int row) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> v.longs[row];
            case DOUBLE -> Double.doubleToRawLongBits(v.doubles[row]);
            case BOOLEAN -> v.booleans[row];
            case SYMBOL, VARCHAR -> String.valueOf(v.texts[row]);
        };
    }

    private static Vector written(final Accumulator accumulator, final CdbType type) {
        final Vector out = Vector.of(type, GROUPS);
        for (int g = 0; g < GROUPS; g++) {
            accumulator.write(g, out, g);
        }
        return out;
    }

    // rows: `add` one at a time; else per page, a page whole, its runs as ranges, or runs split between both
    private void feed(final Accumulator accumulator, final Input input, final boolean mixed) {
        int groups = 0;
        for (int p = 0; p < input.pages.size(); p++) {
            final Vector value = input.pages.get(p);
            final int[] group = input.groups.get(p);
            final int size = input.pageSizes.get(p);
            for (int i = 0; i < size; i++) {
                groups = Math.max(groups, group[i] + 1);
            }
            accumulator.ensure(groups);
            final int mode = mixed ? random.nextInt(3) : -1;
            if (mode == 0) {
                accumulator.addAll(group, value, size);
                continue;
            }
            int start = 0;
            while (start < size) {
                int end = start + 1;
                while (end < size && group[end] == group[start]) {
                    end++;
                }
                if (mode == 1 || mode == 2 && random.nextBoolean()) {
                    accumulator.addRange(group[start], value, start, end);
                } else {
                    for (int i = start; i < end; i++) {
                        accumulator.add(group[i], value, i);
                    }
                }
                start = end;
            }
        }
        accumulator.ensure(GROUPS);
    }

    // runs of 1 to 300 rows over the first 1,100 groups, squared so low groups are heavy, then one row for each of
    // the next 50 groups and none for the last 50; laid over pages of 1 to 1,024 rows, so runs cross pages
    private Input input(final CdbType type, final boolean nonNegative) {
        final List<int[]> runs = new ArrayList<>();
        for (int rows = 0; rows < 200_000;) {
            final double r = random.nextDouble();
            final int length = 1 + random.nextInt(new int[] {1, 3, 20, 300}[random.nextInt(4)]);
            runs.add(new int[] {(int) (HEAVY_GROUPS * r * r), length});
            rows += length;
        }
        for (int g = HEAVY_GROUPS; g < HEAVY_GROUPS + SINGLE_GROUPS; g++) {
            runs.add(new int[] {g, 1});
        }
        Collections.shuffle(runs, random);
        final Input input = new Input();
        int run = 0;
        int remainingInRun = runs.getFirst()[1];
        while (run < runs.size()) {
            final int size = 1 + random.nextInt(Page.CAPACITY);
            final Vector value = Vector.of(type, size);
            final int[] group = new int[size];
            int i = 0;
            for (; i < size && run < runs.size(); i++) {
                group[i] = runs.get(run)[0];
                input.sizes[group[i]]++;
                set(value, i, nonNegative);
                if (--remainingInRun == 0 && ++run < runs.size()) {
                    remainingInRun = runs.get(run)[1];
                }
            }
            input.pages.add(value);
            input.groups.add(group);
            input.pageSizes.add(i);
        }
        return input;
    }

    private void set(final Vector v, final int i, final boolean nonNegative) {
        final int pick = random.nextInt(100);
        switch (v.type) {
            case LONG, TIMESTAMP -> v.longs[i] = pick < 10
                    ? Vector.NULL_LONG
                    : pick < 50 ? random.nextInt(20) : nonNegative ? random.nextLong(1L << 40) : random.nextLong();
            case DOUBLE -> v.doubles[i] = switch (pick) {
                case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 -> Double.NaN;
                case 10, 11, 12, 13, 14 -> -0.0;
                case 15, 16, 17, 18, 19 -> 0.0;
                case 20 -> Double.POSITIVE_INFINITY;
                case 21 -> Double.NEGATIVE_INFINITY;
                case 22 -> Double.MIN_VALUE;
                default -> pick < 60 ? random.nextInt(20) / 4.0 : random.nextGaussian() * 1e6;
            };
            case BOOLEAN -> v.booleans[i] = random.nextBoolean();
            case SYMBOL, VARCHAR -> v.texts[i] = pick < 10 ? null : "s" + random.nextInt(50);
        }
    }

    private List<double[]> shapes() {
        final List<double[]> shapes = new ArrayList<>();
        for (final int n : new int[] {1, 2, 3, 15, 16, 17, 100, 333}) {
            final double[] ascending = new double[n];
            final double[] descending = new double[n];
            final double[] constant = new double[n];
            final double[] zeros = new double[n];
            final double[] organ = new double[n];
            final double[] noise = new double[n];
            for (int i = 0; i < n; i++) {
                ascending[i] = i;
                descending[i] = n - i;
                constant[i] = 3.5;
                zeros[i] = random.nextBoolean() ? -0.0 : 0.0;
                organ[i] = Math.min(i, n - 1 - i);
                noise[i] = random.nextInt(7) - 3 + (random.nextBoolean() ? -0.0 : 0.0);
            }
            shapes.addAll(List.of(ascending, descending, constant, zeros, organ, noise));
        }
        return shapes;
    }

    /// Which copy-and-sort reference a [SortedPercentile] is.
    private enum Kind {
        CONTINUOUS,
        DOUBLE,
        LONG
    }

    /// Pages of one value column, the group of each row, and the number of rows per group.
    private static final class Input {

        private final List<int[]> groups = new ArrayList<>();
        private final List<Integer> pageSizes = new ArrayList<>();
        private final List<Vector> pages = new ArrayList<>();
        private final int[] sizes = new int[GROUPS];
    }

    /// The percentiles as they were answered before the in-place selection: each group's values copied and sorted.
    private static final class SortedPercentile implements Accumulator {

        private final double fraction;
        private final Kind kind;
        private final int precision;
        private final List<List<Number>> values = new ArrayList<>();

        private SortedPercentile(final Kind kind, final double fraction, final int precision) {
            this.kind = kind;
            this.fraction = fraction;
            this.precision = precision;
        }

        @Override
        public void add(final int group, final Vector value, final int row) {
            if (value.doubles != null) {
                if (!Double.isNaN(value.doubles[row])) {
                    values.get(group).add(value.doubles[row]);
                }
            } else if (value.longs[row] != Vector.NULL_LONG) {
                values.get(group).add(kind == Kind.CONTINUOUS ? (Number) (double) value.longs[row]
                        : (Number) value.longs[row]);
            }
        }

        @Override
        public void addAll(final int[] groups, final Vector value, final int size) {
            for (int i = 0; i < size; i++) {
                add(groups[i], value, i);
            }
        }

        @Override
        public void ensure(final int groups) {
            while (values.size() < groups) {
                values.add(new ArrayList<>());
            }
        }

        @Override
        public void merge(final int group, final Accumulator from, final int fromGroup) {
            if (!(from instanceof final SortedPercentile other)) {
                throw new IllegalArgumentException("refuse merge of another kind [from=" + from + ']');
            }
            values.get(group).addAll(other.values.get(fromGroup));
        }

        @Override
        public void write(final int group, final Vector out, final int row) {
            final List<Number> list = values.get(group);
            final int n = list.size();
            if (kind == Kind.LONG) {
                if (n == 0) {
                    Aggregates.writeLong(Vector.NULL_LONG, out, row);
                    return;
                }
                final long[] sorted = new long[n];
                for (int i = 0; i < n; i++) {
                    sorted[i] = list.get(i).longValue();
                }
                Arrays.sort(sorted);
                if (precision < 0) {
                    Aggregates.writeLong(sorted[LongPercentileAccumulator.rank(fraction, n) - 1], out, row);
                    return;
                }
                final double percent = Math.min(Math.max(Math.nextAfter(fraction * 100.0, Double.NEGATIVE_INFINITY),
                        0.0), 100.0);
                final int k = (int) Math.max(1, Math.ceil(percent / 100.0 * n));
                final long kth = sorted[k - 1];
                Aggregates.writeLong(fraction == 0.0
                        ? LongPercentileAccumulator.lowestEquivalent(kth, precision)
                        : LongPercentileAccumulator.highestEquivalent(kth, precision), out, row);
                return;
            }
            if (n == 0) {
                Aggregates.writeDouble(Double.NaN, out, row);
                return;
            }
            final double[] sorted = new double[n];
            for (int i = 0; i < n; i++) {
                sorted[i] = list.get(i).doubleValue();
            }
            Arrays.sort(sorted);
            if (kind == Kind.DOUBLE) {
                Aggregates.writeDouble(sorted[LongPercentileAccumulator.rank(fraction, n) - 1], out, row);
                return;
            }
            final double position = fraction * (n - 1);
            final int lo = (int) Math.floor(position);
            final int hi = (int) Math.ceil(position);
            Aggregates.writeDouble(sorted[lo] + (position - lo) * (sorted[hi] - sorted[lo]), out, row);
        }
    }
}
