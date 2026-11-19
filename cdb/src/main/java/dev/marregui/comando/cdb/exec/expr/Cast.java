package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * Conversions between the six types, with QuestDB's results: DOUBLE to LONG truncates and is NULL when NaN or out of
 * range; text to LONG reads an optional {@code -}, digits, {@code _} between groups of digits and an optional
 * trailing {@code L} or {@code l}, and anything else, a space included, is NULL; text to DOUBLE ignores characters up
 * to the space at both ends, and a number past the DOUBLE range, which would read as an infinity or as 0, is NaN;
 * text to TIMESTAMP reads the ISO subset of {@link Civil#parse}; text to BOOLEAN is true for {@code true} in any
 * case; numbers to BOOLEAN are true when nonzero and not NULL. Unlike QuestDB, which prints a NULL LONG or a NaN
 * as {@code null} or {@code NaN}, NULL stays NULL.
 */
final class Cast {

    private Cast() {
    }

    /** {@code arg} compiled and read as {@code type}; the vector may be of another type with the same storage. */
    static VectorFn arg(final Bound arg, final CdbType type, final long nowMicros) {
        return coerce(Functions.compile(arg, nowMicros), arg.type(), type);
    }

    /**
     * {@code arg} compiled and read as {@code type} in a comparison: as {@link #arg}, except that a text read as a
     * TIMESTAMP must be one, as QuestDB fails on {@code Invalid date} where a cast gives NULL.
     */
    static VectorFn operand(final Bound arg, final CdbType type, final long nowMicros) {
        if (type != CdbType.TIMESTAMP || !arg.type().isText()) {
            return arg(arg, type, nowMicros);
        }
        final VectorFn source = Functions.compile(arg, nowMicros);
        final Vector out = Vector.of(CdbType.TIMESTAMP, Page.CAPACITY);
        return page -> {
            final Vector in = source.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                final String text = in.textAt(i);
                final long micros = text == null ? Vector.NULL_LONG : Civil.parse(text);
                if (text != null && micros == Vector.NULL_LONG) {
                    throw new CdbException("cannot read text as timestamp [text=" + text + ']', -1);
                }
                out.setLong(i, micros);
            }
            return out;
        };
    }

    /** Each of {@code args} compiled and read as {@code type} in a comparison, as {@link #operand}. */
    static VectorFn[] operands(final List<Bound> args, final int from, final CdbType type, final long nowMicros) {
        final VectorFn[] fns = new VectorFn[args.size() - from];
        for (int i = 0; i < fns.length; i++) {
            fns[i] = operand(args.get(from + i), type, nowMicros);
        }
        return fns;
    }

    /** {@code source} read as {@code to}: the source itself when the two types share their storage. */
    static VectorFn coerce(final VectorFn source, final CdbType from, final CdbType to) {
        return sameStorage(from, to) ? source : exact(source, from, to);
    }

    /** {@code source} converted into a vector whose type is exactly {@code to}. */
    static VectorFn exact(final VectorFn source, final CdbType from, final CdbType to) {
        if (from == to) {
            return source;
        }
        final Vector out = Vector.of(to, Page.CAPACITY);
        final StringBuilder sink = new StringBuilder();
        return page -> {
            final Vector in = source.eval(page);
            final int n = page.size;
            switch (to) {
                case LONG -> {
                    for (int i = 0; i < n; i++) {
                        out.setLong(i, asLong(in, i, false));
                    }
                }
                case TIMESTAMP -> {
                    for (int i = 0; i < n; i++) {
                        out.setLong(i, asLong(in, i, true));
                    }
                }
                case DOUBLE -> {
                    for (int i = 0; i < n; i++) {
                        out.setDouble(i, asDouble(in, i));
                    }
                }
                case BOOLEAN -> {
                    for (int i = 0; i < n; i++) {
                        out.setBoolean(i, asBoolean(in, i));
                    }
                }
                case SYMBOL, VARCHAR -> {
                    for (int i = 0; i < n; i++) {
                        out.setText(i, asText(in, i, sink));
                    }
                }
            }
            return out;
        };
    }

    static boolean sameStorage(final CdbType a, final CdbType b) {
        return a == b || (a.isText() && b.isText()) || (isLongs(a) && isLongs(b));
    }

    static boolean isLongs(final CdbType type) {
        return type == CdbType.LONG || type == CdbType.TIMESTAMP;
    }

    /**
     * The type two operands meet in: equal types keep theirs, two texts meet in VARCHAR, text meets any other type in
     * that type (a text compared with a TIMESTAMP is read as a TIMESTAMP), then DOUBLE wins over TIMESTAMP, and
     * TIMESTAMP over LONG and BOOLEAN.
     */
    static CdbType common(final CdbType a, final CdbType b) {
        if (a == b) {
            return a;
        }
        if (a.isText() && b.isText()) {
            return CdbType.VARCHAR;
        }
        if (a.isText()) {
            return b;
        }
        if (b.isText()) {
            return a;
        }
        if (a == CdbType.DOUBLE || b == CdbType.DOUBLE) {
            return CdbType.DOUBLE;
        }
        if (a == CdbType.TIMESTAMP || b == CdbType.TIMESTAMP) {
            return CdbType.TIMESTAMP;
        }
        return CdbType.LONG;
    }

    static CdbType common(final List<Bound> args) {
        CdbType type = args.getFirst().type();
        for (int i = 1; i < args.size(); i++) {
            type = common(type, args.get(i).type());
        }
        return type;
    }

    static long asLong(final Vector in, final int i, final boolean timestamp) {
        return switch (in.type) {
            case LONG, TIMESTAMP -> in.getLong(i);
            case DOUBLE -> doubleToLong(in.getDouble(i));
            case BOOLEAN -> in.getBoolean(i) ? 1 : 0;
            case SYMBOL, VARCHAR -> {
                final String text = in.textAt(i);
                if (text == null) {
                    yield Vector.NULL_LONG;
                }
                yield timestamp ? Civil.parse(text) : parseLong(text);
            }
        };
    }

    static double asDouble(final Vector in, final int i) {
        return switch (in.type) {
            case LONG, TIMESTAMP -> in.getLong(i) == Vector.NULL_LONG ? Double.NaN : in.getLong(i);
            case DOUBLE -> in.getDouble(i);
            case BOOLEAN -> in.getBoolean(i) ? 1 : 0;
            case SYMBOL, VARCHAR -> parseDouble(in.textAt(i));
        };
    }

    static boolean asBoolean(final Vector in, final int i) {
        return switch (in.type) {
            case LONG, TIMESTAMP -> in.getLong(i) != 0 && in.getLong(i) != Vector.NULL_LONG;
            case DOUBLE -> in.getDouble(i) != 0 && !Double.isNaN(in.getDouble(i));
            case BOOLEAN -> in.getBoolean(i);
            case SYMBOL, VARCHAR -> "true".equalsIgnoreCase(in.textAt(i));
        };
    }

    /** Value {@code i} as text, NULL as null; {@code sink} is scratch space for timestamps. */
    static String asText(final Vector in, final int i, final StringBuilder sink) {
        return switch (in.type) {
            case LONG -> in.getLong(i) == Vector.NULL_LONG ? null : Long.toString(in.getLong(i));
            case TIMESTAMP -> {
                if (in.getLong(i) == Vector.NULL_LONG) {
                    yield null;
                }
                sink.setLength(0);
                Civil.appendIso(sink, in.getLong(i));
                yield sink.toString();
            }
            case DOUBLE -> Double.isNaN(in.getDouble(i)) ? null : Double.toString(in.getDouble(i));
            case BOOLEAN -> in.getBoolean(i) ? "true" : "false";
            case SYMBOL, VARCHAR -> in.textAt(i);
        };
    }

    /** Appends value {@code i} as text; NULL appends nothing. */
    static void appendText(final StringBuilder sink, final Vector in, final int i) {
        switch (in.type) {
            case LONG -> {
                if (in.getLong(i) != Vector.NULL_LONG) {
                    sink.append(in.getLong(i));
                }
            }
            case TIMESTAMP -> {
                if (in.getLong(i) != Vector.NULL_LONG) {
                    Civil.appendIso(sink, in.getLong(i));
                }
            }
            case DOUBLE -> {
                if (!Double.isNaN(in.getDouble(i))) {
                    sink.append(in.getDouble(i));
                }
            }
            case BOOLEAN -> sink.append(in.getBoolean(i));
            case SYMBOL, VARCHAR -> {
                if (!in.isNull(i)) {
                    sink.append(in.textAt(i));
                }
            }
        }
    }

    /** NULL for NaN and for values outside the LONG range; otherwise truncated toward zero. */
    static long doubleToLong(final double value) {
        // 2^63 as a double: the first value past Long.MAX_VALUE
        if (Double.isNaN(value) || value >= 0x1p63 || value < -0x1p63) {
            return Vector.NULL_LONG;
        }
        return (long) value;
    }

    private static long parseLong(final String text) {
        final int n = text.length();
        final boolean negative = n > 0 && text.charAt(0) == '-';
        int i = negative ? 1 : 0;
        if (i == n) {
            return Vector.NULL_LONG;
        }
        long value = 0;
        int groupDigits = 0;
        for (; i < n; i++) {
            final char c = text.charAt(i);
            if (c == '_') {
                if (groupDigits == 0) {
                    return Vector.NULL_LONG;
                }
                groupDigits = 0;
                continue;
            }
            if ((c == 'L' || c == 'l') && i == n - 1) {
                break;
            }
            final int digit = c - '0';
            if (digit < 0 || digit > 9 || value > (Long.MAX_VALUE - digit) / 10) {
                return Vector.NULL_LONG;
            }
            value = value * 10 + digit;
            groupDigits++;
        }
        if (groupDigits == 0) {
            return Vector.NULL_LONG;
        }
        return negative ? -value : value;
    }

    private static double parseDouble(final String text) {
        if (text == null) {
            return Double.NaN;
        }
        final double value;
        try {
            // trims characters up to the space at both ends, as QuestDB's FastDoubleParser skips them
            value = Double.parseDouble(text);
        } catch (final NumberFormatException _) {
            return Double.NaN;
        }
        return (Double.isInfinite(value) || value == 0.0) && pastTheRange(text, value) ? Double.NaN : value;
    }

    // a numeric literal read as an infinity, or as 0 although its significand has a non-zero digit, overflowed the
    // DOUBLE range, which QuestDB rejects; the words Infinity and NaN are not literals and keep their value
    private static boolean pastTheRange(final String text, final double value) {
        int i = 0;
        while (text.charAt(i) <= ' ') {
            i++;
        }
        if (text.charAt(i) == '-' || text.charAt(i) == '+') {
            i++;
        }
        final char first = text.charAt(i);
        if (first == 'I' || first == 'N') {
            return false;
        }
        if (Double.isInfinite(value)) {
            return true;
        }
        final boolean hex = first == '0' && i + 1 < text.length() && (text.charAt(i + 1) | 32) == 'x';
        for (int j = hex ? i + 2 : i; j < text.length(); j++) {
            final char c = text.charAt(j);
            final int lower = c | 32;
            if (lower == (hex ? 'p' : 'e')) {
                return false;
            }
            if ((c >= '1' && c <= '9') || (hex && lower >= 'a' && lower <= 'f')) {
                return true;
            }
        }
        return false;
    }
}
