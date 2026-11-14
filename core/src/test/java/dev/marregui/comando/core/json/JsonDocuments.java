package dev.marregui.comando.core.json;

import java.util.Set;

/// A seeded source of JSON text: documents nested at most six deep with every value kind, string escapes, surrogate
/// pairs raw and escaped, numbers at the edges of `int`, `long` and `double`, empty containers and repeated property
/// names, with whitespace between tokens. The generator is xorshift128+ over two seeds, so a run is replayed from the
/// two seeds it printed.
final class JsonDocuments {

    /// The deepest a generated document nests.
    static final int MAX_DEPTH = 6;
    private static final int MAX_CHILDREN = 5;
    private static final int MAX_STRING_PIECES = 10;
    private static final String[] WHITESPACE = {"", "", "", " ", "\n", "\t", "  ", "\r\n", " \n  "};
    private static final String[] ESCAPES = {
        "\\\"", "\\\\", "\\/", "\\b", "\\f", "\\n", "\\r", "\\t", "\\u0000", "\\u001f", "\\u001F", "\\u007f",
        "\\u00e9", "\\u00E9", "\\u4e2d", "\\uD83D\\uDE00", "\\ud83d\\ude00", "\\uD800", "\\uDFFF", "\\uFFFF",
        "\\u0041", "\\u2028",
    };
    private static final Set<String> LONE_SURROGATES = Set.of("\\uD800", "\\uDFFF");
    private static final String[] RAW = {
        "a", "Z", "0", " ", "/", "~", "~1", "\u007f", "\u00e9", "\u4e2d", "\uD83D\uDE00", "\u00a0",
        "\uFEFF", "key", "\u00ff\u0100", "\u07ff\u0800", "\uDBFF\uDFFF", "\u2028",
    };
    private static final String[] NUMBERS = {
        "0", "-0", "1", "-1", "10", "11", "-2", "2147483647", "2147483648", "-2147483648", "-2147483649",
        "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809",
        "999999999", "1000000000", "999999999999999999", "1000000000000000000", "0.0", "-0.0", "1.0", "1.5", "-1.5",
        "0.1", "1e3", "1E3", "1e+3", "1e-3", "1.5e10", "1e400", "-1e400", "1e-400", "4.9e-324",
        "1.7976931348623157e308", "2147483647.0", "2147483648.5", "9223372036854775807.0", "9.223372036854775808e18",
        "12345678901234567890123", "-12345678901234567890123", "0.30000000000000004", "123456789.123456789",
        "1.0e0", "5e-1",
    };
    private final StringBuilder out = new StringBuilder();
    private long s0;
    private long s1;

    /// A source whose sequence `seed0` and `seed1` decide; two zero seeds are not allowed.
    JsonDocuments(final long seed0, final long seed1) {
        if (seed0 == 0L && seed1 == 0L) {
            throw new IllegalArgumentException("seeds must not both be zero");
        }
        this.s0 = seed0;
        this.s1 = seed1;
    }

    /// The next document's text.
    String document() {
        out.setLength(0);
        whitespace();
        value(0);
        whitespace();
        return out.toString();
    }

    /// The next string, raw: what a property name or a string value of a built tree holds.
    String text() {
        final StringBuilder text = new StringBuilder();
        final int pieces = nextInt(MAX_STRING_PIECES);
        for (int i = 0; i < pieces; i++) {
            final int kind = nextInt(4);
            if (kind == 0) {
                text.append((char) nextInt(0x20));
            } else if (kind == 1) {
                text.append((char) (0x20 + nextInt(0x60)));
            } else if (kind == 2) {
                text.append(RAW[nextInt(RAW.length)]);
            } else {
                text.append((char) nextInt(0x10000));
            }
        }
        return text.toString();
    }

    long nextLong() {
        long a = s0;
        final long b = s1;
        s0 = b;
        a ^= a << 23;
        s1 = a ^ b ^ (a >>> 17) ^ (b >>> 26);
        return s1 + b;
    }

    /// A uniform-enough `int` in `[0, bound)`.
    int nextInt(final int bound) {
        return (int) ((nextLong() >>> 33) % bound);
    }

    boolean nextBoolean() {
        return (nextLong() & 1L) == 1L;
    }

    private void value(final int depth) {
        final int kind = nextInt(depth < MAX_DEPTH ? 9 : 6);
        switch (kind) {
            case 0, 1 -> string(false);
            case 2 -> number();
            case 3 -> out.append(nextBoolean() ? "true" : "false");
            case 4 -> out.append("null");
            case 5 -> number();
            case 6, 7 -> object(depth + 1);
            default -> array(depth + 1);
        }
    }

    private void object(final int depth) {
        out.append('{');
        whitespace();
        final int children = nextInt(MAX_CHILDREN + 1);
        String previous = null;
        for (int i = 0; i < children; i++) {
            if (i > 0) {
                out.append(',');
                whitespace();
            }
            if (previous != null && nextInt(6) == 0) {
                out.append(previous);
            } else {
                final int start = out.length();
                string(true);
                previous = out.substring(start);
            }
            whitespace();
            out.append(':');
            whitespace();
            value(depth);
            whitespace();
        }
        out.append('}');
    }

    private void array(final int depth) {
        out.append('[');
        whitespace();
        final int children = nextInt(MAX_CHILDREN + 1);
        for (int i = 0; i < children; i++) {
            if (i > 0) {
                out.append(',');
                whitespace();
            }
            value(depth);
            whitespace();
        }
        out.append(']');
    }

    /// A string; a property name leaves out the escaped lone surrogates, which Jackson refuses there.
    private void string(final boolean name) {
        out.append('"');
        final int pieces = nextInt(MAX_STRING_PIECES);
        for (int i = 0; i < pieces; i++) {
            if (nextInt(3) == 0) {
                final String escape = ESCAPES[nextInt(ESCAPES.length)];
                out.append(name && LONE_SURROGATES.contains(escape) ? "\\u0041" : escape);
            } else {
                out.append(RAW[nextInt(RAW.length)]);
            }
        }
        out.append('"');
    }

    private void number() {
        final int kind = nextInt(6);
        switch (kind) {
            case 0 -> out.append(NUMBERS[nextInt(NUMBERS.length)]);
            case 1 -> out.append(nextInt(41) - 20);
            case 2 -> out.append((int) nextLong());
            case 3 -> out.append(nextLong());
            case 4 -> {
                out.append(nextLong()).append('.').append(Long.toUnsignedString(nextLong() >>> 1));
                if (nextBoolean()) {
                    out.append(nextBoolean() ? 'e' : 'E').append(nextBoolean() ? "-" : "+").append(nextInt(330));
                }
            }
            default -> {
                if (nextBoolean()) {
                    out.append('-');
                }
                out.append(1 + nextInt(9));
                final int digits = 18 + nextInt(30);
                for (int i = 0; i < digits; i++) {
                    out.append(nextInt(10));
                }
            }
        }
    }

    private void whitespace() {
        out.append(WHITESPACE[nextInt(WHITESPACE.length)]);
    }
}
