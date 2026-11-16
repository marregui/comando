package dev.marregui.comando.cdb.exec.agg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;

import static org.assertj.core.api.Assertions.assertThat;

/// [Accumulator#merge] of every accumulator over every value type it takes: the rows cut into frames, one accumulator
/// per frame with its groups numbered in the frame's first-seen order, merged in frame order into an empty
/// accumulator or into the first frame's, answer as one accumulator fed every row, bit for bit. `sum` and `avg` of
/// DOUBLE answer as the frames' sums added in frame order instead, the order a parallel query adds them in. Values
/// hold NULLs, NaN, -0.0 and infinities; frames may be empty; groups are first seen in later frames. Seed in the
/// output; replay with `-Dcdb.agg.seed=<seed>`.
class AccumulatorMergeTest {

    private static final long SEED = Long.getLong("cdb.agg.seed", System.nanoTime());
    // short enough that every cut into 3 frames runs for every case
    private static final int SHORT_ROWS = 30;
    private static final int SHORT_GROUPS = 5;
    private static final int LONG_ROWS = 20_000;
    private static final int LONG_GROUPS = 300;
    private static final int TRIALS = 3;
    private static final double[] FRACTIONS = {0.0, 1.0, 0.5, 0.95};

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// Every cut of a short input into 2 frames and into 3, empty frames included, for every case.
    @Test
    void everyCutIntoTwoOrThreeFramesMergesToTheSerialResult() {
        for (final Case c : cases()) {
            final Input input = input(c, SHORT_ROWS, SHORT_GROUPS, false);
            final Vector serial = serial(c, input);
            for (int a = 0; a <= input.rows; a++) {
                check(c, input, serial, new int[] {0, a, input.rows});
                for (int b = a; b <= input.rows; b++) {
                    check(c, input, serial, new int[] {0, a, b, input.rows});
                }
            }
        }
    }

    /// Seeded random cuts of a long input into 2 to 9 frames, some of them empty, for every case; each frame's
    /// accumulator answers after the merge as it did before it.
    @Test
    void randomCutsMergeToTheSerialResult() {
        for (final Case c : cases()) {
            final Input input = input(c, LONG_ROWS, LONG_GROUPS, false);
            final Vector serial = serial(c, input);
            for (int t = 0; t < TRIALS; t++) {
                final int frames = 2 + random.nextInt(8);
                final int[] cuts = new int[frames + 1];
                for (int f = 1; f < frames; f++) {
                    // a few cuts repeat a neighbour's, which leaves a frame empty
                    cuts[f] = random.nextInt(8) == 0 ? 0 : random.nextInt(input.rows + 1);
                }
                cuts[frames] = input.rows;
                Arrays.sort(cuts);
                check(c, input, serial, cuts);
            }
        }
    }

    /// An input of NULLs only (BOOLEAN, which has none, of random values) merges to the serial result: counts 0, the
    /// rest NULL, `first` and `last` the NULL of their row.
    @Test
    void nullsOnlyMergeToTheSerialResult() {
        for (final Case c : cases()) {
            final Input input = input(c, SHORT_ROWS, SHORT_GROUPS, true);
            final Vector serial = serial(c, input);
            for (int a = 0; a <= input.rows; a++) {
                check(c, input, serial, new int[] {0, a, input.rows});
            }
        }
    }

    /// An accumulator merged from, then fed the rest of the rows, merges all of them the next time: what a merge
    /// builds on its source to read it is not kept past the source's next row.
    @Test
    void aSourceFedAfterAMergeMergesItsNewRows() {
        for (final Case c : cases()) {
            final Input input = input(c, LONG_ROWS, LONG_GROUPS, false);
            final Vector serial = serial(c, input);
            final int cut = random.nextInt(input.rows + 1);
            // the first rows' groups are numbered as the whole input's
            final Frame head = frame(c, input, 0, cut);
            final Accumulator source = head.accumulator;
            final Accumulator first = c.create.get();
            first.ensure(head.groups);
            for (int g = 0; g < head.groups; g++) {
                first.merge(g, source, g);
            }
            feed(source, c.kind == Kind.ROWS ? null : input.values, cut,
                    Arrays.copyOfRange(input.groupOfRow, cut, input.rows), input.groups);
            final Accumulator second = c.create.get();
            second.ensure(input.groups);
            for (int g = 0; g < input.groups; g++) {
                second.merge(g, source, g);
            }
            // the source added every row in row order, so even a double sum is the serial one
            final Vector got = written(second, c.out, input.groups);
            for (int g = 0; g < input.groups; g++) {
                assertThat(cell(got, g)).as("group %d of %s over %s, cut %d", g, c.name, c.in, cut)
                        .isEqualTo(cell(serial, g));
            }
        }
    }

    // every accumulator of Aggregates, over every value type it takes
    private List<Case> cases() {
        final List<Case> cases = new ArrayList<>();
        cases.add(new Case("count()", () -> new CountAccumulator(true), CdbType.LONG, CdbType.LONG, Kind.ROWS));
        for (final CdbType type : CdbType.values()) {
            cases.add(new Case("count", () -> new CountAccumulator(false), type, CdbType.LONG, Kind.EXACT));
            for (final boolean last : new boolean[] {false, true}) {
                cases.add(new Case(last ? "last" : "first", () -> new FirstLastAccumulator(type, last), type, type,
                        Kind.EXACT));
            }
        }
        for (final CdbType type : new CdbType[] {CdbType.LONG, CdbType.TIMESTAMP}) {
            cases.add(new Case("sum", LongSumAccumulator::new, type, CdbType.LONG, Kind.EXACT));
            cases.add(new Case("avg", AvgAccumulator::new, type, CdbType.DOUBLE, Kind.AVG));
            for (final boolean max : new boolean[] {false, true}) {
                cases.add(new Case(max ? "max" : "min", () -> new LongMinMaxAccumulator(max), type, type, Kind.EXACT));
            }
        }
        cases.add(new Case("sum", DoubleSumAccumulator::new, CdbType.DOUBLE, CdbType.DOUBLE, Kind.SUM));
        cases.add(new Case("avg", AvgAccumulator::new, CdbType.DOUBLE, CdbType.DOUBLE, Kind.AVG));
        for (final boolean max : new boolean[] {false, true}) {
            final String name = max ? "max" : "min";
            cases.add(new Case(name, () -> new DoubleMinMaxAccumulator(max), CdbType.DOUBLE, CdbType.DOUBLE,
                    Kind.EXACT));
            for (final CdbType type : new CdbType[] {CdbType.SYMBOL, CdbType.VARCHAR}) {
                cases.add(new Case(name, () -> new TextMinMaxAccumulator(max), type, type, Kind.EXACT));
            }
        }
        for (final CdbType type : new CdbType[] {CdbType.LONG, CdbType.TIMESTAMP, CdbType.DOUBLE, CdbType.BOOLEAN}) {
            cases.add(new Case("count_distinct", LongCountDistinctAccumulator::new, type, CdbType.LONG, Kind.EXACT));
        }
        for (final CdbType type : new CdbType[] {CdbType.SYMBOL, CdbType.VARCHAR}) {
            cases.add(new Case("count_distinct", TextCountDistinctAccumulator::new, type, CdbType.LONG, Kind.EXACT));
        }
        for (final double p : FRACTIONS) {
            for (final CdbType type : new CdbType[] {CdbType.LONG, CdbType.TIMESTAMP, CdbType.DOUBLE}) {
                cases.add(new Case("percentile_cont " + p, () -> new ContinuousPercentileAccumulator(p), type,
                        CdbType.DOUBLE, Kind.EXACT));
            }
            cases.add(new Case("percentile_disc " + p, () -> new DoublePercentileAccumulator(p), CdbType.DOUBLE,
                    CdbType.DOUBLE, Kind.EXACT));
            for (final CdbType type : new CdbType[] {CdbType.LONG, CdbType.TIMESTAMP}) {
                cases.add(new Case("percentile_disc " + p, () -> new LongPercentileAccumulator(p, -1), type,
                        CdbType.LONG, Kind.EXACT));
            }
            for (int precision = 0; precision <= 5; precision++) {
                final int digits = precision;
                cases.add(new Case("approx_percentile " + p + " " + digits,
                        () -> new LongPercentileAccumulator(p, digits), CdbType.LONG, CdbType.LONG, Kind.NON_NEGATIVE));
            }
        }
        return cases;
    }

    // the frames [cuts[f], cuts[f + 1]) merged in frame order, against `serial` or, for a double sum, its recomputation
    private void check(final Case c, final Input input, final Vector serial, final int[] cuts) {
        final List<Frame> frames = new ArrayList<>();
        for (int f = 0; f + 1 < cuts.length; f++) {
            frames.add(frame(c, input, cuts[f], cuts[f + 1]));
        }
        // the first frame's groups are numbered as the whole input's, so it can be the merge's target
        final boolean intoFirst = random.nextBoolean();
        final Accumulator merged = intoFirst ? frames.getFirst().accumulator : c.create.get();
        final List<Vector> before = new ArrayList<>();
        for (final Frame frame : frames) {
            before.add(written(frame.accumulator, c.out, frame.groups));
        }
        merged.ensure(input.groups);
        for (int f = intoFirst ? 1 : 0; f < frames.size(); f++) {
            final Frame frame = frames.get(f);
            for (int g = 0; g < frame.groups; g++) {
                merged.merge(frame.globalOf[g], frame.accumulator, g);
            }
        }
        final Vector got = written(merged, c.out, input.groups);
        final Vector want = switch (c.kind) {
            case SUM, AVG -> recomputed(c, input, cuts);
            case EXACT, NON_NEGATIVE, ROWS -> serial;
        };
        for (int g = 0; g < input.groups; g++) {
            assertThat(cell(got, g)).as("group %d of %s over %s, cuts %s, into the first %s", g, c.name, c.in,
                    Arrays.toString(cuts), intoFirst).isEqualTo(cell(want, g));
        }
        for (int f = intoFirst ? 1 : 0; f < frames.size(); f++) {
            final Frame frame = frames.get(f);
            final Vector after = written(frame.accumulator, c.out, frame.groups);
            for (int g = 0; g < frame.groups; g++) {
                assertThat(cell(after, g)).as("frame %d group %d of %s over %s kept", f, g, c.name, c.in)
                        .isEqualTo(cell(before.get(f), g));
            }
        }
    }

    private Vector serial(final Case c, final Input input) {
        return written(frame(c, input, 0, input.rows).accumulator, c.out, input.groups);
    }

    // rows [from, to) fed to a new accumulator, its groups numbered in their first-seen order among those rows
    private Frame frame(final Case c, final Input input, final int from, final int to) {
        final int[] localOf = new int[input.groups];
        Arrays.fill(localOf, -1);
        final int[] globalOf = new int[input.groups];
        final int[] rowGroups = new int[to - from];
        int groups = 0;
        for (int r = from; r < to; r++) {
            final int g = input.groupOfRow[r];
            if (localOf[g] < 0) {
                localOf[g] = groups;
                globalOf[groups++] = g;
            }
            rowGroups[r - from] = localOf[g];
        }
        final Accumulator accumulator = c.create.get();
        feed(accumulator, c.kind == Kind.ROWS ? null : input.values, from, rowGroups, groups);
        return new Frame(accumulator, globalOf, groups);
    }

    // pages of 1 to 1,024 rows, each fed whole, by runs of one group, or a row at a time, as AggTable feeds them
    private void feed(
            final Accumulator accumulator,
            final Vector values,
            final int from,
            final int[] rowGroups,
            final int groups
    ) {
        accumulator.ensure(groups);
        int start = 0;
        while (start < rowGroups.length) {
            final int size = Math.min(rowGroups.length - start, 1 + random.nextInt(Page.CAPACITY));
            final Vector page = values == null ? null : Vector.of(values.type, size);
            final int[] pageGroups = Arrays.copyOfRange(rowGroups, start, start + size);
            for (int i = 0; page != null && i < size; i++) {
                page.copy(values, from + start + i, i);
            }
            switch (random.nextInt(3)) {
                case 0 -> accumulator.addAll(pageGroups, page, size);
                case 1 -> {
                    int run = 0;
                    while (run < size) {
                        int end = run + 1;
                        while (end < size && pageGroups[end] == pageGroups[run]) {
                            end++;
                        }
                        accumulator.addRange(pageGroups[run], page, run, end);
                        run = end;
                    }
                }
                default -> {
                    for (int i = 0; i < size; i++) {
                        accumulator.add(pageGroups[i], page, i);
                    }
                }
            }
            start += size;
        }
    }

    // a double sum or avg as a parallel query computes it: each frame's values added in row order, the frames' sums
    // added in frame order
    private static Vector recomputed(final Case c, final Input input, final int[] cuts) {
        final double[] totals = new double[input.groups];
        final long[] counts = new long[input.groups];
        for (int f = 0; f + 1 < cuts.length; f++) {
            final double[] sums = new double[input.groups];
            for (int r = cuts[f]; r < cuts[f + 1]; r++) {
                if (!input.values.isNull(r)) {
                    final int g = input.groupOfRow[r];
                    sums[g] += input.values.type == CdbType.DOUBLE
                            ? input.values.getDouble(r)
                            : input.values.getLong(r);
                    counts[g]++;
                }
            }
            for (int g = 0; g < input.groups; g++) {
                totals[g] += sums[g];
            }
        }
        final Vector out = Vector.of(c.out, input.groups);
        for (int g = 0; g < input.groups; g++) {
            out.setDouble(g, counts[g] == 0 ? Double.NaN : c.kind == Kind.AVG ? totals[g] / counts[g] : totals[g]);
        }
        return out;
    }

    private static Object cell(final Vector v, final int row) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> v.getLong(row);
            case DOUBLE -> Double.doubleToRawLongBits(v.getDouble(row));
            case BOOLEAN -> v.getBoolean(row);
            case SYMBOL, VARCHAR -> String.valueOf(v.textAt(row));
        };
    }

    private static Vector written(final Accumulator accumulator, final CdbType type, final int groups) {
        final Vector out = Vector.of(type, groups);
        for (int g = 0; g < groups; g++) {
            accumulator.write(g, out, g);
        }
        return out;
    }

    // runs of 1 to 4 rows of one group, skewed so the high groups are rare and first seen late, numbered in their
    // first-seen order; values of the case's type
    private Input input(final Case c, final int rows, final int groupLabels, final boolean nullsOnly) {
        final int[] labels = new int[rows];
        for (int r = 0; r < rows;) {
            final double x = random.nextDouble();
            final int label = (int) (groupLabels * x * x);
            for (int run = 1 + random.nextInt(4); run > 0 && r < rows; run--) {
                labels[r++] = label;
            }
        }
        final int[] numberOf = new int[groupLabels];
        Arrays.fill(numberOf, -1);
        final Input input = new Input(rows, Vector.of(c.in, rows));
        for (int r = 0; r < rows; r++) {
            if (numberOf[labels[r]] < 0) {
                numberOf[labels[r]] = input.groups++;
            }
            input.groupOfRow[r] = numberOf[labels[r]];
            set(input.values, r, nullsOnly, c.kind == Kind.NON_NEGATIVE);
        }
        return input;
    }

    private void set(final Vector v, final int i, final boolean nullsOnly, final boolean nonNegative) {
        if (nullsOnly) {
            if (v.type == CdbType.BOOLEAN) {
                v.setBoolean(i, random.nextBoolean());
            } else {
                v.setNull(i);
            }
            return;
        }
        final int pick = random.nextInt(100);
        switch (v.type) {
            case LONG, TIMESTAMP -> v.setLong(i, pick < 10
                    ? Vector.NULL_LONG
                    : pick < 50 ? random.nextInt(20) : nonNegative ? random.nextLong(1L << 40) : random.nextLong());
            case DOUBLE -> v.setDouble(i, switch (pick) {
                case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 -> Double.NaN;
                case 10, 11, 12, 13, 14 -> -0.0;
                case 15, 16, 17, 18, 19 -> 0.0;
                case 20 -> Double.POSITIVE_INFINITY;
                case 21 -> Double.NEGATIVE_INFINITY;
                case 22 -> Double.MIN_VALUE;
                default -> pick < 60 ? random.nextInt(20) / 4.0 : random.nextGaussian() * 1e6;
            });
            case BOOLEAN -> v.setBoolean(i, random.nextBoolean());
            case SYMBOL, VARCHAR -> v.setText(i, pick < 10 ? null : "s" + random.nextInt(12));
        }
    }

    /// What a case's merged result is compared with, and the values it is fed.
    private enum Kind {
        // the serial result, bit for bit
        EXACT,
        // as EXACT, over values of 0 or more (the histogram percentiles refuse a negative one)
        NON_NEGATIVE,
        // as EXACT, fed no value: count()
        ROWS,
        // the frames' double sums added in frame order
        SUM,
        // that sum over the count
        AVG
    }

    /// One aggregate over one value type.
    private record Case(String name, Supplier<Accumulator> create, CdbType in, CdbType out, Kind kind) {
    }

    /// The accumulator of one frame, and the whole input's group of each of its groups.
    private record Frame(Accumulator accumulator, int[] globalOf, int groups) {
    }

    /// The rows: a value and a group each, groups numbered in their first-seen order.
    private static final class Input {

        private final int[] groupOfRow;
        private final int rows;
        private final Vector values;
        private int groups;

        private Input(final int rows, final Vector values) {
            this.rows = rows;
            this.values = values;
            this.groupOfRow = new int[rows];
        }
    }
}
