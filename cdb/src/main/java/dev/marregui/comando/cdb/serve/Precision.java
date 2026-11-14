package dev.marregui.comando.cdb.serve;

/** The unit of a line's timestamp, named by {@code /write}'s {@code precision} parameter; nanoseconds by default. */
enum Precision {
    NANOS,
    MICROS,
    MILLIS,
    SECONDS;

    /** The precision {@code parameter} names ({@code n}, {@code u}, {@code ms}, {@code s}), nanoseconds when null. */
    static Precision of(final String parameter) {
        if (parameter == null) {
            return NANOS;
        }
        return switch (parameter) {
            case "n" -> NANOS;
            case "u" -> MICROS;
            case "ms" -> MILLIS;
            case "s" -> SECONDS;
            default -> throw new IllegalArgumentException("unsupported precision [precision=" + parameter + ']');
        };
    }

    /** {@code value} in microseconds; nanoseconds round down. Throws {@link ArithmeticException} on overflow. */
    long toMicros(final long value) {
        return switch (this) {
            case NANOS -> Math.floorDiv(value, 1_000L);
            case MICROS -> value;
            case MILLIS -> Math.multiplyExact(value, 1_000L);
            case SECONDS -> Math.multiplyExact(value, 1_000_000L);
        };
    }
}
