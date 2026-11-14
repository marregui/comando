package dev.marregui.comando.core.json;

/** A JSON number held as an {@code int}; the values -1 to 10 are shared. */
final class IntNode extends NumericNode {

    private static final int MIN_SHARED = -1;
    private static final int MAX_SHARED = 10;
    private static final IntNode[] SHARED = shared();
    private final int value;

    private IntNode(final int value) {
        this.value = value;
    }

    /** The node for {@code value}. */
    static IntNode valueOf(final int value) {
        return value >= MIN_SHARED && value <= MAX_SHARED ? SHARED[value - MIN_SHARED] : new IntNode(value);
    }

    /** The value. */
    int value() {
        return value;
    }

    @Override
    public boolean isIntegralNumber() {
        return true;
    }

    @Override
    public boolean isInt() {
        return true;
    }

    @Override
    public int asInt() {
        return value;
    }

    @Override
    public int asInt(final int defaultValue) {
        return value;
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

    @Override
    public int intValue() {
        return value;
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
        return true;
    }

    @Override
    public boolean canConvertToLong() {
        return true;
    }

    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof IntNode number && value == number.value;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    String asStringOrNull() {
        return String.valueOf(value);
    }

    @Override
    Boolean asBooleanOrNull() {
        return value != 0;
    }

    private static IntNode[] shared() {
        final IntNode[] nodes = new IntNode[MAX_SHARED - MIN_SHARED + 1];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new IntNode(i + MIN_SHARED);
        }
        return nodes;
    }
}
