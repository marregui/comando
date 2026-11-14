package dev.marregui.comando.core.json;

import java.math.BigInteger;

/** A JSON number held as a {@link BigInteger}: one beyond the {@code long} range when parsed, any value when put. */
final class BigIntegerNode extends NumericNode {

    private static final BigInteger MIN_INT = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private final BigInteger value;

    /** The node for {@code value}, which must not be {@code null}. */
    BigIntegerNode(final BigInteger value) {
        this.value = value;
    }

    /** The value. */
    BigInteger value() {
        return value;
    }

    @Override
    public boolean isIntegralNumber() {
        return true;
    }

    /** @throws JsonException when the value is outside the {@code int} range */
    @Override
    public int asInt() {
        return intValue();
    }

    @Override
    public int asInt(final int defaultValue) {
        return fitsInt() ? value.intValue() : defaultValue;
    }

    /** @throws JsonException when the value is outside the {@code long} range */
    @Override
    public long asLong() {
        return longValue();
    }

    @Override
    public long asLong(final long defaultValue) {
        return fitsLong() ? value.longValue() : defaultValue;
    }

    /** @throws JsonException when the value is beyond a double's range */
    @Override
    public double asDouble() {
        return doubleValue();
    }

    @Override
    public double asDouble(final double defaultValue) {
        final double converted = value.doubleValue();
        return Double.isFinite(converted) ? converted : defaultValue;
    }

    /** @throws JsonException when the value is outside the {@code int} range */
    @Override
    public int intValue() {
        if (!fitsInt()) {
            throw outOfRange("intValue()");
        }
        return value.intValue();
    }

    /** @throws JsonException when the value is outside the {@code long} range */
    @Override
    public long longValue() {
        if (!fitsLong()) {
            throw outOfRange("longValue()");
        }
        return value.longValue();
    }

    /** @throws JsonException when the value is beyond a double's range */
    @Override
    public double doubleValue() {
        final double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            throw outOfRange("doubleValue()");
        }
        return converted;
    }

    @Override
    public Number numberValue() {
        return value;
    }

    @Override
    public boolean canConvertToInt() {
        return fitsInt();
    }

    @Override
    public boolean canConvertToLong() {
        return fitsLong();
    }

    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof BigIntegerNode number && value.equals(number.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    String asStringOrNull() {
        return value.toString();
    }

    @Override
    Boolean asBooleanOrNull() {
        return value.signum() != 0;
    }

    private boolean fitsInt() {
        return value.compareTo(MIN_INT) >= 0 && value.compareTo(MAX_INT) <= 0;
    }

    private boolean fitsLong() {
        return value.compareTo(MIN_LONG) >= 0 && value.compareTo(MAX_LONG) <= 0;
    }
}
