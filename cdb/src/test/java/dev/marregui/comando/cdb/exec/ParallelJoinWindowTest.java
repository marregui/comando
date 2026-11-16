package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [JoinCursor#parallel] and [WindowCursor#parallel] answer what the serial operator answers over the same input read
/// as one cursor: the same rows, in the same order, the same bits, at every pool size. The left table has three day
/// partitions of 66,000, 5 and 65,540 rows, so its scan cuts into frames that end inside a partition and at its end,
/// and a window's partitions span every frame. Random values from a seed printed per run; replay with
/// `-Dcdb.exec.seed=<seed>`.
class ParallelJoinWindowTest {

    /// 2026-11-16T12:00:00Z.
    private static final long NOW = 1_794_830_400_000_000L;
    /// 2026-11-10T00:00:00Z, the first day partition of the left table.
    private static final long BASE = 1_794_268_800_000_000L;
    private static final long DAY = 86_400_000_000L;
    private static final int[] DAY_ROWS = {66_000, 5, 65_540};
    private static final int[] POOLS = {0, 1, 3, 11};
    private static final String[] SYMBOLS = {"a", "b", "c", "d", "e", "f", "g"};
    /// The fan-out join's left: one partition of the fake table, cut in frames of 1,000 rows, the last of 500.
    private static final int FAN_LEFT_ROWS = 2_500;
    private static final int FAN_FRAME_ROWS = 1_000;
    /// The fan-out join's right: 70 rows, so a frame of 1,000 left rows joins to 70,000 rows, 69 pages.
    private static final int FAN_RIGHT_ROWS = 70;
    private static final List<Field> FAN_LEFT_FIELDS = List.of(RowsFakes.field("ts", CdbType.TIMESTAMP),
            RowsFakes.field("x", CdbType.LONG), RowsFakes.field("d", CdbType.DOUBLE),
            RowsFakes.field("s", CdbType.SYMBOL));
    private static final List<Field> FAN_FIELDS = List.of(FAN_LEFT_FIELDS.get(0), FAN_LEFT_FIELDS.get(1),
            FAN_LEFT_FIELDS.get(2), FAN_LEFT_FIELDS.get(3), RowsFakes.field("rx", CdbType.LONG));
    private static final Plan.Scan FAN_SCAN = new Plan.Scan("t", new int[] {0, 1, 2, 3}, Long.MIN_VALUE,
            Long.MAX_VALUE, List.of(), false, FAN_LEFT_FIELDS);

    @TempDir
    private static Path root;
    private static Store store;
    /// A pool of no worker, for the serial operator's input.
    private static QueryPool serial;

    @BeforeAll
    static void fill() {
        final Random random = ReferenceCursors.random("ParallelJoinWindowTest");
        serial = QueryPool.of(0);
        store = Store.open(root);
        ddl("CREATE TABLE l (ts TIMESTAMP, k LONG, s SYMBOL, x DOUBLE, i LONG) TIMESTAMP(ts) PARTITION BY DAY");
        ddl("CREATE TABLE r (ts TIMESTAMP, k LONG, w LONG, name VARCHAR) TIMESTAMP(ts) PARTITION BY DAY");
        ddl("CREATE TABLE c (ts TIMESTAMP, w LONG) TIMESTAMP(ts) PARTITION BY DAY");
        ddl("CREATE TABLE e (ts TIMESTAMP, k LONG, name VARCHAR) TIMESTAMP(ts) PARTITION BY DAY");
        try (CdbAppender appender = store.appender()) {
            long i = 0;
            for (int day = 0; day < DAY_ROWS.length; day++) {
                for (int j = 0; j < DAY_ROWS[day]; j++) {
                    final CdbAppender row = appender.table("l").longColumn("i", i++);
                    if (random.nextInt(20) != 0) {
                        row.longColumn("k", random.nextInt(50));
                    }
                    if (random.nextInt(30) != 0) {
                        row.stringColumn("s", SYMBOLS[random.nextInt(SYMBOLS.length)]);
                    }
                    if (random.nextInt(25) != 0) {
                        row.doubleColumn("x", random.nextGaussian() * 100);
                    }
                    // two rows a second, so an ORDER BY ts has ties to keep in input order
                    row.at(BASE + day * DAY + j / 2 * 1_000_000L);
                }
            }
            for (int j = 0; j < 60; j++) {
                final CdbAppender row = appender.table("r").longColumn("w", random.nextInt(10))
                        .stringColumn("name", "r" + j);
                if (random.nextInt(10) != 0) {
                    row.longColumn("k", random.nextInt(40));
                }
                row.at(BASE + j);
            }
            for (int j = 0; j < 3; j++) {
                appender.table("c").longColumn("w", j).at(BASE + j);
            }
            appender.flush();
        }
    }

    @AfterAll
    static void close() {
        try {
            if (store != null) {
                store.close();
            }
        } finally {
            if (serial != null) {
                serial.close();
            }
        }
    }

    /// The fixture's left scan cuts into frames that end inside a partition, at a partition's end and in a partition
    /// smaller than a frame.
    @Test
    void theLeftTableSpansSeveralFrames() {
        final Plan.Join join = join("SELECT * FROM l JOIN r ON l.k = r.k");
        assertThat(join.left()).isInstanceOf(Plan.Scan.class);
        try (TableReader reader = store.reader("l")) {
            final List<Long> sizes = new ArrayList<>();
            for (final Frames.Frame frame : Frames.of((Plan.Scan) join.left(), reader)) {
                sizes.add(frame.toRow() - frame.fromRow());
            }
            assertThat(sizes).containsExactly(65_536L, 464L, 5L, 65_536L, 4L);
        }
    }

    /// Inner and left hash joins, either way round, with NULL keys and left keys the right does not have.
    @Test
    void hashJoinsMatchTheSerialJoin() {
        checkJoin("SELECT * FROM l JOIN r ON l.k = r.k");
        checkJoin("SELECT * FROM l LEFT JOIN r ON l.k = r.k");
        checkJoin("SELECT * FROM l JOIN r ON r.k = l.k");
        checkJoin("SELECT * FROM (SELECT ts, k, x FROM l WHERE x > 0) a LEFT JOIN r ON a.k = r.k");
    }

    /// Nested-loop joins, inner and left, whose condition is not one equality between the sides.
    @Test
    void loopJoinsMatchTheSerialJoin() {
        checkJoin("SELECT * FROM l JOIN r ON l.k = r.k AND r.w > 4");
        checkJoin("SELECT * FROM l LEFT JOIN r ON l.k = r.k + 1 AND r.w < 3");
    }

    /// A cross join gives every pair in left order then right order.
    @Test
    void crossJoinMatchesTheSerialJoin() {
        checkJoin("SELECT * FROM l CROSS JOIN c");
    }

    /// An empty right gives no row for an inner or cross join and every left row padded for a left join.
    @Test
    void anEmptyRightMatchesTheSerialJoin() {
        assertThat(checkJoin("SELECT * FROM l JOIN e ON l.k = e.k")).isZero();
        assertThat(checkJoin("SELECT * FROM l CROSS JOIN e")).isZero();
        assertThat(checkJoin("SELECT * FROM l LEFT JOIN e ON l.k = e.k")).isEqualTo(total());
        assertThat(checkJoin("SELECT * FROM l LEFT JOIN e ON l.k < e.k")).isEqualTo(total());
    }

    /// A join that multiplies its left rows keeps at most [JoinCursor.FrameJoin#FRAME_PAGES] pages per frame, plus the
    /// one that crosses the bound, and still answers the serial join's rows: frames of 1,000 left rows times 70 right
    /// rows join to 69 pages each, the last frame of 500 to 35.
    @Test
    void aFanOutJoinHoldsAtMostItsBoundPerFrame() {
        for (final int threads : POOLS) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(RowsFakes.table(),
                    List.of(RowsFakes.partition(0, FAN_LEFT_ROWS)));
            try (QueryPool pool = QueryPool.of(threads);
                 Cursor want = JoinCursor.open(new ScanCursor(FAN_SCAN, new RowsFakes.FakeReader(RowsFakes.table(),
                         List.of(RowsFakes.partition(0, FAN_LEFT_ROWS)))), RowsFakes.longs(FAN_RIGHT_ROWS, 64),
                         "cross", null, NOW, FAN_FIELDS);
                 JoinCursor.FrameJoin got = fanOut(reader, new QueryContext(null, pool))) {
                assertThat(sameRows(got, want)).as("pool=%d", threads)
                        .isEqualTo((long) FAN_LEFT_ROWS * FAN_RIGHT_ROWS);
                assertThat(got.mostPagesHeld()).as("pool=%d", threads)
                        .isEqualTo(JoinCursor.FrameJoin.FRAME_PAGES + 1);
            }
            assertThat(reader.closes()).as("pool=%d", threads).isEqualTo(1);
        }
    }

    /// A cancel between two pages of a frame fails the join's next page with the cancel's failure, and the close
    /// that follows releases the reader and the right once.
    @Test
    void aCancelStopsTheJoinAtTheNextPage() {
        for (final int threads : POOLS) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(RowsFakes.table(),
                    List.of(RowsFakes.partition(0, FAN_LEFT_ROWS)));
            final RowsFakes.ListCursor right = RowsFakes.longs(FAN_RIGHT_ROWS, 64);
            final CdbCancel cancel = new CdbCancel();
            try (QueryPool pool = QueryPool.of(threads);
                 Cursor join = JoinCursor.parallel(FrameSource.of(FAN_SCAN, reader, FAN_FRAME_ROWS,
                         new QueryContext(cancel, pool)), right, "cross", null, NOW, FAN_FIELDS)) {
                assertThat(join.next()).isNotNull();
                cancel.cancel();
                assertThatThrownBy(join::next).as("pool=%d", threads).isInstanceOf(CdbException.class);
            }
            assertThat(reader.closes()).as("pool=%d", threads).isEqualTo(1);
            assertThat(right.closed()).as("pool=%d", threads).isTrue();
        }
    }

    /// A join closed after its first page, with frames in flight, releases the reader once however often it is
    /// closed, closes the right, and gives no further page.
    @Test
    void aJoinClosedEarlyReleasesTheReaderAndTheRight() {
        for (final int threads : POOLS) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(RowsFakes.table(),
                    List.of(RowsFakes.partition(0, FAN_LEFT_ROWS)));
            final RowsFakes.ListCursor right = RowsFakes.longs(FAN_RIGHT_ROWS, 64);
            try (QueryPool pool = QueryPool.of(threads)) {
                final Cursor join = JoinCursor.parallel(FrameSource.of(FAN_SCAN, reader, FAN_FRAME_ROWS,
                        new QueryContext(null, pool)), right, "cross", null, NOW, FAN_FIELDS);
                assertThat(join.next()).isNotNull();
                join.close();
                join.close();
                assertThat(join.next()).isNull();
            }
            assertThat(reader.closes()).as("pool=%d", threads).isEqualTo(1);
            assertThat(right.closed()).as("pool=%d", threads).isTrue();
        }
    }

    /// row_number and lag with PARTITION BY, by one key and by two, ordered ascending and descending, ties by input
    /// order.
    @Test
    void rowNumberAndLagByPartitionMatchTheSerialWindow() {
        checkWindow("SELECT i, row_number() OVER (PARTITION BY s ORDER BY ts) FROM l");
        checkWindow("SELECT i, lag(x) OVER (PARTITION BY s ORDER BY ts), lag(i, 2, -1) OVER (PARTITION BY k ORDER BY "
                + "i DESC) FROM l");
        checkWindow("SELECT i, row_number() OVER (PARTITION BY k, s), lag(s) OVER (PARTITION BY k) FROM l");
    }

    /// Running aggregates with ORDER BY and whole-partition ones without, first and last among them.
    @Test
    void aggregatesByPartitionMatchTheSerialWindow() {
        checkWindow("SELECT i, sum(x) OVER (PARTITION BY s ORDER BY ts), avg(x) OVER (PARTITION BY k ORDER BY ts) "
                + "FROM l");
        checkWindow("SELECT i, sum(x) OVER (PARTITION BY s), avg(x) OVER (PARTITION BY k), count() OVER "
                + "(PARTITION BY k, s), max(i) OVER (PARTITION BY k) FROM l");
        checkWindow("SELECT i, first(x) OVER (PARTITION BY s ORDER BY ts), last(x) OVER (PARTITION BY k ORDER BY ts), "
                + "min(x) OVER (PARTITION BY s ORDER BY x DESC), first(s) OVER (PARTITION BY k) FROM l");
    }

    /// Without PARTITION BY the calls run in one ordered pass on the consumer, and still match.
    @Test
    void windowsWithoutPartitionMatchTheSerialWindow() {
        checkWindow("SELECT i, row_number() OVER (ORDER BY x), sum(x) OVER (ORDER BY ts), lag(x) OVER (), "
                + "count() OVER () FROM l");
        checkWindow("SELECT i, row_number() OVER (PARTITION BY s ORDER BY ts), row_number() OVER (ORDER BY ts) "
                + "FROM l WHERE x > 50");
    }

    /// A window function cdb plans but does not run fails at the first page with the serial window's failure.
    @Test
    void windowFunctionsTheSerialWindowRefusesFailAlike() {
        for (final String function : List.of("lead(x)", "first_value(x)", "last_value(x)")) {
            final Plan.Window window = window("SELECT i, " + function + " OVER (PARTITION BY s ORDER BY ts) FROM l");
            assertThatThrownBy(() -> drain(serialWindow(window), new ArrayList<>())).as(function)
                    .isInstanceOf(CdbException.class);
            for (final int threads : POOLS) {
                try (QueryPool pool = QueryPool.of(threads)) {
                    assertThatThrownBy(() -> drain(Executor.open(window, store, NOW, new QueryContext(null, pool)),
                            new ArrayList<>())).as("%s pool=%d", function, threads).isInstanceOf(CdbException.class);
                }
            }
        }
    }

    private static void ddl(final String sql) {
        Executor.execute((Plan.Change) Planner.plan(Parser.parse(sql), store::def, NOW), store, NOW);
    }

    private static long total() {
        long n = 0;
        for (final int rows : DAY_ROWS) {
            n += rows;
        }
        return n;
    }

    private static Plan plan(final String sql) {
        return Planner.plan(Parser.parse(sql), store::def, NOW);
    }

    private static Plan.Join join(final String sql) {
        final Plan.Join join = find(plan(sql), Plan.Join.class);
        assertThat(join).as(sql).isNotNull();
        assertThat(FrameSource.isSegment(join.left())).as(sql).isTrue();
        return join;
    }

    private static Plan.Window window(final String sql) {
        final Plan.Window window = find(plan(sql), Plan.Window.class);
        assertThat(window).as(sql).isNotNull();
        assertThat(FrameSource.isSegment(window.child())).as(sql).isTrue();
        return window;
    }

    private static <T extends Plan> T find(final Plan plan, final Class<T> type) {
        if (type.isInstance(plan)) {
            return type.cast(plan);
        }
        return switch (plan) {
            case Plan.Project p -> find(p.child(), type);
            case Plan.Filter f -> find(f.child(), type);
            case Plan.Sort s -> find(s.child(), type);
            case Plan.Limit l -> find(l.child(), type);
            case Plan.Distinct d -> find(d.child(), type);
            default -> null;
        };
    }

    /// A context on the pool of no worker, for the serial operator's input.
    private static QueryContext serialContext() {
        return new QueryContext(null, serial);
    }

    /// The serial join over the left's rows read as one cursor.
    private static Cursor serialJoin(final Plan.Join join) {
        return JoinCursor.open(Executor.open(join.left(), store, NOW, serialContext()),
                Executor.open(join.right(), store, NOW, serialContext()), join.kind(), join.on(), NOW, join.fields());
    }

    /// The serial window over its input read as one cursor.
    private static Cursor serialWindow(final Plan.Window window) {
        return new WindowCursor(Executor.open(window.child(), store, NOW, serialContext()), window, NOW);
    }

    /// The join of `sql` at every pool size against the serial join; the number of rows.
    private static long checkJoin(final String sql) {
        final Plan.Join join = join(sql);
        final List<List<Object>> want = new ArrayList<>();
        final long rows = drain(serialJoin(join), want);
        for (final int threads : POOLS) {
            try (QueryPool pool = QueryPool.of(threads)) {
                final List<List<Object>> got = new ArrayList<>();
                assertThat(drain(Executor.open(join, store, NOW, new QueryContext(null, pool)), got))
                        .as("%s pool=%d", sql, threads).isEqualTo(rows);
                assertSame(got, want, sql, threads);
            }
        }
        return rows;
    }

    /// The window of `sql` at every pool size against the serial window.
    private static void checkWindow(final String sql) {
        final Plan.Window window = window(sql);
        final List<List<Object>> want = new ArrayList<>();
        final long rows = drain(serialWindow(window), want);
        assertThat(rows).as(sql).isPositive();
        for (final int threads : POOLS) {
            try (QueryPool pool = QueryPool.of(threads)) {
                final List<List<Object>> got = new ArrayList<>();
                assertThat(drain(Executor.open(window, store, NOW, new QueryContext(null, pool)), got))
                        .as("%s pool=%d", sql, threads).isEqualTo(rows);
                assertSame(got, want, sql, threads);
            }
        }
    }

    /// Row by row, so a failure names the first row that differs rather than printing every row.
    private static void assertSame(
            final List<List<Object>> got,
            final List<List<Object>> want,
            final String sql,
            final int threads
    ) {
        for (int r = 0; r < want.size(); r++) {
            if (!got.get(r).equals(want.get(r))) {
                assertThat(got.get(r)).as("%s pool=%d row=%d", sql, threads, r).isEqualTo(want.get(r));
            }
        }
    }

    /// Every row of `cursor` into `out`, each cell as its raw value (a DOUBLE compared by its bits), the cursor closed;
    /// the number of rows.
    private static long drain(final Cursor cursor, final List<List<Object>> out) {
        long rows = 0;
        try (cursor) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int r = 0; r < page.size; r++) {
                    out.add(row(page, r));
                    rows++;
                }
            }
        }
        return rows;
    }

    /// Row `r` of `page`, each cell as its raw value (a DOUBLE compared by its bits).
    private static List<Object> row(final Page page, final int r) {
        final List<Object> row = new ArrayList<>(page.columns.length);
        for (final Vector v : page.columns) {
            row.add(switch (v.type) {
                case LONG, TIMESTAMP -> v.longs[r];
                case DOUBLE -> Double.doubleToRawLongBits(v.doubles[r]);
                case BOOLEAN -> v.booleans[r];
                case SYMBOL, VARCHAR -> String.valueOf(v.texts[r]);
            });
        }
        return row;
    }

    /// The cross join of the fake table's partition of [#FAN_LEFT_ROWS] rows, in frames of [#FAN_FRAME_ROWS], with
    /// [#FAN_RIGHT_ROWS] right rows.
    private static JoinCursor.FrameJoin fanOut(final RowsFakes.FakeReader reader, final QueryContext ctx) {
        return (JoinCursor.FrameJoin) JoinCursor.parallel(FrameSource.of(FAN_SCAN, reader, FAN_FRAME_ROWS, ctx),
                RowsFakes.longs(FAN_RIGHT_ROWS, 64), "cross", null, NOW, FAN_FIELDS);
    }

    /// Reads `got` and `want` in step, row by row whatever their pages, without holding their rows; fails at the first
    /// row that differs or when one ends first. The number of rows.
    private static long sameRows(final Cursor got, final Cursor want) {
        long rows = 0;
        Page a = null;
        Page b = null;
        int ra = 0;
        int rb = 0;
        while (true) {
            if (a == null || ra == a.size) {
                a = got.next();
                ra = 0;
            }
            if (b == null || rb == b.size) {
                b = want.next();
                rb = 0;
            }
            if (a == null || b == null) {
                assertThat(a).as("both end at row %d", rows).isNull();
                assertThat(b).as("both end at row %d", rows).isNull();
                return rows;
            }
            final List<Object> left = row(a, ra++);
            final List<Object> right = row(b, rb++);
            if (!left.equals(right)) {
                assertThat(left).as("row=%d", rows).isEqualTo(right);
            }
            rows++;
        }
    }
}
