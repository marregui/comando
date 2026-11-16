package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// LATEST ON, DISTINCT and the set operations run per frame answer what their serial operators answer, row for row
/// and bit for bit, on pools of 0, 1, 3 and 11 workers: over random rows cut into frames of a few rows, so ties,
/// duplicates and NULL keys fall on both sides of frame edges, and end to end over a table whose partitions each hold
/// more than [Frames#FRAME_ROWS] rows. Seeds in the output; replay with `-Dcdb.exec.seed=<seed>`.
class ParallelRowOpsTest {

    private static final int[] POOL_SIZES = {0, 1, 3, 11};
    private static final int CASES = 300;
    private static final long NOW = 1_000_000_000_000L;
    private static final List<Field> XS = List.of(field("sym", CdbType.SYMBOL), field("n", CdbType.LONG),
            field("d", CdbType.DOUBLE), field("flag", CdbType.BOOLEAN));
    private static final String[] SET_OPS = {"union", "except", "intersect"};

    @TempDir
    private static Path root;
    private static QueryPool[] pools;
    private static Store store;

    /// One pool per size and one table of 300,000 rows over two hourly partitions of 215,999 and 84,001 rows, so the
    /// whole-size frames fall inside partitions too: every three rows share a timestamp, so a tie spans the edge
    /// between the first two frames, where the key `edge` has both its latest rows.
    @BeforeAll
    static void openPoolsAndTable() {
        pools = new QueryPool[POOL_SIZES.length];
        for (int i = 0; i < pools.length; i++) {
            pools[i] = QueryPool.of(POOL_SIZES[i]);
        }
        store = Store.open(root);
        run("CREATE TABLE t (ts TIMESTAMP, k SYMBOL, x LONG, d DOUBLE, v VARCHAR, at TIMESTAMP) TIMESTAMP(ts)"
                + " PARTITION BY HOUR");
        run("INSERT INTO t SELECT CAST(x / 3 * 50000 AS TIMESTAMP),"
                + " CASE WHEN x = 65535 OR x = 65537 THEN 'edge' WHEN x < 10 THEN 'early' WHEN x % 11 = 0 THEN NULL"
                + " ELSE 'k' || (x % 7) END,"
                + " x, CASE WHEN x % 13 = 0 THEN NULL ELSE (x % 5) * 0.5 END, 'v' || (x % 3),"
                + " CAST(x * 7919 % 100000 * 1000 AS TIMESTAMP) FROM long_sequence(300000)");
    }

    @AfterAll
    static void closePoolsAndTable() {
        if (store != null) {
            store.close();
        }
        for (final QueryPool pool : pools) {
            pool.close();
        }
    }

    private static void run(final String sql) {
        Executor.execute((Plan.Change) Planner.plan(Parser.parse(sql), store::def, NOW), store, NOW);
    }

    /// The rows of `rows[from, to)` in pages of `pageSize`.
    private static Cursor slice(final List<Field> fields, final Object[][] rows, final int from, final int to,
                                final int pageSize) {
        return RowsFakes.cursor(fields, to - from, pageSize, r -> rows[from + r]);
    }

    /// The partials `step` makes of `rows` cut into frames of `frameRows`, on `pool`; `releases` counts the releases.
    private static DistinctCursor.FramePartials frames(
            final List<Field> fields,
            final Object[][] rows,
            final int frameRows,
            final int pageSize,
            final QueryPool pool,
            final Function<Cursor, List<Page>> step,
            final AtomicInteger releases
    ) {
        final int count = Math.max(1, (rows.length + frameRows - 1) / frameRows);
        final QueryContext ctx = new QueryContext(null, pool);
        return new DistinctCursor.FramePartials(fields, () -> pool.submit(count, f -> step.apply(slice(fields, rows,
                Math.min(rows.length, f * frameRows), Math.min(rows.length, (f + 1) * frameRows), pageSize)), ctx),
                releases::incrementAndGet);
    }

    /// A frame size of 3 rows one case in two, else 1 to 8 rows or a whole page and a bit.
    private static int frameRows(final Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> 1 + random.nextInt(8);
            case 1 -> Page.CAPACITY + 1;
            default -> 3;
        };
    }

    private static int[] partition(final Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> new int[0];
            case 1 -> new int[] {4};
            case 2 -> new int[] {1, 2};
            case 3 -> new int[] {5, 4};
            case 4 -> new int[] {3};
            default -> new int[] {1};
        };
    }

    /// `n` rows of [#XS] with few values, NULL in about a fifth of the nullable ones: duplicates within and across
    /// frames.
    private static Object[][] few(final Random random, final int n) {
        final int cardinality = 1 + random.nextInt(6);
        final Object[][] rows = new Object[n][];
        for (int r = 0; r < n; r++) {
            rows[r] = new Object[] {
                    random.nextInt(5) == 0 ? null : "s" + random.nextInt(cardinality),
                    random.nextInt(5) == 0 ? null : (long) random.nextInt(cardinality),
                    random.nextInt(5) == 0 ? null : random.nextInt(cardinality) * 0.5 - 1.0,
                    random.nextBoolean()
            };
        }
        return rows;
    }

    /// LATEST ON per frame, in time order over ordered and unordered rows and newest first, with timestamp ties and
    /// NULL keys on frame edges, answers the serial operator's rows in its order, packed.
    @Test
    void latestOnPerFrameMatchesTheSerialOperator() {
        final Random random = ReferenceCursors.random("latestOnPerFrameMatchesTheSerialOperator");
        for (int i = 0; i < CASES; i++) {
            final boolean newestFirst = random.nextBoolean();
            final boolean ascending = newestFirst || random.nextBoolean();
            final Object[][] rows = ReferenceCursors.rows(random, ReferenceCursors.size(random),
                    1 + random.nextInt(newestFirst ? 30 : 200), random.nextInt(30), ascending);
            final int[] keys = partition(random);
            final int pageSize = ReferenceCursors.pageSize(random);
            final int frameRows = frameRows(random);
            final Plan.LatestOn spec = new Plan.LatestOn(new Plan.Distinct(null), 0, keys, newestFirst);
            final List<List<Object>> expected = RowsFakes.drain(
                    new LatestOnCursor(ReferenceCursors.cursor(rows, pageSize, newestFirst), spec)).rows();
            final int n = rows.length;
            final Object[][] input = new Object[n][];
            for (int r = 0; r < n; r++) {
                input[r] = rows[newestFirst ? n - 1 - r : r];
            }
            for (final QueryPool pool : pools) {
                final AtomicInteger releases = new AtomicInteger();
                final RowsFakes.Drained out = RowsFakes.drain(new LatestOnCursor(frames(ReferenceCursors.FIELDS, input,
                        frameRows, pageSize, pool, frame -> LatestOnCursor.partial(frame, spec), releases), spec));
                final String as = "n=" + n + ", newestFirst=" + newestFirst + ", frameRows=" + frameRows + ", pool="
                        + pool.threads();
                assertThat(out.rows()).as(as).isEqualTo(expected);
                assertThat(out.packed()).as(as).isTrue();
                assertThat(releases.get()).as(as).isEqualTo(1);
            }
        }
    }

    /// DISTINCT per frame, with duplicates and NULLs within and across frames, answers the serial operator's rows in
    /// first-seen order.
    @Test
    void distinctPerFrameMatchesTheSerialOperator() {
        final Random random = ReferenceCursors.random("distinctPerFrameMatchesTheSerialOperator");
        for (int i = 0; i < CASES; i++) {
            final Object[][] rows = few(random, ReferenceCursors.size(random));
            final int pageSize = ReferenceCursors.pageSize(random);
            final int frameRows = frameRows(random);
            final List<List<Object>> expected = RowsFakes.drain(
                    new DistinctCursor(slice(XS, rows, 0, rows.length, pageSize))).rows();
            for (final QueryPool pool : pools) {
                final AtomicInteger releases = new AtomicInteger();
                final List<List<Object>> out = RowsFakes.drain(new DistinctCursor(frames(XS, rows, frameRows, pageSize,
                        pool, DistinctCursor::partial, releases))).rows();
                assertThat(out).as("n=%d, frameRows=%d, pool=%d", rows.length, frameRows, pool.threads())
                        .isEqualTo(expected);
                assertThat(releases.get()).isEqualTo(1);
            }
        }
    }

    /// UNION, EXCEPT and INTERSECT with the left per frame answer the serial operator's rows in first-seen order,
    /// packed, NULL equal to NULL, with duplicates across frames and across the inputs.
    @Test
    void setOperationsPerFrameMatchTheSerialOperator() {
        final Random random = ReferenceCursors.random("setOperationsPerFrameMatchTheSerialOperator");
        for (int i = 0; i < CASES; i++) {
            final Object[][] left = few(random, ReferenceCursors.size(random));
            final Object[][] right = few(random, ReferenceCursors.size(random) / 4);
            final String op = SET_OPS[random.nextInt(SET_OPS.length)];
            final int pageSize = ReferenceCursors.pageSize(random);
            final int frameRows = frameRows(random);
            final List<List<Object>> expected = RowsFakes.drain(new SetOpCursor(op,
                    slice(XS, left, 0, left.length, pageSize), slice(XS, right, 0, right.length, pageSize))).rows();
            for (final QueryPool pool : pools) {
                final AtomicInteger releases = new AtomicInteger();
                final RowsFakes.Drained out = RowsFakes.drain(new SetOpCursor(op, frames(XS, left, frameRows, pageSize,
                        pool, SetOpCursor::distinctRows, releases), slice(XS, right, 0, right.length, pageSize)));
                final String as = op + " n=" + left.length + ", frameRows=" + frameRows + ", pool=" + pool.threads();
                assertThat(out.rows()).as(as).isEqualTo(expected);
                assertThat(out.packed()).as(as).isTrue();
                assertThat(releases.get()).as(as).isEqualTo(1);
            }
        }
    }

    /// DISTINCT streams: on a pool without workers, where a frame runs only when it is awaited, the first page goes
    /// out once the first frame is read, before the later frames run.
    @Test
    void distinctEmitsTheFirstFrameBeforeTheLastRuns() {
        final Object[][] rows = new Object[30][];
        for (int r = 0; r < rows.length; r++) {
            rows[r] = new Object[] {"s" + r, (long) r, 0.5, true};
        }
        final AtomicInteger ran = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        try (Cursor cursor = new DistinctCursor(frames(XS, rows, 3, 3, pools[0], frame -> {
            ran.incrementAndGet();
            return DistinctCursor.partial(frame);
        }, releases))) {
            assertThat(cursor.next().size).isEqualTo(3);
            assertThat(ran.get()).isEqualTo(1);
        }
        assertThat(releases.get()).isEqualTo(1);
    }

    /// A frame's failure reaches the consumer, the frames are released once at the close, and a second close does
    /// nothing.
    @Test
    void aFailedFrameFailsTheQueryAndReleasesTheFramesOnce() {
        final Object[][] rows = new Object[40][];
        for (int r = 0; r < rows.length; r++) {
            rows[r] = new Object[] {"s" + r, (long) r, 0.5, true};
        }
        final IllegalStateException failure = new IllegalStateException("frame failed [frame=3]");
        for (final QueryPool pool : pools) {
            final AtomicInteger releases = new AtomicInteger();
            final Cursor cursor = new DistinctCursor(frames(XS, rows, 3, 2, pool, frame -> {
                final List<Page> pages = DistinctCursor.partial(frame);
                if (pages.getFirst().columns[1].longs[0] == 9L) {
                    throw failure;
                }
                return pages;
            }, releases));
            assertThatThrownBy(() -> {
                while (cursor.next() != null) {
                    assertThat(releases.get()).isZero();
                }
            }).as("pool=%d", pool.threads()).isSameAs(failure);
            cursor.close();
            cursor.close();
            assertThat(releases.get()).as("pool=%d", pool.threads()).isEqualTo(1);
        }
    }

    /// End to end on the table: each query's operator, run in a context on every pool, answers what the serial
    /// operator answers over its input read in scan order; LATEST ON also in time order and on a timestamp that is not
    /// the designated one, so its input is unordered.
    @Test
    void queriesOverManyFramesMatchTheSerialOperator() {
        final List<String> queries = List.of(
                "SELECT * FROM t LATEST ON ts PARTITION BY k",
                "SELECT * FROM t WHERE d IS NOT NULL LATEST ON ts PARTITION BY v, k",
                "SELECT * FROM t LATEST ON at PARTITION BY k",
                "SELECT DISTINCT k, v FROM t",
                "SELECT DISTINCT d, k FROM t WHERE x > 100",
                "SELECT DISTINCT x % 1000 FROM t",
                "SELECT k, x % 100 FROM t UNION SELECT k, x % 101 FROM t WHERE x < 1000",
                "SELECT k, x % 100 FROM t EXCEPT SELECT k, x % 100 FROM t WHERE x % 3 = 0",
                "SELECT k, x % 100 FROM t INTERSECT SELECT k, x % 100 FROM t WHERE x % 3 = 0",
                "SELECT k, x FROM t WHERE x % 1000 = 0 UNION ALL SELECT k, x FROM t WHERE x % 999 = 0",
                // the left's duplicates within each frame are kept: a UNION ALL routed through the dedup drops them
                "SELECT k, x % 10 FROM t UNION ALL SELECT k, x % 10 FROM t WHERE x < 100");
        for (final String sql : queries) {
            final Plan operator = operator(Planner.plan(Parser.parse(sql), store::def, NOW));
            check(sql, operator);
            if (operator instanceof Plan.LatestOn(
                    final Plan child, final int ts, final int[] keys, final boolean newest) && newest) {
                check(sql + " in time order", new Plan.LatestOn(inTimeOrder(child), ts, keys, false));
            }
        }
    }

    private static void check(final String sql, final Plan operator) {
        final Plan segment = switch (operator) {
            case Plan.LatestOn l -> l.child();
            case Plan.Distinct d -> d.child();
            case Plan.SetOp s -> s.left();
            default -> throw new AssertionError("not a per-frame operator [plan=" + operator + ']');
        };
        assertThat(FrameSource.isSegment(segment)).as(sql).isTrue();
        final List<List<Object>> expected = RowsFakes.drain(serial(operator)).rows();
        assertThat(expected).as(sql).isNotEmpty();
        for (final QueryPool pool : pools) {
            final List<List<Object>> out = RowsFakes.drain(
                    Executor.open(operator, store, NOW, new QueryContext(null, pool))).rows();
            assertThat(out).as("%s pool=%d", sql, pool.threads()).isEqualTo(expected);
        }
    }

    /// The serial operator of `operator` over its inputs read in scan order on the calling thread.
    private static Cursor serial(final Plan operator) {
        final QueryContext inline = new QueryContext(null, pools[0]);
        return switch (operator) {
            case Plan.LatestOn l -> new LatestOnCursor(Executor.open(l.child(), store, NOW, inline), l);
            case Plan.Distinct d -> new DistinctCursor(Executor.open(d.child(), store, NOW, inline));
            case Plan.SetOp s -> new SetOpCursor(s.op(), Executor.open(s.left(), store, NOW, inline),
                    Executor.open(s.right(), store, NOW, inline));
            default -> throw new AssertionError("not a per-frame operator [plan=" + operator + ']');
        };
    }

    /// The first LATEST ON, DISTINCT or set operation on the way down from `plan` through single-child plans.
    private static Plan operator(final Plan plan) {
        return switch (plan) {
            case Plan.LatestOn _, Plan.Distinct _, Plan.SetOp _ -> plan;
            case Plan.Project p -> operator(p.child());
            case Plan.Filter f -> operator(f.child());
            case Plan.Sort s -> operator(s.child());
            case Plan.Limit l -> operator(l.child());
            default -> throw new AssertionError("no per-frame operator [plan=" + plan + ']');
        };
    }

    /// `segment` with its scan in time order.
    private static Plan inTimeOrder(final Plan segment) {
        return switch (segment) {
            case Plan.Scan s -> new Plan.Scan(s.table(), s.columns(), s.fromMicros(), s.toMicros(), s.terms(), false,
                    s.fields());
            case Plan.Filter f -> new Plan.Filter(inTimeOrder(f.child()), f.predicate());
            case Plan.Project p -> new Plan.Project(inTimeOrder(p.child()), p.exprs(), p.fields());
            default -> throw new AssertionError("not a segment [plan=" + segment + ']');
        };
    }

    /// The fixture's rows land as the header says: two partitions of more than one frame each.
    @Test
    void theTableSpansFramesInEachPartition() {
        final List<String> counts = new ArrayList<>();
        for (final String sql : List.of("SELECT count() FROM t WHERE ts < '1970-01-01T01:00:00Z'",
                "SELECT count() FROM t WHERE ts >= '1970-01-01T01:00:00Z'")) {
            try (Cursor cursor = Executor.open(Planner.plan(Parser.parse(sql), store::def, NOW), store, NOW)) {
                counts.add(String.valueOf(cursor.next().columns[0].longs[0]));
            }
        }
        assertThat(counts).containsExactly("215999", "84001");
    }
}
