package dev.marregui.comando.cdb.exec;

import java.util.List;

/**
 * The rows an operator produces, a page at a time; used by one thread. Closing a cursor closes its children and the
 * table readers it holds.
 */
public interface Cursor extends AutoCloseable {

    /** The output columns; fixed for the life of the cursor. */
    List<Field> fields();

    /** The next page, never empty; null after the last. */
    Page next();

    /** Idempotent. */
    @Override
    void close();
}
