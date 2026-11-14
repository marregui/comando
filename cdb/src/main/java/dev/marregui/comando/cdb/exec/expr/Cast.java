package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * Conversions between the six types, with QuestDB's results: DOUBLE to LONG truncates and is NULL when NaN or out of
 * range; text to LONG is strict (an optional {@code -} and digits); text to TIMESTAMP reads the ISO subset of
 * {@link Civil#parse}; text to BOOLEAN is true for {@code true} in any case; numbers to BOOLEAN are true when nonzero
 * and not NULL. Unlike QuestDB, which prints a NULL LONG or a NaN as {@code null} or {@code NaN}, NULL stays NULL.
 */
final class Cast {

    private Cast() {
    }

    /** {@code arg} compiled and read as {@code type}; the vector may be of another type with the same storage. */
    static VectorFn arg(final Bound arg, final CdbType type, final long nowMicros) {
        return coerce(Functions.compile(arg, nowMicros), arg.type(), type);
    }

    /** Each of {@code args} compiled and read as {@code type}. */
    static VectorFn[] args(final List<Bound> args, final int from, final CdbType type, final long nowMicros) {
        final VectorFn[] fns = new VectorFn[args.size() - from];
        for (int i = 0; i < fns.length; i++) {
            fns[i] = arg(args.get(from + i), type, nowMicros);
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
                        out.longs[i] = asLong(in, i, false);
                    }
                }
                case TIMESTAMP -> {
                    for (int i = 0; i < n; i++) {
                        out.longs[i] = asLong(in, i, true);
                    }
                }
                case DOUBLE -> {
                    for (int i = 0; i < n; i++) {
                        out.doubles[i] = asDouble(in, i);
                    }
                }
                case BOOLEAN -> {
                    for (int i = 0; i < n; i++) {
                        out.booleans[i] = asBoolean(in, i);
                    }
                }
                case SYMBOL, VARCHAR -> {
                    for (int i = 0; i < n; i++) {
                        out.texts[i] = asText(in, i, sink);
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
            case LONG, TIMESTAMP -> in.longs[i];
            case DOUBLE -> doubleToLong(in.doubles[i]);
            case BOOLEAN -> in.booleans[i] ? 1 : 0;
            case SYMBOL, VARCHAR -> {
                final String text = in.texts[i];
                if (text == null) {
                    yield Vector.NULL_LONG;
                }
                yield timestamp ? Civil.parse(text) : parseLong(text);
            }
        };
    }

    static double asDouble(final Vector in, final int i) {
        return switch (in.type) {
            case LONG, TIMESTAMP -> in.longs[i] == Vector.NULL_LONG ? Double.NaN : in.longs[i];
            case DOUBLE -> in.doubles[i];
            case BOOLEAN -> in.booleans[i] ? 1 : 0;
            case SYMBOL, VARCHAR -> parseDouble(in.texts[i]);
        };
    }

    static boolean asBoolean(final Vector in, final int i) {
        return switch (in.type) {
            case LONG, TIMESTAMP -> in.longs[i] != 0 && in.longs[i] != Vector.NULL_LONG;
            case DOUBLE -> in.doubles[i] != 0 && !Double.isNaN(in.doubles[i]);
            case BOOLEAN -> in.booleans[i];
            case SYMBOL, VARCHAR -> "true".equalsIgnoreCase(in.texts[i]);
        };
    }

    /** Value {@code i} as text, NULL as null; {@code sink} is scratch space for timestamps. */
    static String asText(final Vector in, final int i, final StringBuilder sink) {
        return switch (in.type) {
            case LONG -> in.longs[i] == Vector.NULL_LONG ? null : Long.toString(in.longs[i]);
            case TIMESTAMP -> {
                if (in.longs[i] == Vector.NULL_LONG) {
                    yield null;
                }
                sink.setLength(0);
                Civil.appendIso(sink, in.longs[i]);
                yield sink.toString();
            }
            case DOUBLE -> Double.isNaN(in.doubles[i]) ? null : Double.toString(in.doubles[i]);
            case BOOLEAN -> in.booleans[i] ? "true" : "false";
            case SYMBOL, VARCHAR -> in.texts[i];
        };
    }

    /** Appends value {@code i} as text; NULL appends nothing. */
    static void appendText(final StringBuilder sink, final Vector in, final int i) {
        switch (in.type) {
            case LONG -> {
                if (in.longs[i] != Vector.NULL_LONG) {
                    sink.append(in.longs[i]);
                }
            }
            case TIMESTAMP -> {
                if (in.longs[i] != Vector.NULL_LONG) {
                    Civil.appendIso(sink, in.longs[i]);
                }
            }
            case DOUBLE -> {
                if (!Double.isNaN(in.doubles[i])) {
                    sink.append(in.doubles[i]);
                }
            }
            case BOOLEAN -> sink.append(in.booleans[i]);
            case SYMBOL, VARCHAR -> {
                if (in.texts[i] != null) {
                    sink.append(in.texts[i]);
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
        for (; i < n; i++) {
            final int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9 || value > (Long.MAX_VALUE - digit) / 10) {
                return Vector.NULL_LONG;
            }
            value = value * 10 + digit;
        }
        return negative ? -value : value;
    }

    private static double parseDouble(final String text) {
        if (text == null || text.isEmpty() || text.charAt(0) <= ' ' || text.charAt(text.length() - 1) <= ' ') {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (final NumberFormatException _) {
            return Double.NaN;
        }
    }
}
