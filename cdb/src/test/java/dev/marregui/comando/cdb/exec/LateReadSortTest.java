package dev.marregui.comando.cdb.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.plan.ColumnUse;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.Compare;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;
import dev.marregui.comando.cdb.store.TableReader;
import dev.marregui.comando.cdb.store.TableWriter;

import static org.assertj.core.api.Assertions.assertThat;

/// ORDER BY with a small LIMIT over a scan, read late: the scan reads the filter's and the keys' columns and each row's
/// position, and the other columns are fetched for the kept rows alone. The answer is the eager serial plan's, the
/// same bits and the same order of ties, at pools of 1, 2 and 11 threads. Seeds in the output; replay with
/// `-Dcdb.late.seed0=<seed0> -Dcdb.late.seed1=<seed1>`.
class LateReadSortTest {

    private static final int[] POOL_SIZES = {1, 2, 11};
    private static final long DAY_MICROS = 86_400_000_000L;
    private static final long START_MICROS = 1_793_491_200_000_000L;
    private static final long NOW_MICROS = START_MICROS + 10 * DAY_MICROS;
    private static final long WAIT_MILLIS = 1_000L;
    private static final String[] KEYS = {"ts", "sym", "n", "d", "flag", "s", "at"};
    private static final long[] LIMITS = {0, 1, 7, 500, 1_500, 10_000};
    private static final String[] FILTERS = {"", " WHERE n > 0", " WHERE d > 0 AND s != 'v1'", " WHERE sym = 's2'",
        " WHERE n < 50 AND n > -50"};

    @TempDir
    static Path root;

    private static Store store;
    private static List<QueryPool> pools;
    private static TwoSeedRandom random;
    private static int bigRows;

    /// Writes `t`, over more than one frame across three days, and `small`, fewer rows than most limits.
    @BeforeAll
    static void writeTables() {
        random = TwoSeedRandom.of("cdb.late", "LateReadSortTest");
        pools = new ArrayList<>();
        for (final int size : POOL_SIZES) {
            pools.add(QueryPool.of(size));
        }
        store = Store.open(root);
        bigRows = random.between(Frames.FRAME_ROWS + 1, 2 * Frames.FRAME_ROWS);
        write("t", bigRows, 3);
        write("small", random.between(1, 300), 2);
    }

    /// Closes the pools and the store.
    @AfterAll
    static void close() {
        for (final QueryPool pool : pools) {
            pool.close();
        }
        store.close();
    }

    /// The bench's query shape, a filter over the key, is read late and answers what the eager plan answers.
    @Test
    void benchShapeMatchesTheEagerPlan() {
        check("SELECT * FROM t WHERE n > 0 ORDER BY n LIMIT 500", true);
        check("SELECT * FROM t ORDER BY n DESC LIMIT 500", true);
        check("SELECT * FROM t WHERE flag ORDER BY ts DESC, s LIMIT 100", true);
    }

    /// Random keys of every type, either direction, with ties and NULLs, a filter or none, limits from 0 to the bound,
    /// over and under the row count.
    @Test
    void randomSortsMatchTheEagerPlan() {
        for (int i = 0; i < 40; i++) {
            final StringBuilder order = new StringBuilder();
            final int keys = random.between(1, 2);
            for (int k = 0; k < keys; k++) {
                order.append(k == 0 ? "" : ", ").append(KEYS[random.nextInt(KEYS.length)])
                        .append(random.nextBoolean() ? " DESC" : "");
            }
            final String table = random.percent(25) ? "small" : "t";
            final String sql = "SELECT * FROM " + table + FILTERS[random.nextInt(FILTERS.length)] + " ORDER BY "
                    + order + " LIMIT " + LIMITS[random.nextInt(LIMITS.length)];
            check(sql, false);
        }
    }

    /// A projection of some columns: the columns nothing reads stay unread, the others are fetched late.
    @Test
    void projectionMatchesTheEagerPlan() {
        check("SELECT s, n, d FROM t ORDER BY flag, d LIMIT 2000", false);
        check("SELECT sym, at FROM t WHERE flag ORDER BY at DESC LIMIT 10000", false);
    }

    /// A limit past the bound stays eager.
    @Test
    void aLimitPastTheBoundIsNotReadLate() {
        final Plan.Sort sort = sortOf(ParallelRows.plan(store, "SELECT * FROM t ORDER BY n LIMIT 10001", NOW_MICROS));
        assertThat(sort).isNotNull();
        assertThat(LateReadCursor.applies(sort, ColumnUse.of(sort))).isFalse();
        check("SELECT * FROM t ORDER BY n LIMIT 10001", false);
    }

    /// The columns outside the keys and the filter are read only for the kept rows, through fetch, and the answer is
    /// the eager plan's.
    @Test
    void otherColumnsAreReadOnlyForTheWinners() {
        final String sql = "SELECT * FROM t WHERE n > 0 ORDER BY n, ts DESC LIMIT 500";
        final Plan plan = ParallelRows.plan(store, sql, NOW_MICROS);
        final Plan.Sort sort = sortOf(plan);
        assertThat(sort).isNotNull();
        final ParallelRows.Answer eager = ParallelRows.drain(SerialPlans.open(plan, store, NOW_MICROS));
        for (final QueryPool pool : pools) {
            final CountingReader reader = new CountingReader(store.reader("t"));
            final ParallelRows.Answer late = ParallelRows.drain(LateReadCursor.open(sort, reader, NOW_MICROS,
                    ColumnUse.of(plan), new QueryContext(null, pool)));
            assertThat(late.differenceFrom(eager, false)).as("pool of %d", pool.threads()).isNull();
            assertThat(late.rows()).isEqualTo(500);
            assertThat(reader.closed).as("the cursor closes its reader").isTrue();
            // n (2) is the key and the filter's, ts (0) a key; the others are fetched for the 500 kept rows alone
            assertThat(reader.values.get(2)).as("n is read for every row").isGreaterThanOrEqualTo(bigRows);
            for (final int c : new int[] {1, 3, 4, 5, 6, 7}) {
                assertThat(reader.values.get(c)).as("pool of %d, column %d", pool.threads(), c).isEqualTo(500);
                assertThat(reader.fetched.get(c)).as("pool of %d, column %d", pool.threads(), c).isEqualTo(500);
            }
        }
    }

    private static void check(final String sql, final boolean mustBeLate) {
        final Plan plan = ParallelRows.plan(store, sql, NOW_MICROS);
        final Plan.Sort sort = sortOf(plan);
        final boolean late = sort != null && LateReadCursor.applies(sort, ColumnUse.of(plan));
        if (mustBeLate) {
            assertThat(late).as("read late: %s, plan %s", sql, plan).isTrue();
        }
        final ParallelRows.Answer eager = ParallelRows.drain(SerialPlans.open(plan, store, NOW_MICROS));
        for (final QueryPool pool : pools) {
            final ParallelRows.Answer answer = ParallelRows.drain(
                    Executor.open(plan, store, NOW_MICROS, new QueryContext(null, pool)));
            assertThat(answer.differenceFrom(eager, false))
                    .as("pool of %d, late=%s: %s", pool.threads(), late, sql)
                    .isNull();
        }
    }

    private static Plan.Sort sortOf(final Plan plan) {
        if (plan instanceof Plan.Sort s) {
            return s;
        }
        if (plan instanceof Plan.Limit l) {
            return sortOf(l.child());
        }
        return plan instanceof Plan.Project p ? sortOf(p.child()) : null;
    }

    // ts, sym, n, d, p, flag, s, at: rows spread over the days, one in eight on the previous row's timestamp, small
    // value ranges so keys tie
    private static void write(final String table, final int rows, final int days) {
        store.create(new TableDef(table, List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("sym", CdbType.SYMBOL),
                new ColumnDef("n", CdbType.LONG),
                new ColumnDef("d", CdbType.DOUBLE),
                new ColumnDef("p", CdbType.DOUBLE),
                new ColumnDef("flag", CdbType.BOOLEAN),
                new ColumnDef("s", CdbType.VARCHAR),
                new ColumnDef("at", CdbType.TIMESTAMP)), 0, PartitionBy.DAY, List.of(), 0L), false);
        final long spanMicros = days * DAY_MICROS - 1;
        try (final TableWriter writer = store.writer(table, WAIT_MILLIS)) {
            long tsMicros = START_MICROS;
            for (int r = 0; r < rows; r++) {
                if (r == 0 || !random.percent(12)) {
                    tsMicros = START_MICROS + (long) ((double) r / rows * spanMicros);
                }
                writer.newRow(tsMicros);
                if (!random.percent(10)) {
                    writer.putText(1, "s" + random.nextInt(5));
                }
                if (!random.percent(10)) {
                    writer.putLong(2, random.between(-200, 200));
                }
                writer.putDouble(3, random.percent(5) ? Double.NaN : random.between(-400, 400) / 8.0);
                writer.putDouble(4, random.nextDouble());
                writer.putBoolean(5, random.nextBoolean());
                if (!random.percent(10)) {
                    writer.putText(6, "v" + random.nextInt(50));
                }
                if (!random.percent(10)) {
                    writer.putLong(7, START_MICROS + random.nextInt(1_000));
                }
                writer.endRow();
            }
            writer.commit();
        }
        System.out.println("table " + table + " [rows=" + rows + ", days=" + days + ']');
    }

    /// A reader whose partitions count the values each column hands out, through any read, and through fetch alone.
    private static final class CountingReader implements TableReader {

        final AtomicLongArray values = new AtomicLongArray(8);
        final AtomicLongArray fetched = new AtomicLongArray(8);
        private final TableReader delegate;
        private final List<PartitionReader> partitions;
        volatile boolean closed;

        CountingReader(final TableReader delegate) {
            this.delegate = delegate;
            final List<PartitionReader> wrapped = new ArrayList<>();
            for (final PartitionReader p : delegate.partitions()) {
                wrapped.add(new CountingPartition(p));
            }
            partitions = List.copyOf(wrapped);
        }

        @Override
        public TableDef def() {
            return delegate.def();
        }

        @Override
        public List<PartitionReader> partitions() {
            return partitions;
        }

        @Override
        public long rowCount() {
            return delegate.rowCount();
        }

        @Override
        public void close() {
            closed = true;
            delegate.close();
        }

        private final class CountingPartition implements PartitionReader {

            private final PartitionReader p;

            CountingPartition(final PartitionReader p) {
                this.p = p;
            }

            @Override
            public long lowerBound() {
                return p.lowerBound();
            }

            @Override
            public long minTimestamp() {
                return p.minTimestamp();
            }

            @Override
            public long maxTimestamp() {
                return p.maxTimestamp();
            }

            @Override
            public long rowCount() {
                return p.rowCount();
            }

            @Override
            public void longs(final int column, final long from, final int n, final long[] dst) {
                values.addAndGet(column, n);
                p.longs(column, from, n, dst);
            }

            @Override
            public void doubles(final int column, final long from, final int n, final double[] dst) {
                values.addAndGet(column, n);
                p.doubles(column, from, n, dst);
            }

            @Override
            public void booleans(final int column, final long from, final int n, final boolean[] dst) {
                values.addAndGet(column, n);
                p.booleans(column, from, n, dst);
            }

            @Override
            public void texts(final int column, final long from, final int n, final String[] dst) {
                values.addAndGet(column, n);
                p.texts(column, from, n, dst);
            }

            @Override
            public void read(final int column, final long from, final int n, final Vector dst, final int at) {
                values.addAndGet(column, n);
                p.read(column, from, n, dst, at);
            }

            @Override
            public void fetch(final int column, final long[] rows, final int from, final int n, final Vector dst,
                    final int at) {
                values.addAndGet(column, n);
                fetched.addAndGet(column, n);
                p.fetch(column, rows, from, n, dst, at);
            }

            @Override
            public long rowAtOrAfter(final int timestampColumn, final long micros) {
                return p.rowAtOrAfter(timestampColumn, micros);
            }

            @Override
            public int selectLong(final int column, final long from, final int n, final Compare op,
                    final long constant, final int[] dst) {
                values.addAndGet(column, n);
                return p.selectLong(column, from, n, op, constant, dst);
            }

            @Override
            public int selectDouble(final int column, final long from, final int n, final Compare op,
                    final double constant, final int[] dst) {
                values.addAndGet(column, n);
                return p.selectDouble(column, from, n, op, constant, dst);
            }

            @Override
            public int selectText(final int column, final long from, final int n, final Compare op,
                    final String constant, final int[] dst) {
                values.addAndGet(column, n);
                return p.selectText(column, from, n, op, constant, dst);
            }

            @Override
            public void longsAt(final int column, final long from, final int[] sel, final int count,
                    final long[] dst) {
                values.addAndGet(column, count);
                p.longsAt(column, from, sel, count, dst);
            }

            @Override
            public void doublesAt(final int column, final long from, final int[] sel, final int count,
                    final double[] dst) {
                values.addAndGet(column, count);
                p.doublesAt(column, from, sel, count, dst);
            }

            @Override
            public void booleansAt(final int column, final long from, final int[] sel, final int count,
                    final boolean[] dst) {
                values.addAndGet(column, count);
                p.booleansAt(column, from, sel, count, dst);
            }

            @Override
            public void textsAt(final int column, final long from, final int[] sel, final int count,
                    final String[] dst) {
                values.addAndGet(column, count);
                p.textsAt(column, from, sel, count, dst);
            }
        }
    }
}
