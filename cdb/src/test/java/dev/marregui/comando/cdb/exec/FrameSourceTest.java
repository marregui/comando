package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.junit.jupiter.api.Test;

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

/// [FrameSource] cuts a segment's scan in frames of a fixed number of rows counted from each partition's first row,
/// opens each frame as its own cursor through the segment's filters and projections, and releases its reader once.
/// Frames of 3 rows keep every edge in sight.
class FrameSourceTest {

    private static final int FRAME_ROWS = 3;
    private static final List<Field> X = List.of(field("x", CdbType.LONG));
    private static final Bound COLUMN_X = new Bound.Column(0, CdbType.LONG);

    private static Plan.Scan scan(
            final long from,
            final long to,
            final List<Plan.ScanTerm> terms,
            final boolean newest
    ) {
        return new Plan.Scan("t", new int[] {1}, from, to, terms, newest, X);
    }

    private static Plan.Scan all(final boolean newest) {
        return scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(), newest);
    }

    /// Two partitions: x 0..6 with timestamps 0..6, and x 7..8 with timestamps 7..8.
    private static List<PartitionReader> twoPartitions() {
        return List.of(partition(0, 7), partition(7, 2));
    }

    private static FrameSource source(final Plan plan, final RowsFakes.FakeReader reader) {
        return FrameSource.of(plan, reader, FRAME_ROWS, QueryContext.uncancellable());
    }

    /// Each frame's rows of x, frame after frame.
    private static List<List<Long>> frames(final Plan plan, final List<PartitionReader> parts) {
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), parts);
        final List<List<Long>> out = new ArrayList<>();
        try (FrameSource source = source(plan, reader)) {
            for (int f = 0; f < source.frames(); f++) {
                out.add(drain(source.open(f)).longs());
            }
            assertThat(reader.closes()).as("a frame cursor leaves the reader to the source").isZero();
        }
        assertThat(reader.closes()).as("the source releases its reader once").isEqualTo(1);
        return out;
    }

    private static Bound call(final String function, final CdbType type, final Bound... args) {
        return new Bound.Call(function, List.of(args), type);
    }

    private static Bound constant(final long value) {
        return new Bound.Constant(value, CdbType.LONG);
    }

    /// A frame ends at every multiple of the frame size counted from its partition's first row, and at the
    /// partition's end: a partition's last frame may be short, and no frame spans two partitions.
    @Test
    void framesEndAtFrameMultiplesAndPartitionEnds() {
        assertThat(frames(all(false), twoPartitions()))
                .containsExactly(List.of(0L, 1L, 2L), List.of(3L, 4L, 5L), List.of(6L), List.of(7L, 8L));
    }

    /// A time range trims the first and last frames of each partition it cuts, without moving the frame edges, and
    /// skips the partitions outside it.
    @Test
    void aTimeRangeTrimsFramesWithoutMovingTheirEdges() {
        assertThat(frames(scan(2, 8, List.of(), false), twoPartitions()))
                .containsExactly(List.of(2L), List.of(3L, 4L, 5L), List.of(6L), List.of(7L));
        assertThat(frames(scan(7, Long.MAX_VALUE, List.of(), false), twoPartitions()))
                .containsExactly(List.of(7L, 8L));
        assertThat(frames(scan(4, 5, List.of(), false), twoPartitions())).containsExactly(List.of(4L));
    }

    /// Newest first runs the frames from the last and each frame's rows in reverse: together the time-order rows in
    /// reverse.
    @Test
    void newestFirstReversesTheFramesAndTheirRows() {
        assertThat(frames(all(true), twoPartitions()))
                .containsExactly(List.of(8L, 7L), List.of(6L), List.of(5L, 4L, 3L), List.of(2L, 1L, 0L));
        assertThat(frames(scan(2, 8, List.of(), true), twoPartitions()))
                .containsExactly(List.of(7L), List.of(6L), List.of(5L, 4L, 3L), List.of(2L));
    }

    /// The scan's terms apply inside each frame and do not move its edges: a frame they empty is still a frame, with
    /// no row.
    @Test
    void preFilterTermsApplyInsideEachFrame() {
        final Plan.ScanTerm notFour = new Plan.ScanTerm(0, Compare.NE, 4, Double.NaN, null);
        final Plan.ScanTerm belowSix = new Plan.ScanTerm(0, Compare.LT, 6, Double.NaN, null);
        assertThat(frames(scan(Long.MIN_VALUE, Long.MAX_VALUE, List.of(notFour, belowSix), false), twoPartitions()))
                .containsExactly(List.of(0L, 1L, 2L), List.of(3L, 5L), List.of(), List.of());
    }

    /// A table with no row in range is one empty frame; a frame outside the source is refused.
    @Test
    void noRowIsOneEmptyFrame() {
        assertThat(frames(all(false), List.of())).containsExactly(List.of());
        assertThat(frames(scan(100, 200, List.of(), false), twoPartitions())).containsExactly(List.of());
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), twoPartitions());
        try (FrameSource source = source(all(false), reader)) {
            assertThatThrownBy(() -> source.open(4)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> source.open(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    /// A frame runs the segment's filters and projections over its own rows.
    @Test
    void aFrameRunsTheSegmentsFilterAndProjection() {
        final Plan filter = new Plan.Filter(all(false), call(">", CdbType.BOOLEAN, COLUMN_X, constant(1)));
        final Plan doubled = new Plan.Project(filter, List.of(call("*", CdbType.LONG, COLUMN_X, constant(2))), X);
        assertThat(frames(doubled, twoPartitions()))
                .containsExactly(List.of(4L), List.of(6L, 8L, 10L), List.of(12L), List.of(14L, 16L));
    }

    /// Two frames' cursors read on two threads at once give each its own rows: each cursor compiles its own
    /// expressions, whose reused vectors one shared compilation would let the other thread overwrite mid-page.
    @Test
    void framesReadAtOnceDoNotShareExpressions() throws InterruptedException {
        final int rows = 64 * Page.CAPACITY;
        final Plan doubled = new Plan.Project(all(false), List.of(call("*", CdbType.LONG, COLUMN_X, constant(2))), X);
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), List.of(partition(0, 2 * rows)));
        try (FrameSource source = FrameSource.of(doubled, reader, rows, QueryContext.uncancellable())) {
            assertThat(source.frames()).isEqualTo(2);
            final CyclicBarrier start = new CyclicBarrier(2);
            final CountDownLatch done = new CountDownLatch(2);
            final AtomicReferenceArray<List<Long>> read = new AtomicReferenceArray<>(2);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            for (int f = 0; f < 2; f++) {
                final int frame = f;
                Thread.ofPlatform().name("frame-reader-" + f).start(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        read.set(frame, drain(source.open(frame)).longs());
                    } catch (final Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).as("both readers end").isTrue();
            assertThat(failure.get()).isNull();
            for (int f = 0; f < 2; f++) {
                final List<Long> expected = new ArrayList<>();
                for (final long x : range((long) f * rows, (long) (f + 1) * rows)) {
                    expected.add(2 * x);
                }
                assertThat(read.get(f)).as("frame %d", f).isEqualTo(expected);
            }
        }
    }

    /// Closing the source twice releases the reader once.
    @Test
    void closeReleasesTheReaderOnce() {
        final RowsFakes.FakeReader reader = new RowsFakes.FakeReader(table(), twoPartitions());
        final FrameSource source = source(all(false), reader);
        source.close();
        source.close();
        assertThat(reader.closes()).isEqualTo(1);
    }

    /// A scan under filters and projections of row-local expressions is a segment; an expression that reads other
    /// rows, or any other node in the chain, is not.
    @Test
    void onlyRowLocalChainsOverAScanAreSegments() {
        final Plan.Scan scan = all(false);
        final Bound windowCall = new Bound.WindowCall("row_number", List.of(), List.of(), List.of(), CdbType.LONG);
        final Plan filter = new Plan.Filter(scan, call(">", CdbType.BOOLEAN, COLUMN_X, constant(1)));
        assertThat(FrameSource.isSegment(scan)).isTrue();
        assertThat(FrameSource.isSegment(filter)).isTrue();
        assertThat(FrameSource.isSegment(new Plan.Project(filter, List.of(COLUMN_X), X))).isTrue();
        assertThat(FrameSource.isSegment(new Plan.Project(scan, List.of(windowCall), X))).isFalse();
        assertThat(FrameSource.isSegment(new Plan.Project(scan, List.of(call("+", CdbType.LONG, COLUMN_X,
                windowCall)), X))).isFalse();
        assertThat(FrameSource.isSegment(new Plan.Filter(new Plan.Limit(scan, 1, 0), call(">", CdbType.BOOLEAN,
                COLUMN_X, constant(1))))).isFalse();
    }
}
