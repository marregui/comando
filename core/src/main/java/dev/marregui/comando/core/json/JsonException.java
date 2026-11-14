package dev.marregui.comando.core.json;

/**
 * A JSON document that cannot be read, a tree that cannot be written, or a value a node cannot give: thrown by every
 * parse failure, by {@link JsonNode#required(String)} and by the accessors that cannot convert. The message is
 * {@code what [details]}, ASCII only, with the line and column of the offending character appended when the failure
 * has a place in the input; {@link #getOriginalMessage()} leaves the place out.
 */
public final class JsonException extends RuntimeException {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final long NO_PLACE = -1L;
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int LAST_PRINTABLE = 0x7E;
    private final String what;
    private final String details;
    private final long line;
    private final long column;

    /**
     * A failure without a place in an input.
     *
     * @param what    the verb phrase that says what failed
     * @param details the values to act on, as {@code key=value} pairs separated by {@code ", "}; empty for none
     */
    public JsonException(final String what, final String details) {
        this(what, details, NO_PLACE, NO_PLACE, null);
    }

    /**
     * A failure caused by another, such as the read of the input failing.
     *
     * @param what    the verb phrase that says what failed
     * @param details the values to act on, as {@code key=value} pairs; empty for none
     * @param cause   the failure underneath
     */
    public JsonException(final String what, final String details, final Throwable cause) {
        this(what, details, NO_PLACE, NO_PLACE, cause);
    }

    /** A parse failure at {@code line} and {@code column} of the input, both counted from 1. */
    JsonException(final String what, final String details, final long line, final long column) {
        this(what, details, line, column, null);
    }

    private JsonException(
            final String what, final String details, final long line, final long column, final Throwable cause) {
        super(ascii(what), cause);
        this.what = ascii(what);
        this.details = ascii(details);
        this.line = line;
        this.column = column;
    }

    /** What failed and its values, then the line and column when the failure has a place in the input. */
    @Override
    public String getMessage() {
        if (line == NO_PLACE) {
            return getOriginalMessage();
        }
        final String place = "line=" + line + ", column=" + column;
        return what + " [" + (details.isEmpty() ? place : details + ", " + place) + ']';
    }

    /** What failed and its values, without the place in the input. */
    public String getOriginalMessage() {
        return details.isEmpty() ? what : what + " [" + details + ']';
    }

    /** {@code text} with every character outside printable ASCII written as {@code U+XXXX}. */
    static String ascii(final CharSequence text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c >= FIRST_PRINTABLE && c <= LAST_PRINTABLE) {
                out.append(c);
            } else {
                out.append("U+").append(HEX[c >> 12 & 0xF]).append(HEX[c >> 8 & 0xF]).append(HEX[c >> 4 & 0xF])
                        .append(HEX[c & 0xF]);
            }
        }
        return out.toString();
    }
}
