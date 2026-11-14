package dev.marregui.comando.core.json;

/**
 * Where nothing is: the answer of {@code path} and {@code at} for an absent property, index or pointer, and of reading
 * an empty document. Every {@code path} on it gives it again. Its coercions give "", 0 and false, and their
 * {@code asX(default)} forms the default; it is written as JSON null, but {@link #toString()} gives "".
 */
final class MissingNode extends JsonNode {

    /** The node. */
    static final MissingNode INSTANCE = new MissingNode();
    /** The hash code jackson-databind 3.2.4 gives: the ordinal of MISSING in its JsonNodeType. */
    private static final int HASH = 3;

    private MissingNode() {
    }

    @Override
    public JsonNode path(final String name) {
        return this;
    }

    @Override
    public JsonNode path(final int index) {
        return this;
    }

    @Override
    public boolean isMissingNode() {
        return true;
    }

    @Override
    public String asString() {
        return "";
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
    public boolean asBoolean() {
        return false;
    }

    @Override
    public MissingNode deepCopy() {
        return this;
    }

    @Override
    public boolean equals(final Object other) {
        return other == this;
    }

    @Override
    public int hashCode() {
        return HASH;
    }

    @Override
    public String toString() {
        return "";
    }

    @Override
    public String toPrettyString() {
        return "";
    }
}
