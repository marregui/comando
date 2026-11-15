package dev.marregui.comando.cdb.store;

import java.util.concurrent.TimeUnit;

/**
 * When the applier applies one table: once its oldest queued commit is older than the interval, at once when a reader
 * waits or the store's queued bytes press. The interval adapts after each batch: halved when a reader waited (it reads
 * often, so fresher batches save its waits), doubled when the batch took more than half the interval (batches are
 * cheaper per commit when larger), else drawn a quarter of the way back to the base. Owned by the applier thread.
 */
final class ApplySchedule {

    // bounds and base: not measured, set for the plan of the applier; a read that cannot wait binds
    // its own lag, so the base only sets how fresh an unread table is and how many commits a batch takes
    static final long MIN_NANOS = TimeUnit.MILLISECONDS.toNanos(100L);
    static final long BASE_NANOS = TimeUnit.SECONDS.toNanos(1L);
    static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private long intervalNanos = BASE_NANOS;

    /** The current interval. */
    long intervalNanos() {
        return intervalNanos;
    }

    /**
     * True when a table whose oldest commit was queued at {@code oldestNanos} is due at {@code nowNanos}; {@code
     * demanded} when a reader waits, {@code pressed} when the store's queued bytes are over half its budget.
     */
    boolean due(final long nowNanos, final long oldestNanos, final boolean demanded, final boolean pressed) {
        return demanded || pressed || nowNanos - oldestNanos >= intervalNanos;
    }

    /**
     * The nanoseconds from {@code nowNanos} until a table whose oldest commit was queued at {@code oldestNanos} is due.
     */
    long untilDueNanos(final long nowNanos, final long oldestNanos) {
        return Math.max(0L, intervalNanos - (nowNanos - oldestNanos));
    }

    /** Adapts the interval after a batch that took {@code tookNanos}; {@code demanded} when a reader waited for it. */
    void applied(final long tookNanos, final boolean demanded) {
        if (demanded) {
            intervalNanos = Math.max(MIN_NANOS, intervalNanos / 2L);
        } else if (tookNanos > intervalNanos / 2L) {
            intervalNanos = Math.min(MAX_NANOS, intervalNanos * 2L);
        } else {
            intervalNanos += (BASE_NANOS - intervalNanos) / 4L;
        }
    }

    /** Backs off after a failed batch: the interval doubles, up to its bound. */
    void failed() {
        intervalNanos = Math.min(MAX_NANOS, intervalNanos * 2L);
    }
}
