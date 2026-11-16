package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.ColumnUse;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;

/// [GroupByCursor] and [SampleByCursor] over a segment's frames, merged in frame order, answer what the serial
/// operator answers over the same rows, the same bits at every pool size and on every run. The table holds three day
/// partitions of over 65,536 rows each, so every partition is cut into several frames: keys first seen in a later
/// frame or partition, first and last values across frame edges, NULL keys and values, runs of equal timestamps and a
/// three-hour gap for the fills. Every value is exact in a double sum, so a sum's rounding cannot depend on the
/// frames. Seed in the output; replay with `-Dcdb.parallel.seed=<seed>`.
class ParallelGroupByTest {

    private static final long SEED = Long.getLong("cdb.parallel.seed", System.nanoTime());
    private static final long DAY = 86_400_000_000L;
    private static final long HOUR = 3_600_000_000L;
    private static final long DAY0 = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli() * 1_000L;
    private static final long NOW = DAY0 + 10 * DAY;
    // over 65,536 rows a partition even with the gap, so each partition makes at least two frames
    private static final int ROWS_PER_DAY = 150_000;
    private static final int[] POOL_SIZES = {0, 1, 3, 11};
    private static final int REPEATS = 20;
    private static final String[] KEYS = {"a", "b", "c", "d"};
    // a full frame runs 64 pages, so a cancel seen inside one misses it rarely: 20 tries put a miss on every try out
    // of reach, while a missing check still fails within seconds
    private static final int CANCEL_ATTEMPTS = 20;
    // far above a query over the table's 450,000 rows, so only a hung query reaches it
    private static final Duration WAIT = Duration.ofSeconds(30L);

    @TempDir
    private static Path root;

    private static List<QueryPool> pools;
    private static Store store;

    /// Opens the store and writes the table `t` (the random rows) and the table `s` (one huge value, then ones).
    @BeforeAll
    static void fill() {
        System.out.println("ParallelGroupByTest seed=" + SEED);
        store = Store.open(root);
        pools = new ArrayList<>();
        for (final int size : POOL_SIZES) {
            pools.add(QueryPool.of(size));
        }
        run("CREATE TABLE t (ts TIMESTAMP, k SYMBOL, x LONG, d DOUBLE, v VARCHAR) TIMESTAMP(ts) PARTITION BY DAY");
        run("CREATE TABLE s (ts TIMESTAMP, d DOUBLE) TIMESTAMP(ts) PARTITION BY DAY");
        final Random random = new Random(SEED);
        try (CdbAppender appender = store.appender()) {
            String key = KEYS[0];
            for (int day = 0; day < 3; day++) {
                for (int i = 0; i < ROWS_PER_DAY; i++) {
                    // two rows a second, so each timestamp is held by two rows
                    final long ts = DAY0 + day * DAY + (i / 2) * 1_000_000L;
                    if (day == 1 && ts - DAY0 - DAY >= 5 * HOUR && ts - DAY0 - DAY < 8 * HOUR) {
                        continue;
                    }
                    if (random.nextInt(10) == 0) {
                        key = KEYS[random.nextInt(KEYS.length)];
                    }
                    String rowKey = key;
                    if (day == 0 && i >= 70_000 && random.nextInt(50) == 0) {
                        rowKey = "late";
                    } else if (day == 2 && i >= 140_000 && random.nextInt(20) == 0) {
                        rowKey = "tail";
                    }
                    appender.table("t");
                    if (random.nextInt(100) != 0) {
                        appender.symbol("k", rowKey);
                    }
                    if (random.nextInt(50) != 0) {
                        appender.longColumn("x", random.nextInt(2_000_001) - 1_000_000);
                    }
                    if (random.nextInt(50) != 0) {
                        appender.doubleColumn("d", (random.nextInt(8_001) - 4_000) / 4.0);
                    }
                    if (random.nextInt(30) != 0) {
                        appender.stringColumn("v", "v" + random.nextInt(500));
                    }
                    appender.at(ts);
                }
            }
            for (int i = 0; i < ROWS_PER_DAY; i++) {
                appender.table("s").doubleColumn("d", i == 0 ? 1e16 : 1.0).at(DAY0 + i * 100_000L);
            }
            appender.flush();
        }
    }

    /// Closes the pools and the store.
    @AfterAll
    static void close() {
        for (final QueryPool pool : pools) {
            pool.close();
        }
        store.close();
    }

    /// GROUP BY one key and two keys, with every kind of aggregate: the groups in first-seen order (the key first
    /// seen in a partition's second frame, the one first seen in the last frame), first and last across frame edges.
    @Test
    void groupByAnswersAsSerial() {
        alike("SELECT k, count(), count(x), sum(x), sum(d), avg(x), min(x), max(d), min(v), max(v), first(x),"
                + " last(x), first(v), last(v), first(ts), last(ts), count_distinct(v), percentile_disc(x, 0.9)"
                + " FROM t GROUP BY k");
        alike("SELECT k, x % 3, count(), sum(d), last(v) FROM t GROUP BY k, x % 3");
        alike("SELECT v, count(), first(k), last(k) FROM t GROUP BY v");
    }

    /// A whole-input aggregate: one row, its first and last over every frame.
    @Test
    void keylessAggregateAnswersAsSerial() {
        alike("SELECT count(), sum(x), sum(d), min(d), max(x), first(ts), last(ts), first(k), last(k), first(v),"
                + " last(v) FROM t");
    }

    /// A filter in the segment and a time range that starts and ends inside partitions.
    @Test
    void filteredAndRangedGroupByAnswersAsSerial() {
        alike("SELECT k, count(), sum(x), first(d), last(d) FROM t WHERE x > 0 GROUP BY k");
        alike("SELECT k, count(), sum(x), first(x), last(x) FROM t WHERE ts >= '2026-11-10T12:00:00.000000Z'"
                + " AND ts < '2026-11-12T03:00:00.000000Z' GROUP BY k");
    }

    /// No row in range, or none passing the filter: a keyed aggregate answers no row, a keyless one its one row.
    @Test
    void emptyInputAnswersAsSerial() {
        assertThat(alike("SELECT count(), sum(x), first(v) FROM t WHERE ts >= '2026-11-15T00:00:00.000000Z'"))
                .hasSize(1);
        assertThat(alike("SELECT count(), sum(x) FROM t WHERE x > 5000000")).hasSize(1);
        assertThat(alike("SELECT k, count() FROM t WHERE ts >= '2026-11-15T00:00:00.000000Z' GROUP BY k")).isEmpty();
        assertThat(alike("SELECT ts, count() FROM t WHERE ts >= '2026-11-15T00:00:00.000000Z' SAMPLE BY 1h"))
                .isEmpty();
        assertThat(alike("SELECT ts, k, count() FROM t WHERE x > 5000000 SAMPLE BY 1h FILL(PREV)")).isEmpty();
    }

    /// SAMPLE BY without a FILL, with and without keys, fixed and calendar strides, aligned to calendar.
    @Test
    void sampleByAnswersAsSerial() {
        alike("SELECT ts, count(), sum(x), sum(d), first(x), last(x), first(v), last(v) FROM t SAMPLE BY 1h");
        alike("SELECT ts, k, count(), sum(d), first(x), last(v) FROM t SAMPLE BY 30m");
        alike("SELECT ts, k, count(), max(x) FROM t SAMPLE BY 1d ALIGN TO CALENDAR");
        alike("SELECT ts, count(), min(d) FROM t SAMPLE BY 1M");
        alike("SELECT ts, k, count(), last(x) FROM t WHERE x < 0 AND ts >= '2026-11-10T20:00:00.000000Z'"
                + " AND ts < '2026-11-12T01:00:00.000000Z' SAMPLE BY 2h");
    }

    /// Every FILL over the gap, keyed and keyless: each bucket holds every key in first-seen order over the whole
    /// input, the keys first seen in a later frame among them.
    @Test
    void sampleByFillsAnswerAsSerial() {
        for (final String fill : List.of("NULL", "PREV", "LINEAR", "7")) {
            alike("SELECT ts, count(), sum(x), last(d) FROM t SAMPLE BY 1h FILL(" + fill + ')');
            alike("SELECT ts, k, count(), sum(x), last(d), first(v) FROM t SAMPLE BY 1h FILL(" + fill + ')');
        }
        alike("SELECT ts, k, count(), sum(x), last(d), first(x) FROM t SAMPLE BY 20m FILL(NULL, PREV, LINEAR, 0)");
    }

    /// The same query, 20 times at each pool size, answers the same bits each time.
    @Test
    void repeatedRunsAnswerTheSameBits() {
        final List<String> queries = List.of(
                "SELECT k, count(), sum(x), sum(d), first(x), last(v) FROM t GROUP BY k",
                "SELECT ts, k, count(), sum(d), last(x) FROM t SAMPLE BY 1h FILL(PREV)",
                "SELECT count(), sum(d), first(v), last(v) FROM t");
        for (final String sql : queries) {
            final List<List<Object>> first = parallel(sql, pools.getFirst());
            for (final QueryPool pool : pools) {
                for (int run = 0; run < REPEATS; run++) {
                    assertThat(parallel(sql, pool)).as("%s threads=%d run=%d", sql, pool.threads(), run)
                            .isEqualTo(first);
                }
            }
        }
    }

    /// A double sum adds each frame's own sum in frame order: a huge first value, then ones that it absorbs one by
    /// one but not once summed per frame. The serial operator over a single frame gives the same.
    @Test
    void doubleSumsAddEachFramesSumInFrameOrder() {
        for (final QueryPool pool : pools) {
            assertThat(parallel("SELECT sum(d) FROM s", pool)).containsExactly(List.of(bits(frameSums("s", pool))));
            final List<List<Object>> sampled = parallel("SELECT ts, sum(d) FROM s SAMPLE BY 1d", pool);
            assertThat(sampled).hasSize(1);
            assertThat(sampled.getFirst().get(1)).isEqualTo(bits(frameSums("s", pool)));
        }
    }

    /// A cancel set while a frame reads its pages stops that frame at its next page: the failure is thrown from the
    /// frame's own page loop, not from the pool's check before a frame starts, and the cursor still closes. The cancel
    /// is set once a thread is seen inside a frame; one that lands after the frame's last page misses it, and the
    /// query runs again.
    @Test
    void cancelInsideAFrameStopsItAtItsNextPage() throws InterruptedException {
        for (final String sql : List.of("SELECT k, count_distinct(v), percentile_disc(x, 0.9) FROM t GROUP BY k",
                "SELECT ts, k, count_distinct(v) FROM t SAMPLE BY 1h FILL(NULL)")) {
            for (final QueryPool pool : List.of(pools.getFirst(), pools.getLast())) {
                assertThat(cancelsInsideAFrame(sql, pool)).as("%s threads=%d", sql, pool.threads()).isTrue();
            }
        }
    }

    // true once a cancel set while a thread runs a frame fails the query from inside that frame's page loop
    private static boolean cancelsInsideAFrame(final String sql, final QueryPool pool) throws InterruptedException {
        for (int attempt = 0; attempt < CANCEL_ATTEMPTS; attempt++) {
            final CdbCancel cancel = new CdbCancel();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final Thread query = Thread.ofPlatform().name("parallel-group-by-test-query").start(() -> {
                try {
                    final Cursor cursor = Executor.open(node(sql).plan(), store, NOW, new QueryContext(cancel, pool));
                    try {
                        rows(cursor);
                    } finally {
                        // rows closed it already: a second close is a no-op
                        cursor.close();
                    }
                } catch (final RuntimeException e) {
                    failure.set(e);
                } finally {
                    done.countDown();
                }
            });
            final long start = System.nanoTime();
            while (done.getCount() > 0 && !inFrame() && System.nanoTime() - start < WAIT.toNanos()) {
                Thread.onSpinWait();
            }
            cancel.cancel();
            assertThat(done.await(WAIT.toNanos(), TimeUnit.NANOSECONDS)).as("query ended").isTrue();
            assertThat(query.join(WAIT)).as("query thread ended").isTrue();
            final Throwable thrown = failure.get();
            if (thrown != null) {
                assertThat(thrown).isInstanceOf(CdbException.class);
                if (inFrame(thrown.getStackTrace())) {
                    return true;
                }
            }
        }
        return false;
    }

    // whether any thread is inside a frame's partial: a pool worker, or the consuming thread at pool size 0
    private static boolean inFrame() {
        for (final StackTraceElement[] stack : Thread.getAllStackTraces().values()) {
            if (inFrame(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inFrame(final StackTraceElement[] stack) {
        for (final StackTraceElement call : stack) {
            if (call.getMethodName().equals("partial")
                    && (call.getClassName().equals(GroupByCursor.class.getName())
                    || call.getClassName().equals(SampleByCursor.class.getName()))) {
                return true;
            }
        }
        return false;
    }

    private static void run(final String sql) {
        Executor.execute((Plan.Change) Planner.plan(Parser.parse(sql), store::def, NOW), store, NOW);
    }

    /// Runs `sql` serially and at every pool size, asserts each parallel answer is the serial one, and returns it.
    private static List<List<Object>> alike(final String sql) {
        final Node node = node(sql);
        final Map<Plan.Scan, boolean[]> reads = ColumnUse.of(node.plan());
        final List<List<Object>> serial = rows(node.serial().apply(Executor.serialSegment(node.child(), store, NOW,
                reads)));
        for (final QueryPool pool : pools) {
            assertThat(parallel(sql, pool)).as("%s threads=%d", sql, pool.threads()).isEqualTo(serial);
        }
        return serial;
    }

    private static List<List<Object>> parallel(final String sql, final QueryPool pool) {
        return rows(Executor.open(node(sql).plan(), store, NOW, new QueryContext(null, pool)));
    }

    /// The GROUP BY or SAMPLE BY node of `sql`'s plan, asserted to be over a segment, so that it runs per frame.
    private static Node node(final String sql) {
        final Node node = node(Planner.plan(Parser.parse(sql), store::def, NOW));
        assertThat(FrameSource.isSegment(node.child())).as("segment under %s", sql).isTrue();
        return node;
    }

    private static Node node(final Plan plan) {
        return switch (plan) {
            case Plan.Aggregate a -> new Node(a, a.child(), c -> new GroupByCursor(c, a, NOW));
            case Plan.SampleBy s -> new Node(s, s.child(), c -> new SampleByCursor(c, s, NOW));
            case Plan.Project p -> node(p.child());
            case Plan.Sort s -> node(s.child());
            case Plan.Limit l -> node(l.child());
            case Plan.Filter f -> node(f.child());
            case Plan.Scan _, Plan.Source _, Plan.LatestOn _, Plan.Window _, Plan.Distinct _, Plan.SetOp _,
                 Plan.Join _, Plan.CreateTable _, Plan.Update _, Plan.Insert _, Plan.AddColumns _, Plan.SetTtl _ ->
                    throw new IllegalStateException("no aggregate in the plan [plan=" + plan + ']');
        };
    }

    /// Column `d` of table `name` summed per frame of its scan, each frame from 0.0 in scan order, then the frames'
    /// sums added in frame order: what a merge in frame order gives.
    private static double frameSums(final String name, final QueryPool pool) {
        final Node node = node("SELECT sum(d) FROM " + name);
        final Plan segment = node.child();
        int column = -1;
        for (int c = 0; c < segment.fields().size(); c++) {
            if ("d".equals(segment.fields().get(c).name())) {
                column = c;
            }
        }
        assertThat(column).as("column d of the segment").isNotNegative();
        double total = 0.0;
        try (FrameSource source = FrameSource.of(segment, store, NOW, ColumnUse.of(node.plan()),
                new QueryContext(null, pool))) {
            for (int frame = 0; frame < source.frames(); frame++) {
                double sum = 0.0;
                try (Cursor rows = source.open(frame)) {
                    Page page;
                    while ((page = rows.next()) != null) {
                        for (int r = 0; r < page.size; r++) {
                            sum += page.columns[column].getDouble(r);
                        }
                    }
                }
                total += sum;
            }
        }
        return total;
    }

    private static Object bits(final double value) {
        return Double.doubleToRawLongBits(value);
    }

    /// Every row of `cursor`, which it closes, each cell its raw value: a double as its bits, a text or NULL text as
    /// its string or null.
    private static List<List<Object>> rows(final Cursor cursor) {
        final List<List<Object>> out = new ArrayList<>();
        try (cursor) {
            Page page;
            while ((page = cursor.next()) != null) {
                for (int r = 0; r < page.size; r++) {
                    final List<Object> row = new ArrayList<>(page.columns.length);
                    for (final Vector v : page.columns) {
                        row.add(switch (v.type) {
                            case LONG, TIMESTAMP -> v.getLong(r);
                            case DOUBLE -> bits(v.getDouble(r));
                            case BOOLEAN -> v.getBoolean(r);
                            case SYMBOL, VARCHAR -> v.textAt(r) == null ? null : v.textAt(r).toString();
                        });
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /// An aggregate node of a plan, its input segment, and the serial operator over a cursor of that input.
    private record Node(Plan plan, Plan child, Function<Cursor, Cursor> serial) {
    }
}
