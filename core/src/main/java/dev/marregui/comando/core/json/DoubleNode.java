package dev.marregui.comando.core.json;

/** A JSON number held as a {@code double}, NaN and the infinities included; they are written as strings. */
final class DoubleNode extends FloatingNode {

    private final double value;

    /** The node for {@code value}. */
    DoubleNode(final double value) {
        this.value = value;
    }

    /** The value. */
    double value() {
        return value;
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public double asDouble(final double defaultValue) {
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public Number numberValue() {
        return value;
    }

    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof DoubleNode number && Double.compare(value, number.value) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }

    @Override
    String asStringOrNull() {
        return String.valueOf(value);
    }

    @Override
    boolean isNaN() {
        return !Double.isFinite(value);
    }

    @Override
    boolean hasFraction() {
        return value != Math.rint(value);
    }

    @Override
    boolean inIntRange() {
        return !isNaN() && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    /** As Jackson decides it: {@code Long.MAX_VALUE} widens to 2^63, so 2^63 counts as in range. */
    @Override
    boolean inLongRange() {
        return !isNaN() && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE;
    }

    @Override
    int truncatedInt() {
        return (int) value;
    }

    @Override
    long truncatedLong() {
        return (long) value;
    }
}
