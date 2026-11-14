package dev.marregui.comando.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A JSON object: properties in the order they were first put, a property put again keeping its place. A {@code null}
 * value given to any method is stored as JSON null; a {@code null} name is refused with a
 * {@link NullPointerException}. The views {@link #properties()}, {@link #propertyNames()} and {@link #values()} are
 * live.
 */
public final class ObjectNode extends JsonNode {

    private final LinkedHashMap<String, JsonNode> children = new LinkedHashMap<>();

    /** An empty object; {@link Json#object()} makes one outside this package. */
    ObjectNode() {
    }

    @Override
    public JsonNode get(final String name) {
        return children.get(name);
    }

    @Override
    public JsonNode path(final String name) {
        final JsonNode value = children.get(name);
        return value == null ? MissingNode.INSTANCE : value;
    }

    /** @throws JsonException when there is no property {@code name} */
    @Override
    public JsonNode required(final String name) {
        final JsonNode value = children.get(name);
        if (value == null) {
            throw new JsonException("object has no such property", "property=" + name);
        }
        return value;
    }

    @Override
    public boolean isObject() {
        return true;
    }

    @Override
    public boolean isContainer() {
        return true;
    }

    @Override
    public boolean isEmpty() {
        return children.isEmpty();
    }

    @Override
    public int size() {
        return children.size();
    }

    @Override
    public Set<Map.Entry<String, JsonNode>> properties() {
        return children.entrySet();
    }

    @Override
    public Collection<String> propertyNames() {
        return children.keySet();
    }

    @Override
    public Collection<JsonNode> values() {
        return children.values();
    }

    @Override
    public Stream<JsonNode> valueStream() {
        return children.values().stream();
    }

    @Override
    public Stream<Map.Entry<String, JsonNode>> propertyStream() {
        return children.entrySet().stream();
    }

    /** Puts {@code value} under {@code name}, a string or JSON null; returns this object. */
    public ObjectNode put(final String name, final String value) {
        return set(name, value == null ? NullNode.INSTANCE : StringNode.valueOf(value));
    }

    /** Puts {@code value} under {@code name}; returns this object. */
    public ObjectNode put(final String name, final int value) {
        return set(name, IntNode.valueOf(value));
    }

    /** Puts {@code value} under {@code name}, held as a {@code long} whatever its size; returns this object. */
    public ObjectNode put(final String name, final long value) {
        return set(name, new LongNode(value));
    }

    /** Puts {@code value} under {@code name}; returns this object. */
    public ObjectNode put(final String name, final double value) {
        return set(name, new DoubleNode(value));
    }

    /** Puts {@code value} under {@code name}; returns this object. */
    public ObjectNode put(final String name, final float value) {
        return set(name, new FloatNode(value));
    }

    /** Puts {@code value} under {@code name}; returns this object. */
    public ObjectNode put(final String name, final boolean value) {
        return set(name, BooleanNode.valueOf(value));
    }

    /** Puts {@code value} under {@code name}, scale kept, or JSON null; returns this object. */
    public ObjectNode put(final String name, final BigDecimal value) {
        return set(name, value == null ? NullNode.INSTANCE : new DecimalNode(value));
    }

    /** Puts {@code value} under {@code name}, held as a big integer whatever its size, or JSON null. */
    public ObjectNode put(final String name, final BigInteger value) {
        return set(name, value == null ? NullNode.INSTANCE : new BigIntegerNode(value));
    }

    /** Puts {@code value} under {@code name}, or JSON null; returns this object. */
    public ObjectNode put(final String name, final Integer value) {
        return set(name, value == null ? NullNode.INSTANCE : IntNode.valueOf(value));
    }

    /** Puts {@code value} under {@code name}, or JSON null; returns this object. */
    public ObjectNode put(final String name, final Long value) {
        return set(name, value == null ? NullNode.INSTANCE : new LongNode(value));
    }

    /** Puts {@code value} under {@code name}, or JSON null; returns this object. */
    public ObjectNode put(final String name, final Double value) {
        return set(name, value == null ? NullNode.INSTANCE : new DoubleNode(value));
    }

    /** Puts {@code value} under {@code name}, or JSON null; returns this object. */
    public ObjectNode put(final String name, final Boolean value) {
        return set(name, value == null ? NullNode.INSTANCE : BooleanNode.valueOf(value));
    }

    /** Puts JSON null under {@code name}; returns this object. */
    public ObjectNode putNull(final String name) {
        return set(name, NullNode.INSTANCE);
    }

    /** Puts a new empty object under {@code name} and returns it. */
    public ObjectNode putObject(final String name) {
        final ObjectNode child = new ObjectNode();
        set(name, child);
        return child;
    }

    /** Puts a new empty array under {@code name} and returns it. */
    public ArrayNode putArray(final String name) {
        final ArrayNode child = new ArrayNode();
        set(name, child);
        return child;
    }

    /**
     * The array under {@code name}, put there first when the property is absent or JSON null.
     *
     * @throws JsonException            when the property holds something other than an array or JSON null
     * @throws IllegalArgumentException when {@code name} starts with {@code /}: a JSON Pointer, which this does not
     *                                  resolve
     */
    public ArrayNode withArray(final String name) {
        if (name.startsWith("/")) {
            throw new IllegalArgumentException("withArray takes a property name, not a JSON Pointer [name=" + name
                    + ']');
        }
        final JsonNode child = children.get(name);
        if (child == null || child.isNull()) {
            return putArray(name);
        }
        if (child instanceof ArrayNode array) {
            return array;
        }
        throw new JsonException("cannot replace property with an array", "property=" + name + ", node="
                + child.kind());
    }

    /** Puts {@code value} under {@code name}, JSON null for {@code null}; returns this object. */
    public ObjectNode set(final String name, final JsonNode value) {
        children.put(Objects.requireNonNull(name), value == null ? NullNode.INSTANCE : value);
        return this;
    }

    /** Puts every entry of {@code properties}, in its order, JSON null for a {@code null} value; returns this. */
    public ObjectNode setAll(final Map<String, ? extends JsonNode> properties) {
        for (final Map.Entry<String, ? extends JsonNode> entry : properties.entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /** Puts every property of {@code other}, in its order, sharing its values; returns this object. */
    public ObjectNode setAll(final ObjectNode other) {
        children.putAll(other.children);
        return this;
    }

    /** Puts {@code value} under {@code name}, JSON null for {@code null}, and returns the value it replaced or null. */
    public JsonNode replace(final String name, final JsonNode value) {
        return children.put(Objects.requireNonNull(name), value == null ? NullNode.INSTANCE : value);
    }

    /** Removes property {@code name} and returns its value, or {@code null} when there was none. */
    public JsonNode remove(final String name) {
        return children.remove(name);
    }

    /** Removes every property named in {@code names}; returns this object. */
    public ObjectNode remove(final Collection<String> names) {
        children.keySet().removeAll(names);
        return this;
    }

    /** Removes every property; returns this object. */
    public ObjectNode removeAll() {
        children.clear();
        return this;
    }

    /** Removes every property not named in {@code names}; returns this object. */
    public ObjectNode retain(final Collection<String> names) {
        children.keySet().retainAll(names);
        return this;
    }

    /** Removes every property not named in {@code names}; returns this object. */
    public ObjectNode retain(final String... names) {
        return retain(List.of(names));
    }

    @Override
    public ObjectNode deepCopy() {
        final ObjectNode copy = new ObjectNode();
        for (final Map.Entry<String, JsonNode> entry : children.entrySet()) {
            copy.children.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }

    /** Whether {@code other} is an object with equal properties, in any order. */
    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof ObjectNode object && children.equals(object.children);
    }

    @Override
    public int hashCode() {
        return children.hashCode();
    }

    @Override
    void collectValuesAsString(final String name, final List<String> found) {
        for (final Map.Entry<String, JsonNode> entry : children.entrySet()) {
            if (name.equals(entry.getKey())) {
                found.add(entry.getValue().asString());
            } else {
                entry.getValue().collectValuesAsString(name, found);
            }
        }
    }
}
