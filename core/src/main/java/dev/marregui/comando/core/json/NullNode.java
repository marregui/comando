package dev.marregui.comando.core.json;

/**
 * JSON null, one shared node. Its coercions give "", 0 and false, and their {@code asX(default)} forms the default;
 * {@link #stringValue()} gives {@code null}; the exact number and boolean accessors throw.
 */
final class NullNode extends JsonNode {

    /** The node. */
    static final NullNode INSTANCE = new NullNode();
    /** The hash code jackson-databind 3.2.4 gives: the ordinal of NULL in its JsonNodeType. */
    private static final int HASH = 4;

    private NullNode() {
    }

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public String asString(final String defaultValue) {
        return defaultValue;
    }

    @Override
    public int asInt() {
        return 0;
    }

    @Override
    public long asLong() {
        return 0L;
    }

    @Override
    public double asDouble() {
        return 0.0;
    }

    @Override
    public boolean asBoolean(final boolean defaultValue) {
        return defaultValue;
    }

    @Override
    public String stringValue() {
        return null;
    }

    @Override
    public NullNode deepCopy() {
        return this;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof NullNode;
    }

    @Override
    public int hashCode() {
        return HASH;
    }

    @Override
    String asStringOrNull() {
        return "";
    }

    @Override
    Boolean asBooleanOrNull() {
        return Boolean.FALSE;
    }
}
