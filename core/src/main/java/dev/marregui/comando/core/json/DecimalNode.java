package dev.marregui.comando.core.json;

import java.math.BigDecimal;

/** A JSON number held as a {@link BigDecimal}, scale kept: only a put or a factory makes one, a parse never does. */
final class DecimalNode extends FloatingNode {

    private static final BigDecimal MIN_INT = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal MAX_INT = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal MIN_LONG = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);
    private final BigDecimal value;

    /** The node for {@code value}, which must not be {@code null}. */
    DecimalNode(final BigDecimal value) {
        this.value = value;
    }

    /** The value. */
    BigDecimal value() {
        return value;
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

    /** Whether {@code other} holds an equal value of the same scale: {@code 1.0} and {@code 1.00} differ. */
    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof DecimalNode number && value.equals(number.value);
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
    boolean isNaN() {
        return false;
    }

    @Override
    boolean hasFraction() {
        return value.signum() != 0 && value.scale() > 0 && value.stripTrailingZeros().scale() > 0;
    }

    @Override
    boolean inIntRange() {
        return value.compareTo(MIN_INT) >= 0 && value.compareTo(MAX_INT) <= 0;
    }

    @Override
    boolean inLongRange() {
        return value.compareTo(MIN_LONG) >= 0 && value.compareTo(MAX_LONG) <= 0;
    }

    @Override
    int truncatedInt() {
        return value.intValue();
    }

    @Override
    long truncatedLong() {
        return value.longValue();
    }
}
