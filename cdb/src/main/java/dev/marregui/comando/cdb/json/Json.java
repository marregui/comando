package dev.marregui.comando.cdb.json;

import java.util.Arrays;

/**
 * A scanner of JSON texts (RFC 8259) held as UTF-8 bytes: it checks a text, and finds the value a {@link JsonPath}
 * names in one forward pass that stops at that value. It never recurses, so any depth of nesting scans, and it
 * allocates only to grow its nesting stack and its decode buffer to the largest it met. The bytes are taken as
 * well-formed UTF-8 (cdb encodes its text from Java strings), so only the grammar is checked: a string may hold any
 * byte from 0x20 up but an unescaped quote or backslash. One instance per thread.
 */
public final class Json {
    /** What {@link #find} returns when the path names no value, or the text breaks before the value. */
    public static final long ABSENT = -1L;
    /** What {@link #unescape} returns for a string without escapes, whose bytes are its value. */
    public static final int RAW = -1;
    /** What {@link #invalidAt} returns for a valid text. */
    public static final int VALID = -1;

    private static final int FIRST_DEPTH = 32; // sizing hint
    private static final int FIRST_BUFFER = 64; // sizing hint
    private static final int SPAN_BITS = 32;
    private static final long SPAN_MASK = 0xFFFF_FFFFL;
    private static final byte OBJECT = 1;
    private static final byte ARRAY = 2;
    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] NULL = {'n', 'u', 'l', 'l'};
    private static final int HEX_DIGITS = 4;
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8; // the largest array size every JVM allocates
    private static final int ESCAPE_U_LENGTH = 6; // a backslash, a u and four hex digits
    // what String.getBytes(UTF_8) writes for a lone surrogate, so a decoded value encodes as its Java string would
    private static final byte UNPAIRED = '?';

    // the open containers, OBJECT or ARRAY, innermost at depth - 1; grown, never shrunk
    private byte[] stack = new byte[FIRST_DEPTH];
    private byte[] buffer = new byte[FIRST_BUFFER];
    // set by string(): whether the string it scanned last holds an escape
    private boolean escaped;

    /** The value's first byte in a span {@link #find} returns. */
    public static int start(final long span) {
        return (int) (span >>> SPAN_BITS);
    }

    /** The offset past the value's last byte in a span {@link #find} returns. */
    public static int end(final long span) {
        return (int) (span & SPAN_MASK);
    }

    /** True when the value at {@code a[start]} is a JSON string. */
    public static boolean isString(final byte[] a, final int start) {
        return a[start] == '"';
    }

    /** True when the value at {@code a[start]} is the literal {@code null}. */
    public static boolean isNull(final byte[] a, final int start) {
        return a[start] == 'n';
    }

    /** The offset of the first byte of {@code a[from, to)} that breaks one JSON text, or {@link #VALID}. */
    public int invalidAt(final byte[] a, final int from, final int to) {
        final int end = value(a, whitespace(a, from, to), to);
        if (end < 0) {
            return failedAt(end);
        }
        final int p = whitespace(a, end, to);
        return p == to ? VALID : p;
    }

    /**
     * The span of the value {@code path} names in the JSON text {@code a[from, to)}, the whitespace around it left
     * out, or {@link #ABSENT}. An object member is the first of its name. The bytes past the value are not read, so
     * a text broken after it still yields it.
     */
    public long find(final byte[] a, final int from, final int to, final JsonPath path) {
        int p = whitespace(a, from, to);
        for (int s = 0, n = path.steps(); s < n && p >= 0; s++) {
            p = path.isIndex(s) ? element(a, p, to, path.index(s)) : member(a, p, to, path.key(s));
        }
        if (p < 0) {
            return ABSENT;
        }
        final int end = value(a, p, to);
        return end < 0 ? ABSENT : (long) p << SPAN_BITS | end;
    }

    /**
     * Decodes the escapes of the string contents {@code a[from, to)} (the quotes left out) into {@link #buffer()} and
     * returns the decoded length, or {@link #RAW} when there is no escape. The contents must be a valid string's.
     */
    public int unescape(final byte[] a, final int from, final int to) {
        int p = from;
        while (p < to && a[p] != '\\') {
            p++;
        }
        if (p == to) {
            return RAW;
        }
        ensureBuffer(to - from);
        int n = p - from;
        System.arraycopy(a, from, buffer, 0, n);
        while (p < to) {
            final byte b = a[p];
            if (b != '\\') {
                buffer[n++] = b;
                p++;
                continue;
            }
            final byte c = a[p + 1];
            if (c != 'u') {
                buffer[n++] = unescaped(c);
                p += 2;
                continue;
            }
            int cp = hex(a, p + 2);
            p += ESCAPE_U_LENGTH;
            if (Character.isHighSurrogate((char) cp)) {
                if (p + ESCAPE_U_LENGTH <= to && a[p] == '\\' && a[p + 1] == 'u'
                        && Character.isLowSurrogate((char) hex(a, p + 2))) {
                    cp = Character.toCodePoint((char) cp, (char) hex(a, p + 2));
                    p += ESCAPE_U_LENGTH;
                } else {
                    cp = -1;
                }
            } else if (Character.isLowSurrogate((char) cp)) {
                cp = -1;
            }
            n = encode(cp, n);
        }
        return n;
    }

    /** The bytes {@link #unescape} decoded last; valid until its next call. */
    public byte[] buffer() {
        return buffer;
    }

    private static int escape(final byte[] a, final int p, final int to) {
        if (p + 1 >= to) {
            return fail(p + 1);
        }
        return switch (a[p + 1]) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> p + 2;
            case 'u' -> {
                for (int i = p + 2; i < p + 2 + HEX_DIGITS; i++) {
                    if (i >= to || Character.digit(a[i], 16) < 0) {
                        yield fail(i);
                    }
                }
                yield p + ESCAPE_U_LENGTH;
            }
            default -> fail(p + 1);
        };
    }

    private static int number(final byte[] a, final int from, final int to) {
        int p = from;
        if (a[p] == '-') {
            p++;
        }
        if (p >= to) {
            return fail(p);
        }
        if (a[p] == '0') {
            p++;
        } else if (isDigit(a[p])) {
            p = digits(a, p, to);
        } else {
            return fail(p);
        }
        if (p < to && a[p] == '.') {
            p++;
            if (p >= to || !isDigit(a[p])) {
                return fail(p);
            }
            p = digits(a, p, to);
        }
        if (p < to && (a[p] == 'e' || a[p] == 'E')) {
            p++;
            if (p < to && (a[p] == '+' || a[p] == '-')) {
                p++;
            }
            if (p >= to || !isDigit(a[p])) {
                return fail(p);
            }
            p = digits(a, p, to);
        }
        return p;
    }

    private static int digits(final byte[] a, final int from, final int to) {
        int p = from;
        while (p < to && isDigit(a[p])) {
            p++;
        }
        return p;
    }

    private static boolean isDigit(final byte b) {
        return b >= '0' && b <= '9';
    }

    private static int literal(final byte[] a, final int p, final int to, final byte[] word) {
        for (int i = 0; i < word.length; i++) {
            if (p + i >= to || a[p + i] != word[i]) {
                return fail(p + i);
            }
        }
        return p + word.length;
    }

    // after a member's or an element's value ending at p: the first byte of the next one, or a failure at the close
    private static int next(final byte[] a, final int p, final int to) {
        if (p < 0) {
            return p;
        }
        final int q = whitespace(a, p, to);
        if (q < to && a[q] == ',') {
            return whitespace(a, q + 1, to);
        }
        return fail(q);
    }

    private static int whitespace(final byte[] a, final int from, final int to) {
        if (from < 0) {
            return from;
        }
        int p = from;
        while (p < to) {
            final byte b = a[p];
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                break;
            }
            p++;
        }
        return p;
    }

    private static byte unescaped(final byte c) {
        return switch (c) {
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> c; // '"', '\\', '/'
        };
    }

    private static int hex(final byte[] a, final int at) {
        int v = 0;
        for (int i = at; i < at + HEX_DIGITS; i++) {
            v = v << 4 | Character.digit(a[i], 16);
        }
        return v;
    }

    // a failure at offset p, as a negative position
    private static int fail(final int p) {
        return -1 - p;
    }

    private static int failedAt(final int failure) {
        return -1 - failure;
    }

    // the offset past the value at a[from], or a failure; iterative: the open containers are on the stack
    private int value(final byte[] a, final int from, final int to) {
        int depth = 0;
        int p = from;
        while (true) {
            p = whitespace(a, p, to);
            if (p >= to) {
                return fail(p);
            }
            final byte b = a[p];
            if (b == '{' || b == '[') {
                final byte close = b == '{' ? (byte) '}' : (byte) ']';
                p = whitespace(a, p + 1, to);
                if (p < to && a[p] == close) {
                    p++;
                } else {
                    push(depth++, b == '{' ? OBJECT : ARRAY);
                    if (b == '{') {
                        p = key(a, p, to);
                        if (p < 0) {
                            return p;
                        }
                    }
                    continue;
                }
            } else {
                p = scalar(a, p, to);
                if (p < 0) {
                    return p;
                }
            }
            // a value ended: close the containers it ends, or go on to the next element or member
            boolean next = false;
            while (depth > 0 && !next) {
                p = whitespace(a, p, to);
                if (p >= to) {
                    return fail(p);
                }
                final byte c = a[p];
                final byte top = stack[depth - 1];
                if (c == ',') {
                    p++;
                    if (top == OBJECT) {
                        p = key(a, whitespace(a, p, to), to);
                        if (p < 0) {
                            return p;
                        }
                    }
                    next = true;
                } else if (c == (top == OBJECT ? '}' : ']')) {
                    depth--;
                    p++;
                } else {
                    return fail(p);
                }
            }
            if (!next) {
                return p;
            }
        }
    }

    // the offset past a string, number or literal at a[p], or a failure
    private int scalar(final byte[] a, final int p, final int to) {
        return switch (a[p]) {
            case '"' -> string(a, p, to);
            case 't' -> literal(a, p, to, TRUE);
            case 'f' -> literal(a, p, to, FALSE);
            case 'n' -> literal(a, p, to, NULL);
            default -> number(a, p, to);
        };
    }

    // a member's name and its colon at a[p]: the offset of the value's first byte, or a failure
    private int key(final byte[] a, final int p, final int to) {
        if (p >= to || a[p] != '"') {
            return fail(p);
        }
        final int end = whitespace(a, string(a, p, to), to);
        if (end < 0) {
            return end;
        }
        if (end >= to || a[end] != ':') {
            return fail(end);
        }
        return end + 1;
    }

    private int string(final byte[] a, final int from, final int to) {
        escaped = false;
        int p = from + 1;
        while (p < to) {
            final int b = a[p] & 0xFF;
            if (b == '"') {
                return p + 1;
            }
            if (b == '\\') {
                escaped = true;
                p = escape(a, p, to);
                if (p < 0) {
                    return p;
                }
            } else if (b < 0x20) {
                return fail(p);
            } else {
                p++;
            }
        }
        return fail(p);
    }

    // the member named key of the object at a[p]: the offset of its value, or a failure when absent or broken
    private int member(final byte[] a, final int from, final int to, final byte[] key) {
        if (from >= to || a[from] != '{') {
            return fail(from);
        }
        int p = whitespace(a, from + 1, to);
        while (p >= 0 && p < to && a[p] == '"') {
            final int nameEnd = string(a, p, to);
            if (nameEnd < 0) {
                return nameEnd;
            }
            final boolean hit = sameKey(a, p + 1, nameEnd - 1, key);
            p = whitespace(a, nameEnd, to);
            if (p >= to || a[p] != ':') {
                return fail(p);
            }
            p = whitespace(a, p + 1, to);
            if (hit) {
                return p;
            }
            p = next(a, value(a, p, to), to);
        }
        return p < 0 ? p : fail(p);
    }

    // element index of the array at a[p]: the offset of its value, or a failure when absent or broken
    private int element(final byte[] a, final int from, final int to, final int index) {
        if (from >= to || a[from] != '[' || index < 0) {
            return fail(from);
        }
        int p = whitespace(a, from + 1, to);
        if (p < to && a[p] == ']') {
            return fail(p);
        }
        for (int i = 0; i < index && p >= 0; i++) {
            p = next(a, value(a, p, to), to);
        }
        return p;
    }

    // escaped holds whether the name string() scanned last has an escape
    private boolean sameKey(final byte[] a, final int from, final int to, final byte[] key) {
        if (!escaped) {
            return Arrays.equals(a, from, to, key, 0, key.length);
        }
        final int n = unescape(a, from, to);
        return Arrays.equals(buffer, 0, n, key, 0, key.length);
    }

    private void push(final int depth, final byte container) {
        if (depth == stack.length) {
            stack = Arrays.copyOf(stack, Math.multiplyExact(stack.length, 2));
        }
        stack[depth] = container;
    }

    private void ensureBuffer(final int length) {
        // a decoded string is never longer than its escaped form
        if (buffer.length < length) {
            buffer = new byte[(int) Math.max(length, Math.min(MAX_ARRAY_SIZE, buffer.length * 2L))];
        }
    }

    private int encode(final int cp, final int at) {
        int n = at;
        if (cp < 0) {
            buffer[n++] = UNPAIRED;
        } else if (cp < 0x80) {
            buffer[n++] = (byte) cp;
        } else if (cp < 0x800) {
            buffer[n++] = (byte) (0xC0 | cp >>> 6);
            buffer[n++] = (byte) (0x80 | cp & 0x3F);
        } else if (cp < 0x10000) {
            buffer[n++] = (byte) (0xE0 | cp >>> 12);
            buffer[n++] = (byte) (0x80 | cp >>> 6 & 0x3F);
            buffer[n++] = (byte) (0x80 | cp & 0x3F);
        } else {
            buffer[n++] = (byte) (0xF0 | cp >>> 18);
            buffer[n++] = (byte) (0x80 | cp >>> 12 & 0x3F);
            buffer[n++] = (byte) (0x80 | cp >>> 6 & 0x3F);
            buffer[n++] = (byte) (0x80 | cp & 0x3F);
        }
        return n;
    }
}
