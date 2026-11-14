package dev.marregui.comando.core.json;

/** A JSON number held as a {@code float}: only a put or a factory makes one, a parse never does. */
final class FloatNode extends FloatingNode {

    private final float value;

    /** The node for {@code value}. */
    FloatNode(final float value) {
        this.value = value;
    }

    /** The value. */
    float value() {
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
        return other == this || other instanceof FloatNode number && Float.compare(value, number.value) == 0;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(value);
    }

    @Override
    String asStringOrNull() {
        return String.valueOf(value);
    }

    @Override
    boolean isNaN() {
        return !Float.isFinite(value);
    }

    /** As Jackson decides it, against {@code Math.round}: a value beyond the {@code int} range counts as fractional. */
    @Override
    boolean hasFraction() {
        return value != Math.round(value);
    }

    /** As Jackson decides it, in {@code float}: {@code Integer.MAX_VALUE} widens to 2^31, which counts as in range. */
    @Override
    boolean inIntRange() {
        return !isNaN() && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

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
