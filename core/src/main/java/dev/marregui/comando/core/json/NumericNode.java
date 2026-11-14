package dev.marregui.comando.core.json;

/** A JSON number, held as the narrowest of {@code int}, {@code long} and big integer, or as a floating-point value. */
abstract sealed class NumericNode extends JsonNode permits IntNode, LongNode, BigIntegerNode, FloatingNode {

    NumericNode() {
    }

    @Override
    public final boolean isNumber() {
        return true;
    }

    @Override
    public final NumericNode deepCopy() {
        return this;
    }

    @Override
    public abstract Number numberValue();

    /** The failure of an exact or coercing accessor whose type cannot hold this value. */
    final JsonException outOfRange(final String accessor) {
        return new JsonException("number is out of range", "accessor=" + accessor + ", value=" + asString());
    }
}
