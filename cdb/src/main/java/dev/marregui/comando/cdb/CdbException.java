package dev.marregui.comando.cdb;

import java.io.Serial;

/**
 * A statement cdb refuses or fails: a syntax error, an unknown table or column, a type mismatch, an unsupported
 * construct, a storage failure. The message follows {@code verb phrase [key=value]}; {@link #position()} is the
 * offset in the SQL text, or -1.
 */
public final class CdbException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int position;

    /** A failure at {@code position} in the SQL text, -1 when it has none. */
    public CdbException(final String message, final int position) {
        super(message);
        this.position = position;
    }

    /** A failure with no position, caused by {@code cause} (an I/O failure of the store). */
    public CdbException(final String message, final Throwable cause) {
        super(message, cause);
        this.position = -1;
    }

    /** The offset in the SQL text the failure is at, -1 when it has none. */
    public int position() {
        return position;
    }
}
