package dev.marregui.comando.core.json;

/** A JSON boolean: one shared node per value. */
final class BooleanNode extends JsonNode {

    /** {@code true}. */
    static final BooleanNode TRUE = new BooleanNode(true);
    /** {@code false}. */
    static final BooleanNode FALSE = new BooleanNode(false);
    /** The hash codes jackson-databind 3.2.4's BooleanNode gives. */
    private static final int TRUE_HASH = 3;
    private static final int FALSE_HASH = 1;
    private final boolean value;

    private BooleanNode(final boolean value) {
        this.value = value;
    }

    /** The node for {@code value}. */
    static BooleanNode valueOf(final boolean value) {
        return value ? TRUE : FALSE;
    }

    /** The value. */
    boolean value() {
        return value;
    }

    @Override
    public boolean isBoolean() {
        return true;
    }

    @Override
    public boolean booleanValue() {
        return value;
    }

    @Override
    public BooleanNode deepCopy() {
        return this;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof BooleanNode bool && value == bool.value;
    }

    @Override
    public int hashCode() {
        return value ? TRUE_HASH : FALSE_HASH;
    }

    @Override
    String asStringOrNull() {
        return value ? "true" : "false";
    }

    @Override
    Boolean asBooleanOrNull() {
        return value;
    }
}
