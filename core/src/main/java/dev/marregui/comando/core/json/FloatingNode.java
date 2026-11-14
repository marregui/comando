package dev.marregui.comando.core.json;

/**
 * A JSON number held as a floating-point value: a {@code double} when parsed, a {@code float} or {@link
 * java.math.BigDecimal} when put. The {@code int} and {@code long} accessors truncate in the {@code asX} forms and
 * refuse a fraction in the exact ones; {@code asBoolean} refuses every value.
 */
abstract sealed class FloatingNode extends NumericNode permits DoubleNode, FloatNode, DecimalNode {

    FloatingNode() {
    }

    /** @throws JsonException when the value is not a number or outside the {@code int} range */
    @Override
    public final int asInt() {
        if (!inIntRange()) {
            throw outOfRange("asInt()");
        }
        return truncatedInt();
    }

    @Override
    public final int asInt(final int defaultValue) {
        return inIntRange() ? truncatedInt() : defaultValue;
    }

    /** @throws JsonException when the value is not a number or outside the {@code long} range */
    @Override
    public final long asLong() {
        if (!inLongRange()) {
            throw outOfRange("asLong()");
        }
        return truncatedLong();
    }

    @Override
    public final long asLong(final long defaultValue) {
        return inLongRange() ? truncatedLong() : defaultValue;
    }

    /** @throws JsonException when the value is not a number, outside the {@code int} range or has a fraction */
    @Override
    public final int intValue() {
        if (!inIntRange()) {
            throw outOfRange("intValue()");
        }
        if (hasFraction()) {
            throw new JsonException("number has a fraction", "accessor=intValue(), value=" + asString());
        }
        return truncatedInt();
    }

    /** @throws JsonException when the value is not a number, outside the {@code long} range or has a fraction */
    @Override
    public final long longValue() {
        if (!inLongRange()) {
            throw outOfRange("longValue()");
        }
        if (hasFraction()) {
            throw new JsonException("number has a fraction", "accessor=longValue(), value=" + asString());
        }
        return truncatedLong();
    }

    @Override
    public final boolean canConvertToInt() {
        return !isNaN() && !hasFraction() && inIntRange();
    }

    @Override
    public final boolean canConvertToLong() {
        return !isNaN() && !hasFraction() && inLongRange();
    }

    /** Whether the value is NaN or infinite. */
    abstract boolean isNaN();

    /** Whether the value is not a whole number, as Jackson 3.2.4 decides it for this kind. */
    abstract boolean hasFraction();

    /** Whether the value is a number within the {@code int} range. */
    abstract boolean inIntRange();

    /** Whether the value is a number within the {@code long} range. */
    abstract boolean inLongRange();

    /** The value cast to {@code int}. */
    abstract int truncatedInt();

    /** The value cast to {@code long}. */
    abstract long truncatedLong();
}
