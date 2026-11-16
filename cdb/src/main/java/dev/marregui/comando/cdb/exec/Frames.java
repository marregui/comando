package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.PartitionReader;
import dev.marregui.comando.cdb.store.TableReader;

/**
 * The frames a scan's rows split into for parallel work: per partition in the scan's time range, its rows in range cut
 * at the multiples of {@link #FRAME_ROWS} counted from the partition's first row. The split depends only on the
 * snapshot and the scan, never on the pool's size, so the merged answer is the same bits on every run. In time order,
 * or newest first for a {@code newestFirst} scan (the newest partition first, each partition's last frame first).
 */
public final class Frames {

    /**
     * The rows of one frame: 64 Ki, 64 pages of {@link Page#CAPACITY}, enough work to repay a hand-off to a worker and
     * small enough that a partition of a day at the soak's rate (about 4 M rows) gives dozens of frames.
     */
    public static final int FRAME_ROWS = 65_536;

    private Frames() {
    }

    /**
     * The rows {@code [fromRow, toRow)} of partition {@code partition}, an index into the reader's
     * {@link TableReader#partitions()} (time order).
     */
    public record Frame(int partition, long fromRow, long toRow) {
    }

    /** The frames of {@code scan} over {@code reader}'s snapshot, in the scan's order. */
    public static List<Frame> of(final Plan.Scan scan, final TableReader reader) {
        return of(scan, reader, FRAME_ROWS);
    }

    /** As {@link #of(Plan.Scan, TableReader)} with frames of {@code frameRows} rows, for tests of frame edges. */
    static List<Frame> of(final Plan.Scan scan, final TableReader reader, final int frameRows) {
        final List<Frame> frames = new ArrayList<>();
        for (final Frame range : ranges(scan, reader)) {
            long lo = range.fromRow();
            while (lo < range.toRow()) {
                final long cut = (lo / frameRows + 1) * frameRows;
                final long hi = Math.min(range.toRow(), cut);
                frames.add(new Frame(range.partition(), lo, hi));
                lo = hi;
            }
        }
        return scan.newestFirst() ? frames.reversed() : frames;
    }

    /**
     * Per partition of {@code reader}'s snapshot, the rows inside {@code scan}'s time range, found by binary search on
     * the timestamp: one frame per partition holding any, in time order whatever the scan's order.
     */
    static List<Frame> ranges(final Plan.Scan scan, final TableReader reader) {
        final int ts = reader.def().timestampIndex();
        final long from = scan.fromMicros();
        final long to = scan.toMicros();
        // Long.MAX_VALUE is the plan's "no upper bound", so a row stamped with it is in range
        final boolean bounded = to != Long.MAX_VALUE;
        final List<PartitionReader> partitions = reader.partitions();
        final List<Frame> ranges = new ArrayList<>();
        for (int i = 0; i < partitions.size(); i++) {
            final PartitionReader p = partitions.get(i);
            if (p.rowCount() <= 0 || p.maxTimestamp() < from || bounded && p.minTimestamp() >= to) {
                continue;
            }
            final long start = ts < 0 || p.minTimestamp() >= from ? 0 : p.rowAtOrAfter(ts, from);
            final long end = ts < 0 || !bounded || p.maxTimestamp() < to ? p.rowCount() : p.rowAtOrAfter(ts, to);
            if (start < end) {
                ranges.add(new Frame(i, start, end));
            }
        }
        return ranges;
    }
}
