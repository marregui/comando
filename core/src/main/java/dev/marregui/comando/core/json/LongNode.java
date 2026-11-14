package dev.marregui.comando.core.json;

/** A JSON number held as a {@code long}: one beyond the {@code int} range when parsed, any value when put. */
final class LongNode extends NumericNode {

    private final long value;

    /** The node for {@code value}. */
    LongNode(final long value) {
        this.value = value;
    }

    /** The value. */
    long value() {
        return value;
    }

    @Override
    public boolean isIntegralNumber() {
        return true;
    }

    /** @throws JsonException when the value is outside the {@code int} range */
    @Override
    public int asInt() {
        if (!fitsInt()) {
            throw outOfRange("asInt()");
        }
        return (int) value;
    }

    @Override
    public int asInt(final int defaultValue) {
        return fitsInt() ? (int) value : defaultValue;
    }

    @Override
    public long asLong() {
        return value;
    }

    @Override
    public long asLong(final long defaultValue) {
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

    /** @throws JsonException when the value is outside the {@code int} range */
    @Override
    public int intValue() {
        if (!fitsInt()) {
            throw outOfRange("intValue()");
        }
        return (int) value;
    }

    @Override
    public long longValue() {
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
    public boolean canConvertToInt() {
        return fitsInt();
    }

    @Override
    public boolean canConvertToLong() {
        return true;
    }

    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof LongNode number && value == number.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    String asStringOrNull() {
        return String.valueOf(value);
    }

    @Override
    Boolean asBooleanOrNull() {
        return value != 0L;
    }

    private boolean fitsInt() {
        return value == (int) value;
    }
}
