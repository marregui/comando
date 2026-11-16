package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/// [ExchangeCursor] gives a segment's rows frame after frame in frame order, the same rows in the same order as the
/// serial scan whatever the pool size, and releases the source's reader once, on its end, its close or a frame's
/// failure.
class ExchangeCursorTest {

    /// The pool sizes every exchange is checked at: no worker, one, a few, more than the frames of a small table.
    private static final int[] POOL_SIZES = {0, 1, 3, 11};
    private static final int[] ALL = {0, 1, 2, 3};
    private static final List<Field> FIELDS = List.of(field("ts", CdbType.TIMESTAMP), field("x", CdbType.LONG),
            field("d", CdbType.DOUBLE), field("s", CdbType.SYMBOL));
    private static final List<Field> X = List.of(field("x", CdbType.LONG));
    private static final Bound COLUMN_X = new Bound.Column(1, CdbType.LONG);

    private static Plan.Scan scan(
            final long from,
            final long to,
            final List<Plan.ScanTerm> terms,
            final boolean newest
    ) {
        return new Plan.Scan("t", ALL, from, to, terms, newest, FIELDS);
    }

    private static Bound constant(final long value) {
        return new Bound.Constant(value, CdbType.LONG);
    }

    /// The segment's rows read through the exchange, frames of `frameRows` rows, on `pool`.
    private static RowsFakes.Drained exchange(
            final Plan plan,
            final List<PartitionReader> parts,
            final int frameRows,
            final QueryPool pool
    ) {
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), parts);
        final RowsFakes.Drained drained = drain(new ExchangeCursor(FrameSource.of(plan, reader, frameRows,
                new QueryContext(null, pool))));
        assertThat(reader.closes()).as("the exchange releases the reader once").isEqualTo(1);
        return drained;
    }

    /// The same segment read serially: one whole-scan cursor over `reader` under the same filters and projections.
    private static Cursor serial(final Plan plan, final RowsFakes.FakeReader reader) {
        return switch (plan) {
            case Plan.Scan s -> new ScanCursor(s, reader);
            case Plan.Filter f -> Executor.filter(f, serial(f.child(), reader), 0);
            case Plan.Project p -> Executor.project(p, serial(p.child(), reader), 0);
            case Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.LatestOn _, Plan.Distinct _, Plan.Sort _,
                 Plan.Limit _, Plan.SetOp _, Plan.Join _, Plan.Window _, Plan.Change _ ->
                    throw new IllegalArgumentException("not a segment [plan=" + plan + ']');
        };
    }

    /// x 0..8 in one partition, failing when column x is read from row `failFrom` on.
    private static PartitionReader failingFrom(final long failFrom) {
        final RowsFakes.FakePartition rows = partition(0, 9);
        return new PartitionReader() {
            @Override
            public long lowerBound() {
                return rows.lowerBound();
            }

            @Override
            public long minTimestamp() {
                return rows.minTimestamp();
            }

            @Override
            public long maxTimestamp() {
                return rows.maxTimestamp();
            }

            @Override
            public long rowCount() {
                return rows.rowCount();
            }

            @Override
            public void longs(final int column, final long from, final int n, final long[] dst) {
                if (column == 1 && from + n > failFrom) {
                    throw new IllegalStateException("fail to read a column [column=" + column + ", from=" + from
                            + ']');
                }
                rows.longs(column, from, n, dst);
            }

            @Override
            public void doubles(final int column, final long from, final int n, final double[] dst) {
                rows.doubles(column, from, n, dst);
            }

            @Override
            public void booleans(final int column, final long from, final int n, final boolean[] dst) {
                rows.booleans(column, from, n, dst);
            }

            @Override
            public void texts(final int column, final long from, final int n, final String[] dst) {
                rows.texts(column, from, n, dst);
            }
        };
    }

    /// Frame order is scan order: a table cut in frames of 3 reads back whole and in order, oldest or newest first, at
    /// every pool size.
    @Test
    void framesComeBackInFrameOrder() {
        final List<PartitionReader> parts = List.of(partition(0, 7), partition(7, 2));
        final Plan x = new Plan.Project(scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(), false), List.of(COLUMN_X), X);
        final Plan newest = new Plan.Project(scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(), true), List.of(COLUMN_X),
                X);
        for (final int threads : POOL_SIZES) {
            try (QueryPool pool = QueryPool.of(threads)) {
                assertThat(exchange(x, parts, 3, pool).longs()).as("threads %d", threads).isEqualTo(range(0, 9));
                assertThat(exchange(newest, parts, 3, pool).longs()).as("threads %d", threads)
                        .isEqualTo(range(0, 9).reversed());
            }
        }
    }

    /// Random tables, time ranges, terms, filters, orders and frame sizes: the exchange gives exactly the serial
    /// scan's rows, in its order, at every pool size.
    @Test
    void randomSegmentsMatchTheSerialScan() {
        final long seed = Long.getLong("cdb.exchange.seed", System.nanoTime());
        System.out.println("randomSegmentsMatchTheSerialScan seed=" + seed);
        final Random random = new Random(seed);
        final int[] frameSizes = {1, 3, Page.CAPACITY - 1, Page.CAPACITY, 2500};
        final Compare[] ops = Compare.values();
        for (int round = 0; round < 60; round++) {
            final List<PartitionReader> parts = new ArrayList<>();
            long first = 0;
            for (int p = random.nextInt(4); p > 0; p--) {
                final int n = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(4000);
                parts.add(partition(first, n));
                first += n + random.nextInt(3);
            }
            final long from = random.nextBoolean() ? Long.MIN_VALUE : random.nextLong(Math.max(1, first));
            final long to = random.nextBoolean() ? Long.MAX_VALUE : random.nextLong(Math.max(1, first) + 1);
            final List<Plan.ScanTerm> terms = random.nextBoolean() ? List.of()
                    : List.of(new Plan.ScanTerm(1, ops[random.nextInt(ops.length)], random.nextLong(Math.max(1, first)),
                            Double.NaN, null));
            Plan plan = scan(from, to, terms, random.nextBoolean());
            if (random.nextBoolean()) {
                plan = new Plan.Filter(plan, new Bound.Call("!=", List.of(new Bound.Call("%", List.of(COLUMN_X,
                        constant(3)), CdbType.LONG), constant(0)), CdbType.BOOLEAN));
            }
            if (random.nextBoolean()) {
                plan = new Plan.Project(plan, List.of(new Bound.Call("*", List.of(COLUMN_X, constant(2)),
                        CdbType.LONG), new Bound.Column(3, CdbType.SYMBOL)), List.of(field("x2", CdbType.LONG),
                        field("s", CdbType.SYMBOL)));
            }
            final Plan segment = plan;
            final List<List<Object>> expected = drain(serial(segment, new RowsFakes.FakeReader(table(), parts)))
                    .rows();
            final int frameRows = frameSizes[random.nextInt(frameSizes.length)];
            for (final int threads : POOL_SIZES) {
                try (QueryPool pool = QueryPool.of(threads)) {
                    assertThat(exchange(segment, parts, frameRows, pool).rows())
                            .as("seed %d round %d frameRows %d threads %d", seed, round, frameRows, threads)
                            .isEqualTo(expected);
                }
            }
        }
    }

    /// Closing the exchange before its end releases the reader once, however often it is closed, and it reads no
    /// further.
    @Test
    void closeBeforeTheEndReleasesTheReaderOnce() {
        for (final int threads : POOL_SIZES) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), List.of(partition(0, 9)));
            try (QueryPool pool = QueryPool.of(threads)) {
                final ExchangeCursor exchange = new ExchangeCursor(FrameSource.of(scan(Long.MIN_VALUE,
                        Long.MAX_VALUE, List.of(), false), reader, 3, new QueryContext(null, pool)));
                assertThat(exchange.next()).isNotNull();
                exchange.close();
                exchange.close();
                assertThat(exchange.next()).isNull();
            }
            assertThat(reader.closes()).as("threads %d", threads).isEqualTo(1);
        }
    }

    /// A frame that fails ends the exchange: the frames before it are read, its failure is thrown by this call and
    /// every later one, and the reader is released once.
    @Test
    void aFailingFrameClosesEverything() {
        for (final int threads : POOL_SIZES) {
            final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), List.of(failingFrom(6)));
            final Plan x = new Plan.Project(scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(), false),
                    List.of(COLUMN_X), X);
            try (QueryPool pool = QueryPool.of(threads);
                 ExchangeCursor exchange = new ExchangeCursor(FrameSource.of(x, reader, 3,
                         new QueryContext(null, pool)))) {
                final List<Page> read = new ArrayList<>();
                read.add(exchange.next());
                read.add(exchange.next());
                assertThat(new RowsFakes.Drained(read).longs()).isEqualTo(range(0, 6));
                final Throwable failure = catchThrowable(exchange::next);
                assertThat(failure).isInstanceOf(IllegalStateException.class);
                assertThat(reader.closes()).as("threads %d", threads).isEqualTo(1);
                assertThatThrownBy(exchange::next).isSameAs(failure);
            }
            assertThat(reader.closes()).as("threads %d", threads).isEqualTo(1);
        }
    }

    /// A cancel between frames stops the exchange with the cancel's failure and releases the reader.
    @Test
    void aCancelStopsTheExchange() {
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), List.of(partition(0, 9)));
        final CdbCancel cancel = new CdbCancel();
        try (QueryPool pool = QueryPool.of(0);
             ExchangeCursor exchange = new ExchangeCursor(FrameSource.of(scan(Long.MIN_VALUE, Long.MAX_VALUE,
                     List.of(), false), reader, 3, new QueryContext(cancel, pool)))) {
            assertThat(exchange.next()).isNotNull();
            cancel.cancel();
            assertThatThrownBy(exchange::next).isInstanceOf(CdbException.class);
            assertThat(reader.closes()).as("the failure released the reader").isEqualTo(1);
        }
        assertThat(reader.closes()).isEqualTo(1);
    }
}
