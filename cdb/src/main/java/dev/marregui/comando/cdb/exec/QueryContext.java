package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.CdbCancel;
import dev.marregui.comando.cdb.CdbException;

/**
 * What one query's work carries to every frame it runs: the cancel its caller holds and the pool its frames run on.
 * Frames check {@link #check()} before they start and at each page, so a cancel reaches work already on the pool.
 *
 * @param cancel the caller's cancel, null when the query cannot be cancelled
 * @param pool the pool the query's frames run on
 */
public record QueryContext(CdbCancel cancel, QueryPool pool) {

    /** A context that cannot be cancelled, on the process's shared pool. */
    public static QueryContext uncancellable() {
        return new QueryContext(null, QueryPool.shared());
    }

    /** Throws the drain's own "query cancelled" failure once the cancel is cancelled. */
    public void check() {
        if (cancel != null && cancel.cancelled()) {
            throw new CdbException("query cancelled", -1);
        }
    }
}
