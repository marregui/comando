package dev.marregui.comando.cdb.serve;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

/**
 * QuestDB's line protocol, one row per line: {@code table,tag=v,tag=v field=1i,field=2.5,field="s",field=t ts}. A
 * backslash takes the next character as it is ({@code \ }, {@code \,}, {@code \=} in names and tags, {@code \"} and
 * {@code \\} in a quoted string, where a line break may also stand). Field values: {@code 1i} LONG, a number DOUBLE,
 * {@code t}, {@code T}, {@code true}, {@code True}, {@code TRUE} (and {@code f} alike) BOOLEAN, a quoted string
 * VARCHAR, {@code 1t} TIMESTAMP in microseconds. The row's timestamp is in the request's {@link Precision}; a line
 * without one takes the time the request arrived. Blank lines are skipped. One parser per body; not thread-safe.
 *
 * <p>The body is UTF-8 bytes, read in place: every byte the grammar looks at is ASCII, which UTF-8 never uses inside
 * a longer sequence, so names and strings are decoded only between those bytes, as decoding the whole body would
 * decode them, a malformed sequence included; an error's offset counts the line's characters, not its bytes.
 */
final class IlpParser {

    private static final byte[][] FALSE_WORDS = ascii("f", "F", "false", "False", "FALSE");
    private static final byte[][] TRUE_WORDS = ascii("t", "T", "true", "True", "TRUE");
    /** A long of this many digits cannot overflow, so it is read by hand; a longer one goes to the JDK. */
    private static final int MAX_LONG_DIGITS = 18;
    /** A significand of at most this many digits is below 2^53, so it converts to a double exactly. */
    private static final int MAX_EXACT_DIGITS = 15;
    /** Every power of ten a double holds exactly: 10^22 is the last, as 5^22 < 2^53 < 5^23. */
    private static final double[] POWERS_OF_TEN = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12,
            1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    private final byte[] body;
    private final int length;
    private final CdbAppender sink;
    private final Precision precision;
    private final long nowMicros;
    // the name or string being unescaped; reused, as every value leaves it as a String of its own
    private final StringBuilder text = new StringBuilder();
    private int position;
    private int line;
    private int lineStart;

    private IlpParser(final byte[] body, final int length, final CdbAppender sink, final Precision precision,
            final long nowMicros) {
        this.body = body;
        this.length = length;
        this.sink = sink;
        this.precision = precision;
        this.nowMicros = nowMicros;
    }

    /**
     * Parses every line of the first {@code length} bytes of {@code body}, UTF-8, into {@code sink}, a row per line,
     * and returns the rows; does not flush, and reads no byte past {@code length}. Stops at the first malformed line
     * or the first row the sink refuses, with an {@link IlpException}: the rows of the lines before it are already in
     * the sink.
     */
    static int parse(final byte[] body, final int length, final CdbAppender sink, final Precision precision,
            final long nowMicros) {
        return new IlpParser(body, length, sink, precision, nowMicros).lines();
    }

    /** {@link #parse(byte[], int, CdbAppender, Precision, long)} of {@code body}'s UTF-8 bytes. */
    @TestOnly
    static int parse(final String body, final CdbAppender sink, final Precision precision, final long nowMicros) {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return parse(bytes, bytes.length, sink, precision, nowMicros);
    }

    private static byte[][] ascii(final String... words) {
        final byte[][] bytes = new byte[words.length][];
        for (int i = 0; i < words.length; i++) {
            bytes[i] = words[i].getBytes(StandardCharsets.US_ASCII);
        }
        return bytes;
    }

    private static boolean isDigit(final byte b) {
        return b >= '0' && b <= '9';
    }

    private int lines() {
        int rows = 0;
        while (position < length) {
            line++;
            lineStart = position;
            if (!atLineEnd()) {
                try {
                    row();
                } catch (final CdbException e) {
                    throw new IlpException(line, e);
                }
                rows++;
            }
            if (!atLineEnd()) {
                throw error("unexpected character after timestamp");
            }
            if (position < length) {
                position += body[position] == '\r' ? 2 : 1;
            }
        }
        return rows;
    }

    private void row() {
        final String table = name(false);
        if (table.isEmpty()) {
            throw error("missing table name");
        }
        sink.table(table);
        while (peek() == ',') {
            position++;
            final String tag = key("missing tag name");
            final String value = name(false);
            if (value.isEmpty()) {
                throw error("missing tag value");
            }
            sink.symbol(tag, value);
        }
        if (peek() != ' ') {
            throw error("missing fields");
        }
        position++;
        field();
        while (peek() == ',') {
            position++;
            field();
        }
        if (atLineEnd()) {
            sink.at(nowMicros);
            return;
        }
        if (peek() != ' ') {
            throw error("unexpected character after field");
        }
        position++;
        sink.at(timestamp());
    }

    private void field() {
        final String name = key("missing field name");
        if (peek() == '"') {
            sink.stringColumn(name, quoted());
            return;
        }
        final int start = position;
        while (!atLineEnd() && peek() != ',' && peek() != ' ') {
            position++;
        }
        final int end = position;
        if (start == end) {
            position = start;
            throw error("missing field value");
        }
        final byte last = body[end - 1];
        if (isOneOf(start, end, TRUE_WORDS)) {
            sink.booleanColumn(name, true);
        } else if (isOneOf(start, end, FALSE_WORDS)) {
            sink.booleanColumn(name, false);
        } else if (last == 'i') {
            sink.longColumn(name, integer(start, end - 1, "could not parse integer field"));
        } else if (last == 't') {
            sink.timestampColumn(name, integer(start, end - 1, "could not parse timestamp field"));
        } else {
            sink.doubleColumn(name, number(start, end));
        }
    }

    private long timestamp() {
        final int start = position;
        while (!atLineEnd() && peek() != ' ' && peek() != ',') {
            position++;
        }
        final long value = integer(start, position, "could not parse timestamp");
        try {
            return precision.toMicros(value);
        } catch (final ArithmeticException _) {
            position = start;
            throw error("timestamp out of range");
        }
    }

    // a name or tag value, up to an unescaped comma, space or line end; an '=' ends a key
    private String name(final boolean key) {
        text.setLength(0);
        int segment = position;
        while (!atLineEnd()) {
            final byte c = body[position];
            if (c == ',' || c == ' ' || key && c == '=') {
                break;
            }
            if (c == '\\') {
                if (position + 1 >= length) {
                    throw error("unfinished escape");
                }
                decode(segment, position);
                position++;
                segment = position;
            }
            position++;
        }
        decode(segment, position);
        return text.toString();
    }

    private String key(final String missing) {
        final String key = name(true);
        if (key.isEmpty()) {
            throw error(missing);
        }
        if (peek() != '=') {
            throw error("missing '='");
        }
        position++;
        return key;
    }

    private String quoted() {
        final int start = position;
        position++;
        text.setLength(0);
        int segment = position;
        while (position < length) {
            final byte c = body[position];
            if (c == '"') {
                decode(segment, position);
                position++;
                return text.toString();
            }
            if (c == '\\' && position + 1 < length) {
                decode(segment, position);
                position++;
                segment = position;
            }
            position++;
        }
        position = start;
        throw error("unclosed string");
    }

    // appends bytes from to to of the body to text: ASCII as it is, else decoded as UTF-8; both ends sit beside an
    // ASCII byte or the body's end, where decoding the whole body would also start or end a character
    private void decode(final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (body[i] < 0) {
                text.append(new String(body, i, to - i, StandardCharsets.UTF_8));
                return;
            }
            text.append((char) body[i]);
        }
    }

    private boolean isOneOf(final int start, final int end, final byte[][] words) {
        for (final byte[] word : words) {
            if (word.length == end - start && Arrays.equals(body, start, end, word, 0, word.length)) {
                return true;
            }
        }
        return false;
    }

    // up to MAX_LONG_DIGITS ASCII digits after an optional sign are read here; anything else, other digits than
    // ASCII's included, as Long.parseLong reads it
    private long integer(final int start, final int end, final String reason) {
        int i = start;
        final boolean negative = i < end && body[i] == '-';
        if (negative || i < end && body[i] == '+') {
            i++;
        }
        if (end > i && end - i <= MAX_LONG_DIGITS) {
            long value = 0L;
            while (i < end && isDigit(body[i])) {
                value = value * 10L + (body[i] - '0');
                i++;
            }
            if (i == end) {
                return negative ? -value : value;
            }
        }
        try {
            return Long.parseLong(new String(body, start, end - start, StandardCharsets.UTF_8));
        } catch (final NumberFormatException _) {
            position = start;
            throw error(reason);
        }
    }

    // Double.parseDouble also takes "1d", hex and surrounding blanks, which a line-protocol float is not
    private double number(final int start, final int end) {
        boolean digit = false;
        for (int i = start; i < end; i++) {
            final byte c = body[i];
            if (isDigit(c)) {
                digit = true;
            } else if (c != '.' && c != '-' && c != '+' && c != 'e' && c != 'E') {
                digit = false;
                break;
            }
        }
        if (digit) {
            final double exact = exact(start, end);
            if (!Double.isNaN(exact)) {
                return exact;
            }
        }
        try {
            if (digit || end - start == 3 && body[start] == 'N' && body[start + 1] == 'a' && body[start + 2] == 'N') {
                return Double.parseDouble(new String(body, start, end - start, StandardCharsets.US_ASCII));
            }
        } catch (final NumberFormatException _) {
            // fall through to the error below, at the value's start
        }
        position = start;
        throw error("could not parse field value");
    }

    /**
     * The value of a decimal {@code [+-]digits[.digits][(e|E)[+-]digits]} of at most {@link #MAX_EXACT_DIGITS}
     * significant digits and a power of ten of magnitude at most 22, or NaN, which no decimal is, for any other
     * text. Both the significand and the power are exact doubles, so one multiplication or division rounds the
     * decimal's value correctly (Clinger's fast path), as {@link Double#parseDouble(String)} does.
     */
    private double exact(final int start, final int end) {
        int i = start;
        final boolean negative = body[i] == '-';
        if (negative || body[i] == '+') {
            i++;
        }
        long significand = 0L;
        int digits = 0;
        int scale = 0;
        boolean any = false;
        boolean fraction = false;
        for (; i < end; i++) {
            final byte c = body[i];
            if (c == '.' && !fraction) {
                fraction = true;
                continue;
            }
            if (!isDigit(c)) {
                break;
            }
            any = true;
            if (fraction) {
                scale--;
            }
            if (significand != 0L || c != '0') {
                if (++digits > MAX_EXACT_DIGITS) {
                    return Double.NaN;
                }
                significand = significand * 10L + (c - '0');
            }
        }
        if (!any) {
            return Double.NaN;
        }
        int exponent = 0;
        if (i < end && (body[i] == 'e' || body[i] == 'E')) {
            i++;
            final boolean negativeExponent = i < end && body[i] == '-';
            if (negativeExponent || i < end && body[i] == '+') {
                i++;
            }
            final int exponentStart = i;
            // past three digits the power is far out of the fast path's range; the rest is left unread
            while (i < end && isDigit(body[i]) && exponent < 1_000) {
                exponent = exponent * 10 + (body[i] - '0');
                i++;
            }
            if (i == exponentStart) {
                return Double.NaN;
            }
            if (negativeExponent) {
                exponent = -exponent;
            }
        }
        final int power = scale + exponent;
        if (i != end || power < -22 || power > 22) {
            return Double.NaN;
        }
        final double value = power >= 0 ? significand * POWERS_OF_TEN[power] : significand / POWERS_OF_TEN[-power];
        return negative ? -value : value;
    }

    private byte peek() {
        return position < length ? body[position] : (byte) '\n';
    }

    private boolean atLineEnd() {
        if (position >= length) {
            return true;
        }
        final byte c = body[position];
        return c == '\n' || c == '\r' && (position + 1 >= length || body[position + 1] == '\n');
    }

    // the offset counts characters, as the line reads decoded: a byte offset would move past every non-ASCII one
    private IlpException error(final String reason) {
        int offset = position - lineStart;
        for (int i = lineStart; i < position; i++) {
            if (body[i] < 0) {
                offset = new String(body, lineStart, position - lineStart, StandardCharsets.UTF_8).length();
                break;
            }
        }
        return new IlpException(reason, line, offset);
    }
}
