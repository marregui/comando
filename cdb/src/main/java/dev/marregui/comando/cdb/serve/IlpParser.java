package dev.marregui.comando.cdb.serve;

import java.io.Serial;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

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
    /** A significand of at most this many digits fits 64 bits unsigned, as 10^19 - 1 < 2^64. */
    private static final int MAX_SIGNIFICAND_DIGITS = 19;
    /** A significand up to this converts to a double exactly. */
    private static final long MAX_EXACT_SIGNIFICAND = 1L << 53;
    /** Every power of ten a double holds exactly: 10^22 is the last, as 5^22 < 2^53 < 5^23. */
    private static final double[] POWERS_OF_TEN = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12,
            1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
    /**
     * The powers of ten Eisel-Lemire reads: (2^64 - 1) * 10^-326 is below the smallest normal double, and 10^309 is
     * past the largest; a power outside goes to the JDK, as does a subnormal result.
     */
    private static final int MIN_POWER = -325;
    private static final int MAX_POWER = 308;
    /**
     * For each power q in [MIN_POWER, MAX_POWER], 5^q's 128 leading bits, truncated, high word then low: 5^q shifted
     * into [2^127, 2^128), or for q < 0, 2^(2b + 128) / 5^-q, b the bit length of 5^-q, shifted alike. The table of
     * fast_float and of QuestDB's FastDoubleMath, computed here once rather than spelled out.
     */
    private static final long[] POWERS_OF_FIVE = powersOfFive();
    private static final int INITIAL_FIELDS = 16;
    /** The first copy of a line a region's end cuts; a sizing hint, doubled until the line fits. */
    private static final int CUT_LINE_BYTES = 4_096;
    private static final Incomplete INCOMPLETE = new Incomplete();

    private final CdbAppender target;
    private final Precision precision;
    private final long nowMicros;
    // the name or string being unescaped; reused, as every value leaves it as a String of its own
    private final StringBuilder text = new StringBuilder();
    // the bytes being parsed, the whole body or one region of it; a region that does not end the body throws
    // INCOMPLETE at every look past its end, where the body's end would end the line
    private byte[] body;
    private int length;
    private boolean lastRegion = true;
    // the target, or in a region that does not end the body, a buffer that hands each row to the target at its end:
    // a row the region's end cuts is parsed again whole, and the target sees it once
    private CdbAppender sink;
    private RowBuffer rowBuffer;
    // a copy of a line a region's end cuts, reused for the next one
    private byte[] cutLine;
    private int position;
    private int line;
    private int lineStart;
    private int rows;

    private IlpParser(final byte[] body, final int length, final CdbAppender sink, final Precision precision,
            final long nowMicros) {
        this.body = body;
        this.length = length;
        this.target = sink;
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

    /**
     * As above, for a body held in {@code chunks} in order, each as long as the first. A line a chunk's end cuts is
     * copied whole and parsed from the copy, so the rows, their values and the first malformed line, its offset
     * included, are those of the body read as one array. Outside the last chunk a row reaches {@code sink} at its
     * end, so a call the sink refuses before {@code at} is refused there, after any malformed byte of the same line.
     */
    static int parse(final byte[][] chunks, final int length, final CdbAppender sink, final Precision precision,
            final long nowMicros) {
        final int size = chunks[0].length;
        if (length <= size) {
            return parse(chunks[0], length, sink, precision, nowMicros);
        }
        return new IlpParser(chunks[0], size, sink, precision, nowMicros).lines(chunks, length);
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

    private static long[] powersOfFive() {
        final long[] table = new long[2 * (MAX_POWER - MIN_POWER + 1)];
        final BigInteger five = BigInteger.valueOf(5L);
        for (int q = MIN_POWER; q <= MAX_POWER; q++) {
            BigInteger power;
            if (q >= 0) {
                power = five.pow(q);
            } else {
                final BigInteger divisor = five.pow(-q);
                power = BigInteger.ONE.shiftLeft(2 * divisor.bitLength() + 128).divide(divisor);
            }
            final int shift = 128 - power.bitLength();
            power = shift >= 0 ? power.shiftLeft(shift) : power.shiftRight(-shift);
            final int i = 2 * (q - MIN_POWER);
            table[i] = power.shiftRight(64).longValue();
            table[i + 1] = power.longValue();
        }
        return table;
    }

    private static boolean isDigit(final byte b) {
        return b >= '0' && b <= '9';
    }

    /**
     * {@code significand * 10^power}, the significand unsigned and not zero and the power in [MIN_POWER, MAX_POWER],
     * correctly rounded by Eisel-Lemire as fast_float and QuestDB's FastDoubleMath compute it, or NaN where the
     * product's bits cannot decide the rounding, or the result is subnormal or infinite.
     */
    private static double eiselLemire(final boolean negative, final long significand, final int power) {
        final int index = 2 * (power - MIN_POWER);
        final long factor = POWERS_OF_FIVE[index];
        // 1023 + 64 + power + floor(log2(5^power)): ((152170 + 65536) * power) >> 16 is power + floor(log2(5^power))
        // for power in (-400, 350)
        final long exponent = (((152_170L + 65_536L) * power) >> 16) + 1_023L + 64L;
        int leadingZeros = Long.numberOfLeadingZeros(significand);
        final long normalized = significand << leadingZeros;
        long upper = Math.unsignedMultiplyHigh(normalized, factor);
        final long lower = normalized * factor;
        // the 64-bit factor is short of 5^power by less than one unit of its last bit: past 55 exact leading bits of
        // the product, the error may carry into them, so the low word of the factor is multiplied in too
        if ((upper & 0x1ffL) == 0x1ffL && Long.compareUnsigned(lower + normalized, lower) < 0) {
            final long factorLow = POWERS_OF_FIVE[index + 1];
            final long productLow = normalized * factorLow;
            final long productMiddle = lower + Math.unsignedMultiplyHigh(normalized, factorLow);
            long productHigh = upper;
            if (Long.compareUnsigned(productMiddle, lower) < 0) {
                productHigh++;
            }
            if (productMiddle + 1L == 0L && (productHigh & 0x1ffL) == 0x1ffL
                    && Long.compareUnsigned(productLow + normalized, productLow) < 0) {
                return Double.NaN;
            }
            upper = productHigh;
        }
        final long upperBit = upper >>> 63;
        long mantissa = upper >>> (upperBit + 9);
        leadingZeros += (int) (1L ^ upperBit);
        // all ones, or a tie to round to even: more bits than the product's would decide it
        if ((upper & 0x1ffL) == 0x1ffL || (upper & 0x1ffL) == 0L && (mantissa & 3L) == 1L) {
            return Double.NaN;
        }
        mantissa = (mantissa + 1L) >>> 1;
        if (mantissa >= 1L << 53) {
            // the rounding carried into a new leading bit, as for 7.2057594037927933e+16
            mantissa = 1L << 52;
            leadingZeros--;
        }
        mantissa &= ~(1L << 52);
        final long realExponent = exponent - leadingZeros;
        if (realExponent < 1L || realExponent > 2_046L) {
            return Double.NaN;
        }
        return Double.longBitsToDouble(mantissa | realExponent << 52 | (negative ? Long.MIN_VALUE : 0L));
    }

    private int lines() {
        while (position < length) {
            line();
        }
        return rows;
    }

    // the body region by region: a line a region's end cuts is parsed from a copy of it whole, then the region
    // holding the line's end goes on from there
    private int lines(final byte[][] chunks, final int bodyLength) {
        final int size = chunks[0].length;
        int at = 0;
        while (at < bodyLength) {
            final int chunk = at / size;
            final int start = chunk * size;
            region(chunks[chunk], Math.min(size, bodyLength - start), bodyLength - start <= size);
            position = at - start;
            try {
                lines();
                at = start + length;
            } catch (final Incomplete _) {
                at = cutLine(chunks, bodyLength, start + lineStart, start + length);
            }
        }
        return rows;
    }

    // the line from start, which a region ending at regionEnd cut, parsed from a copy that doubles until the line
    // ends in it or the copy ends the body; the offset in the body after the line
    private int cutLine(final byte[][] chunks, final int bodyLength, final int start, final int regionEnd) {
        int copy = Math.max(CUT_LINE_BYTES, 2 * (regionEnd - start));
        while (true) {
            final int end = (int) Math.min(bodyLength, (long) start + copy);
            final int bytes = end - start;
            if (cutLine == null || cutLine.length < bytes) {
                cutLine = new byte[Math.max(bytes, cutLine == null ? 0 : 2 * cutLine.length)];
            }
            final int size = chunks[0].length;
            for (int from = start; from < end; ) {
                final int n = Math.min(end - from, size - from % size);
                System.arraycopy(chunks[from / size], from % size, cutLine, from - start, n);
                from += n;
            }
            region(cutLine, bytes, end == bodyLength);
            position = 0;
            // the cut attempt counted the line already
            line--;
            try {
                line();
                return start + position;
            } catch (final Incomplete _) {
                // the copy ends inside the line: a longer one, at most the body's rest, whose end ends the line
                copy = (int) Math.min(2L * copy, bodyLength - start);
            }
        }
    }

    private void region(final byte[] bytes, final int bytesLength, final boolean last) {
        body = bytes;
        length = bytesLength;
        lastRegion = last;
        if (last) {
            sink = target;
        } else {
            if (rowBuffer == null) {
                rowBuffer = new RowBuffer(target);
            }
            sink = rowBuffer;
        }
    }

    // one line, from position through its line break; a row unless it is blank. Once the sink has the row, no look
    // past the region's end is left, so a cut line never reaches the target twice
    private void line() {
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
                    endOfRegion();
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
        endOfRegion();
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
     * The value of a decimal {@code [+-]digits[.digits][(e|E)[+-]digits]} of at most
     * {@link #MAX_SIGNIFICAND_DIGITS} significant digits, rounded as {@link Double#parseDouble(String)} rounds it, or
     * NaN, which no decimal is, for any other text and where neither path below decides. A significand up to 2^53 and
     * a power of ten of magnitude at most 22 are both exact doubles, so one multiplication or division rounds
     * correctly (Clinger's fast path); else Eisel-Lemire, for a power in [MIN_POWER, MAX_POWER]. A zero is zero of
     * the decimal's sign, whatever its power.
     */
    private double exact(final int start, final int end) {
        int i = start;
        final boolean negative = body[i] == '-';
        if (negative || body[i] == '+') {
            i++;
        }
        // unsigned: 19 digits may pass Long.MAX_VALUE, never 2^64
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
                if (++digits > MAX_SIGNIFICAND_DIGITS) {
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
            // past three digits the power is far out of both paths' range; the rest is left unread
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
        if (i != end) {
            return Double.NaN;
        }
        if (significand == 0L) {
            return negative ? -0.0 : 0.0;
        }
        final int power = scale + exponent;
        if (power >= -22 && power <= 22 && Long.compareUnsigned(significand, MAX_EXACT_SIGNIFICAND) <= 0) {
            final double value = power >= 0 ? significand * POWERS_OF_TEN[power]
                    : significand / POWERS_OF_TEN[-power];
            return negative ? -value : value;
        }
        if (power < MIN_POWER || power > MAX_POWER) {
            return Double.NaN;
        }
        return eiselLemire(negative, significand, power);
    }

    // past the bytes of a region that does not end the body, the line goes on in the next region
    private void endOfRegion() {
        if (!lastRegion) {
            throw INCOMPLETE;
        }
    }

    private byte peek() {
        if (position < length) {
            return body[position];
        }
        endOfRegion();
        return '\n';
    }

    private boolean atLineEnd() {
        if (position >= length) {
            endOfRegion();
            return true;
        }
        final byte c = body[position];
        if (c != '\r') {
            return c == '\n';
        }
        if (position + 1 >= length) {
            endOfRegion();
            return true;
        }
        return body[position + 1] == '\n';
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

    /** A look past the end of a region that does not end the body. One instance, without a stack trace. */
    private static final class Incomplete extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        Incomplete() {
            super("line goes on past the region", null, false, false);
        }
    }

    /** One row's calls, handed to the target at the row's end; {@link #table} starts a row afresh. */
    private static final class RowBuffer implements CdbAppender {

        private final CdbAppender target;
        private String table;
        private int fields;
        private CdbType[] types = new CdbType[INITIAL_FIELDS];
        private String[] names = new String[INITIAL_FIELDS];
        private long[] longs = new long[INITIAL_FIELDS];
        private double[] doubles = new double[INITIAL_FIELDS];
        private String[] texts = new String[INITIAL_FIELDS];

        RowBuffer(final CdbAppender target) {
            this.target = target;
        }

        @Override
        public CdbAppender table(final String name) {
            table = name;
            fields = 0;
            return this;
        }

        @Override
        public CdbAppender symbol(final String column, final String value) {
            texts[add(column, CdbType.SYMBOL)] = value;
            return this;
        }

        @Override
        public CdbAppender stringColumn(final String column, final String value) {
            texts[add(column, CdbType.VARCHAR)] = value;
            return this;
        }

        @Override
        public CdbAppender longColumn(final String column, final long value) {
            longs[add(column, CdbType.LONG)] = value;
            return this;
        }

        @Override
        public CdbAppender doubleColumn(final String column, final double value) {
            doubles[add(column, CdbType.DOUBLE)] = value;
            return this;
        }

        @Override
        public CdbAppender booleanColumn(final String column, final boolean value) {
            longs[add(column, CdbType.BOOLEAN)] = value ? 1L : 0L;
            return this;
        }

        @Override
        public CdbAppender timestampColumn(final String column, final long micros) {
            longs[add(column, CdbType.TIMESTAMP)] = micros;
            return this;
        }

        @Override
        public void at(final long micros) {
            target.table(table);
            for (int f = 0; f < fields; f++) {
                switch (types[f]) {
                    case SYMBOL -> target.symbol(names[f], texts[f]);
                    case VARCHAR -> target.stringColumn(names[f], texts[f]);
                    case LONG -> target.longColumn(names[f], longs[f]);
                    case DOUBLE -> target.doubleColumn(names[f], doubles[f]);
                    case BOOLEAN -> target.booleanColumn(names[f], longs[f] != 0L);
                    case TIMESTAMP -> target.timestampColumn(names[f], longs[f]);
                }
            }
            fields = 0;
            target.at(micros);
        }

        @Override
        public void flush() {
            // the parser never flushes
        }

        @Override
        public void discard() {
            fields = 0;
        }

        @Override
        public void close() {
            // nothing is held
        }

        // a row has fewer fields than its line has bytes, so doubling stays far from an int's overflow
        private int add(final String column, final CdbType type) {
            if (fields == types.length) {
                final int grown = 2 * fields;
                types = Arrays.copyOf(types, grown);
                names = Arrays.copyOf(names, grown);
                longs = Arrays.copyOf(longs, grown);
                doubles = Arrays.copyOf(doubles, grown);
                texts = Arrays.copyOf(texts, grown);
            }
            types[fields] = type;
            names[fields] = column;
            return fields++;
        }
    }
}
