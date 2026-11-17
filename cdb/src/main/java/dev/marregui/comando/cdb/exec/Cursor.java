package dev.marregui.comando.cdb.exec;

import java.util.List;

/**
 * The rows an operator produces, a page at a time; used by one thread. Closing a cursor closes its children and the
 * table readers it holds.
 */
public interface Cursor extends AutoCloseable {

    /** The output columns; fixed for the life of the cursor. */
    List<Field> fields();

    /** The next page, never empty; null after the last. The page belongs to the caller. */
    Page next();

    /**
     * The next page, as {@link #next}, lent rather than given: it stays valid only until the next {@code borrow},
     * {@code next} or {@code close} on this cursor, which may rewrite it, and the caller keeps neither the page nor its
     * vectors. A cursor that owns a reusable page overrides it; the default lends what {@link #next} gives. Only the
     * operators {@code CdbArchitectureTest} lists call it.
     */
    default Page borrow() {
        return next();
    }

    /** Idempotent. */
    @Override
    void close();
}
