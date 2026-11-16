package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Bound;

import static org.assertj.core.api.Assertions.assertThat;

/// [AggTable#merge]: the rows cut into frames, one table per frame, the tables merged in frame order into an empty
/// table or into the first frame's answer as one table fed every row: the same groups in the same order, the same keys
/// and every aggregate bit for bit. Keys of every type, NULL keys among them, and no key at all, whose empty group
/// merges as a group; groups first seen in later frames; empty frames. The DOUBLE values are quarters, so their sums
/// are exact in any order. Seed in the output; replay with `-Dcdb.agg.seed=<seed>`.
class AggTableMergeTest {

    private static final long SEED = Long.getLong("cdb.agg.seed", System.nanoTime());
    private static final CdbType[] COLUMNS = {CdbType.LONG, CdbType.DOUBLE, CdbType.VARCHAR, CdbType.LONG,
            CdbType.DOUBLE, CdbType.VARCHAR, CdbType.BOOLEAN, CdbType.SYMBOL, CdbType.TIMESTAMP};
    private static final Bound X = new Bound.Column(0, CdbType.LONG);
    private static final Bound D = new Bound.Column(1, CdbType.DOUBLE);
    private static final Bound T = new Bound.Column(2, CdbType.VARCHAR);
    // the page columns of each key set: none, one LONG, two of DOUBLE and VARCHAR, three of the other types
    private static final int[][] KEY_SETS = {{}, {3}, {4, 5}, {6, 7, 8}};
    private static final List<Bound.Agg> AGGS = List.of(
            agg("count", CdbType.LONG),
            agg("count", CdbType.LONG, X),
            agg("count", CdbType.LONG, T),
            new Bound.Agg("count", List.of(T), true, CdbType.LONG),
            agg("count_distinct", CdbType.LONG, X),
            agg("count_distinct", CdbType.LONG, D),
            agg("sum", CdbType.LONG, X),
            agg("sum", CdbType.DOUBLE, D),
            agg("avg", CdbType.DOUBLE, X),
            agg("avg", CdbType.DOUBLE, D),
            agg("min", CdbType.LONG, X),
            agg("max", CdbType.DOUBLE, D),
            agg("min", CdbType.VARCHAR, T),
            agg("max", CdbType.VARCHAR, T),
            agg("first", CdbType.LONG, X),
            agg("last", CdbType.DOUBLE, D),
            agg("first", CdbType.VARCHAR, T),
            agg("last", CdbType.VARCHAR, T),
            agg("approx_percentile", CdbType.LONG, X, constant(0.9), constant(2)),
            agg("approx_median", CdbType.LONG, X),
            agg("percentile_disc", CdbType.DOUBLE, D, constant(0.5)),
            agg("percentile_cont", CdbType.DOUBLE, D, constant(0.25)));
    // short enough that every cut into 3 frames runs for every key set
    private static final int SHORT_ROWS = 24;
    private static final int SHORT_LABELS = 6;
    private static final int LONG_ROWS = 30_000;
    private static final int LONG_LABELS = 600;
    private static final int TRIALS = 4;

    private Random random;

    /// A generator per test, from the run's seed and the test's name, so one test replays alone.
    @BeforeEach
    void seed(final TestInfo info) {
        System.out.println(info.getDisplayName() + " seed=" + SEED);
        random = new Random(SEED ^ info.getDisplayName().hashCode());
    }

    /// Every cut of a short input into 2 frames and into 3, empty frames included, for every key set.
    @Test
    void everyCutIntoTwoOrThreeFramesMergesToTheSerialTable() {
        for (final int[] keySet : KEY_SETS) {
            final Input input = input(SHORT_ROWS, SHORT_LABELS);
            final AggTable serial = serial(input, keySet);
            for (int a = 0; a <= input.rows; a++) {
                check(input, keySet, serial, new int[] {0, a, input.rows});
                for (int b = a; b <= input.rows; b++) {
                    check(input, keySet, serial, new int[] {0, a, b, input.rows});
                }
            }
        }
    }

    /// Seeded random cuts of a long input into 2 to 9 frames, some empty, for every key set; the merges add groups past
    /// the tables' first growth, and each merged table answers after the merge as it did before it.
    @Test
    void randomCutsMergeToTheSerialTable() {
        for (final int[] keySet : KEY_SETS) {
            final Input input = input(LONG_ROWS, LONG_LABELS);
            final AggTable serial = serial(input, keySet);
            for (int t = 0; t < TRIALS; t++) {
                final int frames = 2 + random.nextInt(8);
                final int[] cuts = new int[frames + 1];
                for (int f = 1; f < frames; f++) {
                    // a few cuts at 0, which leaves a frame empty
                    cuts[f] = random.nextInt(8) == 0 ? 0 : random.nextInt(input.rows + 1);
                }
                cuts[frames] = input.rows;
                Arrays.sort(cuts);
                check(input, keySet, serial, cuts);
            }
        }
    }

    /// No rows at all: the merge of empty frames answers as the serial table, one empty group without keys and none
    /// with them.
    @Test
    void emptyFramesMergeToTheSerialTable() {
        for (final int[] keySet : KEY_SETS) {
            final Input input = input(0, 1);
            final AggTable serial = serial(input, keySet);
            assertThat(serial.groups()).isEqualTo(keySet.length == 0 ? 1 : 0);
            check(input, keySet, serial, new int[] {0, 0, 0, 0});
        }
    }

    // the frames [cuts[f], cuts[f + 1]) merged in frame order, against the serial table
    private void check(final Input input, final int[] keySet, final AggTable serial, final int[] cuts) {
        final List<AggTable> frames = new ArrayList<>();
        for (int f = 0; f + 1 < cuts.length; f++) {
            // a frame's cursor adds the empty group only when the query has no key; a partial may come without it
            frames.add(table(input, keySet, cuts[f], cuts[f + 1], keySet.length == 0 && random.nextBoolean()));
        }
        final boolean intoFirst = random.nextBoolean();
        final AggTable merged = intoFirst ? frames.getFirst() : new AggTable(keyTypes(keySet), AGGS, 0);
        final List<List<Object>> before = new ArrayList<>();
        for (final AggTable frame : frames) {
            before.add(cells(frame, keySet));
        }
        for (int f = intoFirst ? 1 : 0; f < frames.size(); f++) {
            merged.merge(frames.get(f));
        }
        if (keySet.length == 0) {
            merged.addEmptyGroup();
        }
        assertThat(cells(merged, keySet)).as("keys %s, cuts %s, into the first %s", Arrays.toString(keySet),
                Arrays.toString(cuts), intoFirst).containsExactlyElementsOf(cells(serial, keySet));
        for (int f = intoFirst ? 1 : 0; f < frames.size(); f++) {
            assertThat(cells(frames.get(f), keySet)).as("frame %d kept", f)
                    .containsExactlyElementsOf(before.get(f));
        }
    }

    private AggTable serial(final Input input, final int[] keySet) {
        return table(input, keySet, 0, input.rows, keySet.length == 0);
    }

    // rows [from, to) added a page of 1 to 1,024 rows at a time, the empty group first when asked
    private AggTable table(final Input input, final int[] keySet, final int from, final int to, final boolean empty) {
        final AggTable table = new AggTable(keyTypes(keySet), AGGS, 0);
        if (empty) {
            table.addEmptyGroup();
        }
        int start = from;
        while (start < to) {
            final int size = Math.min(to - start, 1 + random.nextInt(Page.CAPACITY));
            final Vector[] columns = new Vector[COLUMNS.length];
            for (int c = 0; c < columns.length; c++) {
                columns[c] = Vector.of(COLUMNS[c], size);
                for (int i = 0; i < size; i++) {
                    columns[c].copy(input.columns[c], start + i, i);
                }
            }
            final Vector[] keys = new Vector[keySet.length];
            for (int k = 0; k < keys.length; k++) {
                keys[k] = columns[keySet[k]];
            }
            table.add(new Page(columns, size), keys);
            start += size;
        }
        return table;
    }

    // every group in order, a row of its keys then its aggregates, DOUBLE as raw bits
    private static List<Object> cells(final AggTable table, final int[] keySet) {
        final List<Object> cells = new ArrayList<>();
        for (int g = 0; g < table.groups(); g++) {
            final List<Object> row = new ArrayList<>();
            for (int k = 0; k < keySet.length; k++) {
                final Vector out = Vector.of(COLUMNS[keySet[k]], 1);
                table.writeKey(g, k, out, 0);
                row.add(cell(out));
            }
            for (int a = 0; a < table.aggregates(); a++) {
                final Vector out = Vector.of(AGGS.get(a).type(), 1);
                table.writeAggregate(g, a, out, 0);
                row.add(cell(out));
            }
            cells.add(row);
        }
        return cells;
    }

    private static Object cell(final Vector v) {
        return switch (v.type) {
            case LONG, TIMESTAMP -> v.longs[0];
            case DOUBLE -> Double.doubleToRawLongBits(v.doubles[0]);
            case BOOLEAN -> v.booleans[0];
            case SYMBOL, VARCHAR -> String.valueOf(v.texts[0]);
        };
    }

    private static List<CdbType> keyTypes(final int[] keySet) {
        final List<CdbType> types = new ArrayList<>();
        for (final int column : keySet) {
            types.add(COLUMNS[column]);
        }
        return types;
    }

    // runs of 1 to 4 rows of one key tuple, skewed so the high labels are rare and first seen late; each label one
    // tuple of every key column, NULLs and NaN, -0.0 and 0.0 among them
    private Input input(final int rows, final int labels) {
        final Vector[] tuples = new Vector[COLUMNS.length];
        for (int c = 3; c < COLUMNS.length; c++) {
            tuples[c] = Vector.of(COLUMNS[c], labels);
            for (int l = 0; l < labels; l++) {
                key(tuples[c], l);
            }
        }
        final Input input = new Input(rows);
        for (int r = 0; r < rows;) {
            final double x = random.nextDouble();
            final int label = (int) (labels * x * x);
            for (int run = 1 + random.nextInt(4); run > 0 && r < rows; run--) {
                for (int c = 3; c < COLUMNS.length; c++) {
                    input.columns[c].copy(tuples[c], label, r);
                }
                value(input, r++);
            }
        }
        return input;
    }

    // tuple `label` of a key column: distinct across labels in at least one column of each key set; an odd LONG key
    // above 1 hashes as the even one below it, so the merge meets equal hashes of different keys
    private void key(final Vector v, final int label) {
        switch (v.type) {
            case LONG -> v.longs[label] = label == 1
                    ? Vector.NULL_LONG
                    : label % 2 == 1 ? (1L << 32) | ((label - 1) ^ 1) : label;
            case TIMESTAMP -> v.longs[label] = label % 7 == 3 ? Vector.NULL_LONG : -label * 1_000_000L;
            case DOUBLE -> v.doubles[label] = switch (label % 4) {
                case 0 -> Double.NaN;
                case 1 -> -0.0;
                case 2 -> 0.0;
                default -> label / 4 * 0.5;
            };
            case BOOLEAN -> v.booleans[label] = random.nextBoolean();
            case VARCHAR -> v.texts[label] = label / 4 == 1 ? null : "k" + label / 4;
            case SYMBOL -> v.texts[label] = label == 2 ? null : "s" + label;
        }
    }

    // x a small LONG or NULL, d a quarter, NaN or a zero of either sign, t a short text or NULL
    private void value(final Input input, final int row) {
        final int pick = random.nextInt(100);
        input.columns[0].longs[row] = pick < 10 ? Vector.NULL_LONG : random.nextInt(1_000);
        input.columns[1].doubles[row] = switch (pick % 10) {
            case 0 -> Double.NaN;
            case 1 -> -0.0;
            case 2 -> 0.0;
            default -> (random.nextInt(400) - 200) / 4.0;
        };
        input.columns[2].texts[row] = pick % 9 == 0 ? null : "t" + random.nextInt(15);
    }

    private static Bound.Agg agg(final String function, final CdbType type, final Bound... args) {
        return new Bound.Agg(function, List.of(args), false, type);
    }

    private static Bound constant(final double value) {
        return new Bound.Constant(value, CdbType.DOUBLE);
    }

    /// The rows, one vector per page column.
    private static final class Input {

        private final Vector[] columns = new Vector[COLUMNS.length];
        private final int rows;

        private Input(final int rows) {
            this.rows = rows;
            for (int c = 0; c < columns.length; c++) {
                columns[c] = Vector.of(COLUMNS[c], rows);
            }
        }
    }
}
