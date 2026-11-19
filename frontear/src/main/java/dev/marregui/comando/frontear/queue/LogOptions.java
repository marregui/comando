package dev.marregui.comando.frontear.queue;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link LinkLog} keeps its records.
 *
 * @param segmentBytes  the size at which the log starts a new segment file; a record larger than a segment gets one of
 *                      its own
 * @param maxBytes      the most bytes the log retains on disk, segment headers included: an append that would pass it
 *                      fails at once with {@link QuotaExceededException}
 * @param forceEvery    the longest a record waits after its append for the force that makes it durable, the group
 *                      commit's window; zero forces as soon as the forcer is free, grouping what arrived meanwhile
 * @param maxRoundBytes the most frame bytes the log holds in memory waiting for the forcer to write and force them
 *                      ({@link LinkLog#roundBytes()} is the usage): an append that would pass it while others wait
 *                      fails at once with {@link QuotaExceededException}; a frame larger than it is taken alone
 */
public record LogOptions(long segmentBytes, long maxBytes, Duration forceEvery, long maxRoundBytes) {

    /**
     * The round's bound when none is given. Node a alone took 97,577 publishes of 4 KiB a second in
     * {@code FrontearBench} (Apple M2 Max, APFS, 2026-11-19), about 1.6 MB during one 4 ms force of the link log;
     * 8 MiB is five times that, so a full round means the disk does not keep up, not that the round is too small.
     */
    public static final long MAX_ROUND_BYTES = 8L << 20;

    /** Refuses a segment too small for its header and an empty record, a quota below a segment, a negative wait. */
    public LogOptions {
        Objects.requireNonNull(forceEvery, "forceEvery");
        if (segmentBytes < LinkLog.HEADER + LinkLog.FRAME) {
            throw new IllegalArgumentException("refuse segment too small for its header and a record [segmentBytes="
                    + segmentBytes + ", minimum=" + (LinkLog.HEADER + LinkLog.FRAME) + ']');
        }
        if (maxBytes < segmentBytes) {
            throw new IllegalArgumentException("refuse quota below one segment [maxBytes=" + maxBytes
                    + ", segmentBytes=" + segmentBytes + ']');
        }
        if (forceEvery.isNegative()) {
            throw new IllegalArgumentException("refuse negative force interval [forceEvery=" + forceEvery + ']');
        }
        if (maxRoundBytes < LinkLog.FRAME) {
            throw new IllegalArgumentException("refuse round bound below one frame [maxRoundBytes=" + maxRoundBytes
                    + ", minimum=" + LinkLog.FRAME + ']');
        }
    }

    /** As the canonical constructor, the round bounded by {@link #MAX_ROUND_BYTES}. */
    public LogOptions(final long segmentBytes, final long maxBytes, final Duration forceEvery) {
        this(segmentBytes, maxBytes, forceEvery, MAX_ROUND_BYTES);
    }
}
