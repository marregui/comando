package dev.marregui.comando.cdb;

/**
 * Asks a running query to stop: {@link #cancel()} from any thread, and the query's drain gives up with a
 * {@link CdbException} at its next page. One per query; not reusable.
 */
public final class CdbCancel {

    // written by the cancelling thread, read by the draining one
    private volatile boolean cancelled;

    /** Asks the query to stop; idempotent. */
    public void cancel() {
        cancelled = true;
    }

    /** Whether {@link #cancel()} was called. */
    public boolean cancelled() {
        return cancelled;
    }
}
