package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.plan.Planner;
import dev.marregui.comando.cdb.sql.Parser;
import dev.marregui.comando.cdb.store.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// ORDER BY and LIMIT per frame answer what the serial cursors answer over the frames read one after the other, at
/// every pool size: each frame sorted (or its first k rows kept) and the runs merged with ties to the earlier frame,
/// and a LIMIT that stops reading frames once its window is full. Seeds in the output; replay with
/// `-Dcdb.exec.seed=<seed>`.
class ParallelSortTest {

    /// The pool sizes every case runs at: no worker, one, a few, more than the frames of most cases.
    private static final int[] POOLS = {0, 1, 3, 11};
    private static final int CASES = 150;

    @TempDir
    private Path root;

    /// A whole-input sort of random frames, some empty, with ties and NULLs, keys of every type in either direction.
    @Test
    void fullSortMatchesTheSerialSort() {
        final Random random = ReferenceCursors.random("fullSortMatchesTheSerialSort");
        for (int i = 0; i < CASES; i++) {
            check(random, keys(random), -1);
        }
    }

    /// A sort with a limit: 0, 1, smaller than a frame, larger than a frame, the whole input and past an int.
    @Test
    void limitedSortMatchesTheSerialSort() {
        final Random random = ReferenceCursors.random("limitedSortMatchesTheSerialSort");
        final long[] limits = {0, 1, 7, 300, 2_500, 100_000, Integer.MAX_VALUE + 1L};
        for (int i = 0; i < CASES; i++) {
            check(random, keys(random), limits[random.nextInt(limits.length)]);
        }
    }

    /// Rows equal on the key keep the frame order, then their order inside the frame, ascending and descending.
    @Test
    void tiesAcrossFramesKeepTheFrameOrder() {
        final Object[][][] frames = new Object[3][][];
        long ts = 0;
        for (int f = 0; f < frames.length; f++) {
            frames[f] = new Object[1_500][];
            for (int r = 0; r < frames[f].length; r++) {
                frames[f][r] = new Object[] {ts++, "a", 1L, r % 2 == 0 ? null : 0.5, true, "v"};
            }
        }
        for (final boolean descending : new boolean[] {false, true}) {
            final List<List<Object>> out = sortAtEveryPool(frames, 700, List.of(new Plan.SortKey(2, descending)), -1);
            final List<Long> order = new ArrayList<>();
            for (final List<Object> row : out) {
                order.add((Long) row.getFirst());
            }
            assertThat(order).as("descending=%s", descending).isEqualTo(RowsFakes.range(0, ts));
            final List<List<Object>> byDouble = sortAtEveryPool(frames, 700,
                    List.of(new Plan.SortKey(3, descending)), 1_000);
            // a DOUBLE NULL sorts above every number: last ascending, first descending, ties in frame order
            final List<Long> expected = new ArrayList<>();
            final boolean nullsFirst = descending;
            for (final boolean nulls : new boolean[] {nullsFirst, !nullsFirst}) {
                for (long t = 0; t < ts; t++) {
                    if ((t % 1_500 % 2 == 0) == nulls) {
                        expected.add(t);
                    }
                }
            }
            final List<Long> got = new ArrayList<>();
            for (final List<Object> row : byDouble) {
                got.add((Long) row.getFirst());
            }
            assertThat(got).as("descending=%s", descending).isEqualTo(expected.subList(0, 1_000));
        }
    }

    /// A frame that fails fails the query; every frame opened and the source are closed, the source once.
    @Test
    void aFailedFrameClosesEverything() {
        final Random random = ReferenceCursors.random("aFailedFrameClosesEverything");
        final Object[][][] frames = {ReferenceCursors.rows(random, 500, 10, 10, true),
                ReferenceCursors.rows(random, 500, 10, 10, true), ReferenceCursors.rows(random, 500, 10, 10, true)};
        for (final int threads : POOLS) {
            final FrameRows rows = new FrameRows(frames, 100, 1);
            try (QueryPool pool = QueryPool.of(threads)) {
                final Cursor cursor = rows.sort(pool, List.of(new Plan.SortKey(2, false)), -1);
                assertThatThrownBy(cursor::next).as("threads=%d", threads).isInstanceOf(CdbException.class);
                assertThatThrownBy(cursor::next).as("threads=%d, the failure again", threads)
                        .isInstanceOf(CdbException.class);
                cursor.close();
                cursor.close();
            }
            assertThat(rows.sourceCloses.get()).as("threads=%d", threads).isEqualTo(1);
            rows.assertOpenedClosed();
        }
    }

    /// A sort closed before its first page never starts a frame and closes the source.
    @Test
    void closedBeforeReadingClosesTheSource() {
        final FrameRows rows = new FrameRows(new Object[][][] {new Object[0][]}, 10, -1);
        try (QueryPool pool = QueryPool.of(1)) {
            rows.sort(pool, List.of(new Plan.SortKey(2, false)), -1).close();
        }
        assertThat(rows.sourceCloses.get()).isEqualTo(1);
        assertThat(rows.opens.get()).isZero();
    }

    /// LIMIT over frames read in order: the serial answer for bounds of every sign, and with non-negative bounds the
    /// input closed as soon as the page holding the last row is pulled, so no later frame is opened.
    @Test
    void limitStopsOpeningFramesOnceMet() {
        final Random random = ReferenceCursors.random("limitStopsOpeningFramesOnceMet");
        final Object[][][] frames = new Object[10][][];
        for (int f = 0; f < frames.length; f++) {
            frames[f] = ReferenceCursors.rows(random, 2_000, 50, 10, true);
        }
        // frames of one full page each, so the page holding the window's last row ends the window
        final Object[][][] paged = new Object[10][][];
        for (int f = 0; f < paged.length; f++) {
            paged[f] = ReferenceCursors.rows(random, Page.CAPACITY, 50, 10, true);
        }
        final FrameRows rows = new FrameRows(paged, Page.CAPACITY, -1);
        final FrameChain chain = rows.chain();
        final LimitCursor cursor = new LimitCursor(chain, 0, 2L * Page.CAPACITY);
        assertThat(cursor.next().size).isEqualTo(Page.CAPACITY);
        assertThat(chain.closed).as("open while the window is not full").isFalse();
        assertThat(cursor.next().size).isEqualTo(Page.CAPACITY);
        assertThat(chain.closed).as("closed with the page that met the limit, before the next call").isTrue();
        assertThat(rows.opens.get()).as("frames opened for two frames' rows").isEqualTo(2);
        assertThat(cursor.next()).isNull();
        cursor.close();
        final long[][] bounds = {{0, 1}, {0, 4_000}, {1_999, 2_001}, {-1, 0}, {-2_500, 0}, {0, -2_001}, {-5_000, -10},
            {3_000, 100_000}, {12_000, 3}};
        for (final long[] b : bounds) {
            final FrameRows each = new FrameRows(frames, 300, -1);
            final List<List<Object>> expected = ReferenceCursors.drain(new LimitCursor(each.all(), b[0], b[1]));
            final FrameChain input = each.chain();
            assertThat(ReferenceCursors.drain(new LimitCursor(input, b[0], b[1])))
                    .as("lo=%d hi=%d", b[0], b[1]).isEqualTo(expected);
            assertThat(input.closed).isTrue();
            each.assertOpenedClosed();
        }
    }

    /// Through the executor over a table of several partitions, at every pool size: ORDER BY with and without a
    /// limit and LIMIT alone answer what the serial cursors answer over the table's rows in scan order.
    @Test
    void queriesMatchTheSerialCursorsAtEveryPoolSize() {
        final Random random = ReferenceCursors.random("queriesMatchTheSerialCursorsAtEveryPoolSize");
        final long now = 10_000_000_000_000L;
        try (Store store = Store.open(root.resolve("db"))) {
            final String create = "CREATE TABLE t (ts TIMESTAMP, s SYMBOL, n LONG, d DOUBLE) TIMESTAMP(ts)"
                    + " PARTITION BY HOUR";
            Executor.execute((Plan.Change) Planner.plan(Parser.parse(create), store::def, now), store, now);
            // three hours of 70,000 rows: more than one frame of 65,536 rows per partition
            try (CdbAppender appender = store.appender()) {
                for (int h = 0; h < 3; h++) {
                    for (int r = 0; r < 70_000; r++) {
                        appender.table("t").symbol("s", "s" + random.nextInt(5)).longColumn("n", random.nextInt(50));
                        if (random.nextInt(10) != 0) {
                            appender.doubleColumn("d", random.nextInt(20) - 9.5);
                        }
                        appender.at(h * 3_600_000_000L + r * 50_000L);
                    }
                }
                appender.flush();
            }
            final List<Plan.SortKey> byN = List.of(new Plan.SortKey(2, true), new Plan.SortKey(3, false));
            final String[] sorts = {
                    "SELECT * FROM t ORDER BY n DESC, d", "SELECT * FROM t ORDER BY n DESC, d LIMIT 70"};
            final long[][] limits = {{0, 10}, {65_530, 65_540}, {-5, 0}, {100_000, 140_010}, {0, -209_990}};
            for (final int threads : POOLS) {
                try (QueryPool pool = QueryPool.of(threads)) {
                    final QueryContext ctx = new QueryContext(null, pool);
                    final List<List<Object>> scan = query(store, "SELECT * FROM t", ctx, now);
                    assertThat(scan).hasSize(210_000);
                    final List<List<Object>> sorted = ReferenceCursors.drain(ReferenceCursors.sort(cursor(scan), byN));
                    assertThat(query(store, sorts[0], ctx, now)).as("threads=%d", threads).isEqualTo(sorted);
                    assertThat(query(store, sorts[1], ctx, now)).as("threads=%d", threads)
                            .isEqualTo(sorted.subList(0, 70));
                    for (final long[] b : limits) {
                        final String sql = "SELECT * FROM t LIMIT " + b[0] + ", " + b[1];
                        assertThat(query(store, sql, ctx, now)).as("threads=%d %s", threads, sql)
                                .isEqualTo(ReferenceCursors.drain(new LimitCursor(cursor(scan), b[0], b[1])));
                    }
                }
            }
        }
    }

    private static List<List<Object>> query(
            final Store store,
            final String sql,
            final QueryContext ctx,
            final long now
    ) {
        final Plan plan = Planner.plan(Parser.parse(sql), store::def, now);
        return ReferenceCursors.drain(Executor.open(plan, store, now, ctx));
    }

    /// The rows as a cursor of full pages.
    private static Cursor cursor(final List<List<Object>> rows) {
        final List<Field> fields = List.of(RowsFakes.field("ts", CdbType.TIMESTAMP),
                RowsFakes.field("s", CdbType.SYMBOL),
                RowsFakes.field("n", CdbType.LONG),
                RowsFakes.field("d", CdbType.DOUBLE));
        return RowsFakes.cursor(fields, rows.size(), Page.CAPACITY, r -> rows.get(r).toArray());
    }

    /// One to three keys over the columns of [ReferenceCursors#FIELDS], each ascending or descending.
    private static List<Plan.SortKey> keys(final Random random) {
        final List<Plan.SortKey> keys = new ArrayList<>();
        final int n = 1 + random.nextInt(3);
        for (int k = 0; k < n; k++) {
            keys.add(new Plan.SortKey(random.nextInt(ReferenceCursors.FIELDS.size()), random.nextBoolean()));
        }
        return keys;
    }

    /// Random frames sorted at every pool size, each compared with the serial sort of the frames one after another.
    private static void check(final Random random, final List<Plan.SortKey> keys, final long limit) {
        final Object[][][] frames = new Object[1 + random.nextInt(12)][][];
        final int cardinality = 1 + random.nextInt(random.nextBoolean() ? 4 : 60);
        final int nulls = random.nextInt(40);
        for (int f = 0; f < frames.length; f++) {
            final int n = random.nextInt(5) == 0 ? 0 : ReferenceCursors.size(random);
            frames[f] = ReferenceCursors.rows(random, n, cardinality, nulls, random.nextBoolean());
        }
        final int pageSize = ReferenceCursors.pageSize(random);
        sortAtEveryPool(frames, pageSize, keys, limit);
    }

    /// The frames sorted at every pool size, each answer the serial one, the frames and the source closed.
    private static List<List<Object>> sortAtEveryPool(
            final Object[][][] frames,
            final int pageSize,
            final List<Plan.SortKey> keys,
            final long limit
    ) {
        final List<List<Object>> expected = RowsFakes.drain(
                new SortCursor(new FrameRows(frames, pageSize, -1).all(), keys, limit)).rows();
        for (final int threads : POOLS) {
            final FrameRows rows = new FrameRows(frames, pageSize, -1);
            final RowsFakes.Drained out;
            try (QueryPool pool = QueryPool.of(threads)) {
                out = RowsFakes.drain(rows.sort(pool, keys, limit));
            }
            final String what = "threads=" + threads + ", frames=" + frames.length + ", keys=" + keys + ", limit="
                    + limit + ", pageSize=" + pageSize;
            assertThat(out.rows()).as(what).isEqualTo(expected);
            assertThat(out.packed()).as(what).isTrue();
            assertThat(rows.sourceCloses.get()).as(what).isEqualTo(1);
            assertThat(rows.opens.get()).as(what).isEqualTo(frames.length);
            rows.assertOpenedClosed();
        }
        return expected;
    }

    /// Frames of rows of [ReferenceCursors#FIELDS], each opened at most once in pages of `pageSize`, frame `failing`
    /// (-1 for none) failing at its first page; counts the frames opened and the closes of the source.
    private static final class FrameRows {

        private final Object[][][] frames;
        private final int pageSize;
        private final int failing;
        private final AtomicReferenceArray<RowsFakes.ListCursor> opened;
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger sourceCloses = new AtomicInteger();

        FrameRows(final Object[][][] frames, final int pageSize, final int failing) {
            this.frames = frames;
            this.pageSize = pageSize;
            this.failing = failing;
            opened = new AtomicReferenceArray<>(frames.length);
        }

        Cursor open(final int frame) {
            final Object[][] rows = frames[frame];
            final RowsFakes.ListCursor list = RowsFakes.cursor(ReferenceCursors.FIELDS, rows.length, pageSize,
                    r -> rows[r]);
            assertThat(opened.compareAndSet(frame, null, list)).as("frame %d opened once", frame).isTrue();
            opens.incrementAndGet();
            return frame != failing ? list : new Failing(list);
        }

        /// The parallel sort over these frames on `pool`.
        Cursor sort(final QueryPool pool, final List<Plan.SortKey> keys, final long limit) {
            final QueryContext ctx = new QueryContext(null, pool);
            return SortCursor.merged(ReferenceCursors.FIELDS, keys, limit, frames.length, this::open,
                    work -> pool.submit(frames.length, work, ctx), sourceCloses::incrementAndGet);
        }

        /// Every frame's rows, one frame after the other: the serial operator's input.
        Cursor all() {
            final List<Object[]> rows = new ArrayList<>();
            for (final Object[][] frame : frames) {
                rows.addAll(List.of(frame));
            }
            return RowsFakes.cursor(ReferenceCursors.FIELDS, rows.size(), pageSize, rows::get);
        }

        /// The frames opened lazily in order, as the exchange reads them.
        FrameChain chain() {
            return new FrameChain(this);
        }

        void assertOpenedClosed() {
            for (int f = 0; f < opened.length(); f++) {
                final RowsFakes.ListCursor c = opened.get(f);
                if (c != null) {
                    assertThat(c.closed()).as("frame %d closed", f).isTrue();
                }
            }
        }
    }

    /// A frame whose first page fails.
    private record Failing(RowsFakes.ListCursor list) implements Cursor {

        @Override
        public List<Field> fields() {
            return list.fields();
        }

        @Override
        public Page next() {
            throw new CdbException("cannot read the frame [frame=failing]", -1);
        }

        @Override
        public void close() {
            list.close();
        }
    }

    /// The frames of a [FrameRows] as one cursor, each opened when the previous ends, as the exchange reads them.
    private static final class FrameChain implements Cursor {

        private final FrameRows rows;
        private int frame;
        private Cursor current;
        private boolean closed;

        FrameChain(final FrameRows rows) {
            this.rows = rows;
        }

        @Override
        public List<Field> fields() {
            return ReferenceCursors.FIELDS;
        }

        @Override
        public Page next() {
            assertThat(closed).as("no page is pulled from a closed input").isFalse();
            while (true) {
                if (current == null) {
                    if (frame == rows.frames.length) {
                        return null;
                    }
                    current = rows.open(frame++);
                }
                final Page page = current.next();
                if (page != null) {
                    return page;
                }
                current.close();
                current = null;
            }
        }

        @Override
        public void close() {
            closed = true;
            if (current != null) {
                current.close();
                current = null;
            }
        }
    }
}
