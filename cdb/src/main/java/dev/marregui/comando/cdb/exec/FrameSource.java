package dev.marregui.comando.cdb.exec;

import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.exec.expr.Functions;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableReader;

/**
 * A segment of a plan that runs per frame: a {@link Plan.Scan} with only {@link Plan.Filter} and {@link Plan.Project}
 * nodes above it, none of them depending on rows outside the one being computed. An operator over a segment opens one
 * of these instead of a child cursor, and either asks for each frame's rows ({@link #open(int)}) or hands per-frame
 * work to the pool ({@link #submit(IntFunction)}) and merges the partials in frame order.
 *
 * <p>The source owns the table reader, one snapshot for every frame: it is closed by {@link #close()}, which the
 * operator calls after closing every {@link Job} and frame cursor it opened, so no partition is released under a
 * frame. Frame cursors do not close the reader. Frames of one source may be read on several threads at once: they
 * share the snapshot's partitions, whose reads are stateless, and nothing else.</p>
 */
public final class FrameSource implements AutoCloseable {

    private final Plan segment;
    private final Plan.Scan scan;
    private final long nowMicros;
    private final Map<Plan.Scan, boolean[]> reads;
    private final QueryContext ctx;
    private final TableReader reader;
    private final List<Frames.Frame> frames;
    // false when the reader is borrowed and its lender closes it
    private final boolean ownsReader;
    private boolean closed;

    private FrameSource(
            final Plan segment,
            final TableReader reader,
            final int frameRows,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx,
            final boolean ownsReader
    ) {
        this.segment = segment;
        this.ownsReader = ownsReader;
        this.nowMicros = nowMicros;
        this.reads = reads;
        this.ctx = ctx;
        this.reader = reader;
        try {
            scan = scan(segment);
            frames = List.copyOf(Frames.of(scan, reader, frameRows));
        } catch (final RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    /** The source of {@code plan} when it is a segment, else null; the caller closes it. */
    static FrameSource of(
            final Plan plan,
            final Store store,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx
    ) {
        if (!isSegment(plan)) {
            return null;
        }
        return new FrameSource(plan, store.reader(scan(plan).table()), Frames.FRAME_ROWS, nowMicros, reads, ctx,
                true);
    }

    /**
     * The source of segment {@code plan} over {@code reader}, which it owns from here, cut in frames of
     * {@code frameRows} rows.
     */
    @TestOnly
    static FrameSource of(final Plan plan, final TableReader reader, final int frameRows, final QueryContext ctx) {
        return new FrameSource(plan, reader, frameRows, 0L, Map.of(), ctx, true);
    }

    /**
     * The source of segment {@code plan} over {@code reader}, which stays its caller's: {@link #close()} leaves it
     * open, so the caller may read the snapshot after the frames are done.
     */
    static FrameSource borrowing(
            final Plan plan,
            final TableReader reader,
            final long nowMicros,
            final Map<Plan.Scan, boolean[]> reads,
            final QueryContext ctx
    ) {
        return new FrameSource(plan, reader, Frames.FRAME_ROWS, nowMicros, reads, ctx, false);
    }

    /**
     * True when {@code plan} is a scan under filters and projections only, each of whose expressions is
     * row-local ({@link Functions#rowLocal}).
     */
    static boolean isSegment(final Plan plan) {
        return switch (plan) {
            case Plan.Scan _ -> true;
            case Plan.Filter f -> Functions.rowLocal(f.predicate()) && isSegment(f.child());
            case Plan.Project p -> Functions.rowLocal(p.exprs()) && isSegment(p.child());
            case Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.LatestOn _, Plan.Distinct _, Plan.Sort _,
                 Plan.Limit _, Plan.SetOp _, Plan.Join _, Plan.Window _, Plan.Change _ -> false;
        };
    }

    /** The segment's output columns. */
    public List<Field> fields() {
        return segment.fields();
    }

    /** The context the source's frames run in. */
    public QueryContext context() {
        return ctx;
    }

    /** The number of frames, at least 1 (an empty frame when the scan has no row). */
    public int frames() {
        return Math.max(1, frames.size());
    }

    /** True when the frames run newest first ({@link Plan.Scan#newestFirst()}). */
    public boolean newestFirst() {
        return scan.newestFirst();
    }

    /**
     * False only when no row the frames read can be NULL in output column {@code field}: the segment has no projection,
     * and no partition a frame reads holds a NULL in the field's table column ({@link PartitionReader#mayHoldNull}).
     */
    public boolean mayHoldNull(final int field) {
        if (!(segment instanceof Plan.Scan) && !(segment instanceof Plan.Filter f && f.child() instanceof Plan.Scan)) {
            return true;
        }
        final int column = scan.columns()[field];
        final List<PartitionReader> partitions = reader.partitions();
        int last = -1;
        for (final Frames.Frame frame : frames) {
            if (frame.partition() != last) {
                ctx.check();
                last = frame.partition();
                if (partitions.get(last).mayHoldNull(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Frame {@code frame}'s rows through the segment, in scan order, a page at a time; on any thread, one thread per
     * cursor. The caller closes it; it does not close the reader.
     */
    public Cursor open(final int frame) {
        final int count = frames();
        if (frame < 0 || frame >= count) {
            throw new IndexOutOfBoundsException("frame out of range [frame=" + frame + ", frames=" + count + ']');
        }
        return chain(segment, frames.isEmpty() ? new Frames.Frame(0, 0, 0) : frames.get(frame));
    }

    /** Per-frame work on the context's pool: frame {@code i}'s partial is {@code work.apply(i)}. */
    public <P> Job<P> submit(final IntFunction<P> work) {
        return ctx.pool().submit(frames(), work, ctx);
    }

    /**
     * Releases the snapshot when the source owns it; idempotent. Called by the consumer once no frame of the source
     * runs.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (ownsReader) {
                reader.close();
            }
        }
    }

    /** The scan at the bottom of segment {@code plan}. */
    private static Plan.Scan scan(final Plan plan) {
        return switch (plan) {
            case Plan.Scan s -> s;
            case Plan.Filter f -> scan(f.child());
            case Plan.Project p -> scan(p.child());
            case Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.LatestOn _, Plan.Distinct _, Plan.Sort _,
                 Plan.Limit _, Plan.SetOp _, Plan.Join _, Plan.Window _, Plan.Change _ ->
                    throw new IllegalStateException("not a segment [plan=" + plan + ']');
        };
    }

    // each frame compiles its own expressions: a compiled function reuses its vectors, so two frames never share one
    private Cursor chain(final Plan plan, final Frames.Frame frame) {
        return switch (plan) {
            case Plan.Scan s -> new ScanCursor(s, reader, reads.get(s), frame);
            case Plan.Filter f -> Executor.over(chain(f.child(), frame), c -> Executor.filter(f, c, nowMicros));
            case Plan.Project p -> Executor.over(chain(p.child(), frame), c -> Executor.project(p, c, nowMicros));
            case Plan.Source _, Plan.Aggregate _, Plan.SampleBy _, Plan.LatestOn _, Plan.Distinct _, Plan.Sort _,
                 Plan.Limit _, Plan.SetOp _, Plan.Join _, Plan.Window _, Plan.Change _ ->
                    throw new IllegalStateException("not a segment [plan=" + plan + ']');
        };
    }
}
