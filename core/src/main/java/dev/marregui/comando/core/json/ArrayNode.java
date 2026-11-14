package dev.marregui.comando.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * A JSON array. A {@code null} element given to any method is stored as JSON null. The view {@link #values()} is live.
 */
public final class ArrayNode extends JsonNode {

    private final ArrayList<JsonNode> children = new ArrayList<>();

    /** An empty array; {@link Json#array()} makes one outside this package. */
    ArrayNode() {
    }

    @Override
    public JsonNode get(final int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }

    @Override
    public JsonNode path(final int index) {
        return index >= 0 && index < children.size() ? children.get(index) : MissingNode.INSTANCE;
    }

    @Override
    public boolean isArray() {
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
    public Collection<JsonNode> values() {
        return children;
    }

    @Override
    public Stream<JsonNode> valueStream() {
        return children.stream();
    }

    /** Appends {@code value}, JSON null for {@code null}; returns this array. */
    public ArrayNode add(final JsonNode value) {
        children.add(value == null ? NullNode.INSTANCE : value);
        return this;
    }

    /** Appends {@code value}, a string or JSON null; returns this array. */
    public ArrayNode add(final String value) {
        return add(value == null ? NullNode.INSTANCE : StringNode.valueOf(value));
    }

    /** Appends {@code value}; returns this array. */
    public ArrayNode add(final int value) {
        return add(IntNode.valueOf(value));
    }

    /** Appends {@code value}, held as a {@code long} whatever its size; returns this array. */
    public ArrayNode add(final long value) {
        return add(new LongNode(value));
    }

    /** Appends {@code value}; returns this array. */
    public ArrayNode add(final double value) {
        return add(new DoubleNode(value));
    }

    /** Appends {@code value}; returns this array. */
    public ArrayNode add(final float value) {
        return add(new FloatNode(value));
    }

    /** Appends {@code value}; returns this array. */
    public ArrayNode add(final boolean value) {
        return add(BooleanNode.valueOf(value));
    }

    /** Appends {@code value}, scale kept, or JSON null; returns this array. */
    public ArrayNode add(final BigDecimal value) {
        return add(value == null ? NullNode.INSTANCE : new DecimalNode(value));
    }

    /** Appends {@code value}, held as a big integer whatever its size, or JSON null; returns this array. */
    public ArrayNode add(final BigInteger value) {
        return add(value == null ? NullNode.INSTANCE : new BigIntegerNode(value));
    }

    /** Appends {@code value}, or JSON null; returns this array. */
    public ArrayNode add(final Integer value) {
        return add(value == null ? NullNode.INSTANCE : IntNode.valueOf(value));
    }

    /** Appends {@code value}, or JSON null; returns this array. */
    public ArrayNode add(final Long value) {
        return add(value == null ? NullNode.INSTANCE : new LongNode(value));
    }

    /** Appends {@code value}, or JSON null; returns this array. */
    public ArrayNode add(final Double value) {
        return add(value == null ? NullNode.INSTANCE : new DoubleNode(value));
    }

    /** Appends {@code value}, or JSON null; returns this array. */
    public ArrayNode add(final Boolean value) {
        return add(value == null ? NullNode.INSTANCE : BooleanNode.valueOf(value));
    }

    /** Appends JSON null; returns this array. */
    public ArrayNode addNull() {
        return add(NullNode.INSTANCE);
    }

    /** Appends a new empty object and returns it. */
    public ObjectNode addObject() {
        final ObjectNode child = new ObjectNode();
        add(child);
        return child;
    }

    /** Appends a new empty array and returns it. */
    public ArrayNode addArray() {
        final ArrayNode child = new ArrayNode();
        add(child);
        return child;
    }

    /** Appends every element of {@code other}, sharing them; returns this array. */
    public ArrayNode addAll(final ArrayNode other) {
        children.addAll(other.children);
        return this;
    }

    /** Appends every node of {@code nodes}, JSON null for a {@code null}; returns this array. */
    public ArrayNode addAll(final Collection<? extends JsonNode> nodes) {
        for (final JsonNode node : nodes) {
            add(node);
        }
        return this;
    }

    /**
     * Replaces the element at {@code index} with {@code value}, JSON null for {@code null}; returns this array.
     *
     * @throws JsonException when {@code index} is outside {@code [0, size())}
     */
    public ArrayNode set(final int index, final JsonNode value) {
        if (index < 0 || index >= children.size()) {
            throw new JsonException("array index out of range", "index=" + index + ", size=" + children.size());
        }
        children.set(index, value == null ? NullNode.INSTANCE : value);
        return this;
    }

    /**
     * Inserts {@code value}, JSON null for {@code null}, at {@code index}: at the start for a negative index, at the
     * end for one past the last; returns this array.
     */
    public ArrayNode insert(final int index, final JsonNode value) {
        final JsonNode element = value == null ? NullNode.INSTANCE : value;
        if (index < 0) {
            children.addFirst(element);
        } else if (index >= children.size()) {
            children.addLast(element);
        } else {
            children.add(index, element);
        }
        return this;
    }

    /** Removes the element at {@code index} and returns it, or {@code null} when the index is out of range. */
    public JsonNode remove(final int index) {
        return index >= 0 && index < children.size() ? children.remove(index) : null;
    }

    /** Removes every element; returns this array. */
    public ArrayNode removeAll() {
        children.clear();
        return this;
    }

    @Override
    public ArrayNode deepCopy() {
        final ArrayNode copy = new ArrayNode();
        for (final JsonNode child : children) {
            copy.children.add(child.deepCopy());
        }
        return copy;
    }

    /** Whether {@code other} is an array with equal elements in the same order. */
    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof ArrayNode array && children.equals(array.children);
    }

    @Override
    public int hashCode() {
        return children.hashCode();
    }

    @Override
    void collectValuesAsString(final String name, final List<String> found) {
        for (final JsonNode child : children) {
            child.collectValuesAsString(name, found);
        }
    }
}
