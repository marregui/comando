package dev.marregui.comando.cdb.serve;

/**
 * A {@code /write} body refused: a malformed line, a value out of range, or a row the store did not take. Carries the
 * line, counted from 1, and the offset in it, counted from 0, of the offending character (-1 when it has none).
 */
final class IlpException extends RuntimeException {

    private final int line;
    private final int offset;

    /** A malformed line: {@code reason} with no values, the line and the offset are added to the message. */
    IlpException(final String reason, final int line, final int offset) {
        super(reason + " [line=" + line + ", offset=" + offset + ']');
        this.line = line;
        this.offset = offset;
    }

    /** A line the store refused, with its message. */
    IlpException(final int line, final RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.line = line;
        this.offset = -1;
    }

    int line() {
        return line;
    }

    int offset() {
        return offset;
    }
}
