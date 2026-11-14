package dev.marregui.comando.core.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A node of a JSON tree: an object, an array, or a string, number, boolean, null or missing value. Names and behaviour
 * are those of Jackson 3.2.4's tree model, so code written against it reads the same: {@code get} gives {@code null}
 * where {@code path} gives the missing node, the {@code asX()} coercions convert what they can and throw
 * {@link JsonException} otherwise, their {@code asX(default)} forms give the default instead, and the exact accessors
 * ({@code intValue()} and the rest) throw unless the node holds that kind of value. Value nodes are immutable;
 * {@link ObjectNode} and {@link ArrayNode} are not, and are not safe for concurrent writes.
 */
public abstract sealed class JsonNode implements Iterable<JsonNode>
        permits ObjectNode, ArrayNode, StringNode, NumericNode, BooleanNode, NullNode, MissingNode {

    /** A JSON Pointer segment that is not an array index. */
    private static final int NOT_AN_INDEX = -1;
    /** The digits of {@link Integer#MAX_VALUE}: a longer segment is not an index, as in jackson-core's JsonPointer. */
    private static final int MAX_INDEX_DIGITS = 10;

    JsonNode() {
    }

    /** The value of property {@code name}, or {@code null} when this is not an object or has no such property. */
    public JsonNode get(final String name) {
        return null;
    }

    /** The element at {@code index}, or {@code null} when this is not an array or the index is out of range. */
    public JsonNode get(final int index) {
        return null;
    }

    /** The value of property {@code name}, or the missing node where {@link #get(String)} gives {@code null}. */
    public JsonNode path(final String name) {
        return MissingNode.INSTANCE;
    }

    /** The element at {@code index}, or the missing node where {@link #get(int)} gives {@code null}. */
    public JsonNode path(final int index) {
        return MissingNode.INSTANCE;
    }

    /**
     * The node a JSON Pointer (RFC 6901) names, or the missing node when there is none. An empty or {@code null}
     * pointer names this node.
     *
     * @throws IllegalArgumentException when a non-empty pointer does not start with {@code /}
     */
    public final JsonNode at(final String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return this;
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("JSON Pointer must start with a slash [pointer=" + pointer + ']');
        }
        JsonNode node = this;
        int from = 1;
        while (true) {
            final int slash = pointer.indexOf('/', from);
            final int to = slash < 0 ? pointer.length() : slash;
            final String segment = unescape(pointer, from, to);
            node = step(node, segment);
            if (node == null) {
                return MissingNode.INSTANCE;
            }
            if (slash < 0) {
                return node;
            }
            from = slash + 1;
        }
    }

    /** Whether this is an object with property {@code name}, its value JSON null included. */
    public boolean has(final String name) {
        return get(name) != null;
    }

    /** Whether this is an object with property {@code name} whose value is not JSON null. */
    public boolean hasNonNull(final String name) {
        final JsonNode value = get(name);
        return value != null && !value.isNull();
    }

    /**
     * The value of property {@code name}.
     *
     * @throws JsonException when this is not an object or has no such property
     */
    public JsonNode required(final String name) {
        throw new JsonException("node has no properties", "property=" + name + ", node=" + kind());
    }

    /** Whether this is the missing node, the answer of {@code path} and {@code at} where nothing is. */
    public boolean isMissingNode() {
        return false;
    }

    /** Whether this is JSON null. */
    public boolean isNull() {
        return false;
    }

    /** Whether this is an object. */
    public boolean isObject() {
        return false;
    }

    /** Whether this is an array. */
    public boolean isArray() {
        return false;
    }

    /** Whether this is an object or an array. */
    public boolean isContainer() {
        return false;
    }

    /** Whether this is a string. */
    public boolean isString() {
        return false;
    }

    /** Whether this is a number. */
    public boolean isNumber() {
        return false;
    }

    /** Whether this is an integral number: an int, long or big integer node. */
    public boolean isIntegralNumber() {
        return false;
    }

    /** Whether this is a number held as an {@code int}. */
    public boolean isInt() {
        return false;
    }

    /** Whether this is a boolean. */
    public boolean isBoolean() {
        return false;
    }

    /** Whether this is a value: not an object, an array or the missing node. JSON null is a value. */
    public boolean isValueNode() {
        return !isContainer() && !isMissingNode();
    }

    /** Whether {@link #size()} is 0: true for every value node, strings included. */
    public boolean isEmpty() {
        return size() == 0;
    }

    /** The number of properties of an object or elements of an array; 0 for every other node. */
    public int size() {
        return 0;
    }

    /**
     * The value as text: a string's own text, a number's or boolean's JSON form, "" for JSON null and the missing node.
     *
     * @throws JsonException for an object or an array
     */
    public String asString() {
        final String text = asStringOrNull();
        if (text == null) {
            throw coercionFailed("asString()");
        }
        return text;
    }

    /** As {@link #asString()}, with {@code defaultValue} for JSON null and where it throws. */
    public String asString(final String defaultValue) {
        final String text = asStringOrNull();
        return text == null ? defaultValue : text;
    }

    /**
     * The value as an {@code int}: a number truncated, a string parsed; 0 for JSON null and the missing node.
     *
     * @throws JsonException for a number out of range, a string that is not an {@code int}, and every other node
     */
    public int asInt() {
        throw coercionFailed("asInt()");
    }

    /** As {@link #asInt()}, with {@code defaultValue} for JSON null and where it throws. */
    public int asInt(final int defaultValue) {
        return defaultValue;
    }

    /**
     * The value as a {@code long}: a number truncated, a string parsed; 0 for JSON null and the missing node.
     *
     * @throws JsonException for a number out of range, a string that is not a {@code long}, and every other node
     */
    public long asLong() {
        throw coercionFailed("asLong()");
    }

    /** As {@link #asLong()}, with {@code defaultValue} for JSON null and where it throws. */
    public long asLong(final long defaultValue) {
        return defaultValue;
    }

    /**
     * The value as a {@code double}: a number converted, a string parsed; 0 for JSON null and the missing node.
     *
     * @throws JsonException for a number beyond a double's range, a string that is not a number, and every other node
     */
    public double asDouble() {
        throw coercionFailed("asDouble()");
    }

    /** As {@link #asDouble()}, with {@code defaultValue} for JSON null and where it throws. */
    public double asDouble(final double defaultValue) {
        return defaultValue;
    }

    /**
     * The value as a {@code boolean}: a boolean's value, an integral number other than 0, a string "true" or "false";
     * false for JSON null and the missing node.
     *
     * @throws JsonException for every other node, floating-point numbers included
     */
    public boolean asBoolean() {
        final Boolean value = asBooleanOrNull();
        if (value == null) {
            throw coercionFailed("asBoolean()");
        }
        return value;
    }

    /** As {@link #asBoolean()}, with {@code defaultValue} for JSON null and where it throws. */
    public boolean asBoolean(final boolean defaultValue) {
        final Boolean value = asBooleanOrNull();
        return value == null ? defaultValue : value;
    }

    /**
     * The number as an {@code int}, exactly.
     *
     * @throws JsonException when this is not a number, or is one with a fraction or outside the {@code int} range
     */
    public int intValue() {
        throw notNumeric("intValue()");
    }

    /**
     * The number as a {@code long}, exactly.
     *
     * @throws JsonException when this is not a number, or is one with a fraction or outside the {@code long} range
     */
    public long longValue() {
        throw notNumeric("longValue()");
    }

    /**
     * The number as a {@code double}.
     *
     * @throws JsonException when this is not a number, or is one beyond a double's range
     */
    public double doubleValue() {
        throw notNumeric("doubleValue()");
    }

    /**
     * The number as the {@link Number} it is held as: {@code Integer}, {@code Long}, {@code BigInteger},
     * {@code Double}, {@code Float} or {@code BigDecimal}.
     *
     * @throws JsonException when this is not a number
     */
    public Number numberValue() {
        throw notNumeric("numberValue()");
    }

    /**
     * The boolean's value.
     *
     * @throws JsonException when this is not a boolean
     */
    public boolean booleanValue() {
        throw new JsonException("node is not a boolean", "accessor=booleanValue(), node=" + kind());
    }

    /**
     * The string's text; {@code null} for JSON null.
     *
     * @throws JsonException when this is not a string or JSON null
     */
    public String stringValue() {
        throw new JsonException("node is not a string", "accessor=stringValue(), node=" + kind());
    }

    /** Whether the value converts to an {@code int} without loss: an integral value within its range. */
    public boolean canConvertToInt() {
        return false;
    }

    /** Whether the value converts to a {@code long} without loss: an integral value within its range. */
    public boolean canConvertToLong() {
        return false;
    }

    /** An object's properties in order, a live view; empty for every other node. */
    public Set<Map.Entry<String, JsonNode>> properties() {
        return Set.of();
    }

    /** An object's property names in order, a live view; empty for every other node. */
    public Collection<String> propertyNames() {
        return List.of();
    }

    /** An object's values or an array's elements in order, a live view; empty for every other node. */
    public Collection<JsonNode> values() {
        return List.of();
    }

    /** The values {@link #values()} gives. */
    @Override
    public final Iterator<JsonNode> iterator() {
        return values().iterator();
    }

    /** The values {@link #values()} gives, as a stream. */
    public Stream<JsonNode> valueStream() {
        return values().stream();
    }

    /** The properties {@link #properties()} gives, as a stream. */
    public Stream<Map.Entry<String, JsonNode>> propertyStream() {
        return properties().stream();
    }

    /**
     * The text of every property {@code name} in this tree, as {@link #asString()} gives it, in document order; the
     * value of a matching property is not searched further.
     */
    public final List<String> findValuesAsString(final String name) {
        final List<String> found = new ArrayList<>();
        collectValuesAsString(name, found);
        return found;
    }

    /** A copy that shares nothing mutable with this node; a value node is its own copy. */
    public abstract JsonNode deepCopy();

    /** The node as compact JSON, as {@link Json#write(JsonNode)} gives it; "" for the missing node. */
    @Override
    public String toString() {
        return Json.write(this);
    }

    /** The node as indented JSON, as {@link Json#pretty(JsonNode)} gives it; "" for the missing node. */
    public String toPrettyString() {
        return Json.pretty(this);
    }

    /** The text {@link #asString()} gives, or {@code null} where it throws. */
    String asStringOrNull() {
        return null;
    }

    /** The value {@link #asBoolean()} gives, or {@code null} where it throws. */
    Boolean asBooleanOrNull() {
        return null;
    }

    /** Adds the text of every property {@code name} below this node to {@code found}. */
    void collectValuesAsString(final String name, final List<String> found) {
    }

    /** What kind of node this is, for messages. */
    final String kind() {
        return getClass().getSimpleName();
    }

    /** The failure of a coercion this node cannot make. */
    final JsonException coercionFailed(final String accessor) {
        return new JsonException("cannot coerce node", "accessor=" + accessor + ", node=" + kind());
    }

    /** The failure of a number accessor on a node that is not a number. */
    final JsonException notNumeric(final String accessor) {
        return new JsonException("node is not a number", "accessor=" + accessor + ", node=" + kind());
    }

    /** The node one pointer segment below {@code node}, or {@code null}. */
    private static JsonNode step(final JsonNode node, final String segment) {
        return switch (node) {
            case ObjectNode object -> object.get(segment);
            case ArrayNode array -> {
                final int index = index(segment);
                yield index == NOT_AN_INDEX ? null : array.get(index);
            }
            case MissingNode missing -> missing;
            case StringNode _, NumericNode _, BooleanNode _, NullNode _ -> null;
        };
    }

    /** {@code pointer[from, to)} with {@code ~1} read as {@code /} and {@code ~0} as {@code ~}; other tildes kept. */
    private static String unescape(final String pointer, final int from, final int to) {
        final int tilde = pointer.indexOf('~', from);
        if (tilde < 0 || tilde >= to) {
            return pointer.substring(from, to);
        }
        final StringBuilder segment = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            final char c = pointer.charAt(i);
            if (c == '~' && i + 1 < to && (pointer.charAt(i + 1) == '0' || pointer.charAt(i + 1) == '1')) {
                segment.append(pointer.charAt(++i) == '0' ? '~' : '/');
            } else {
                segment.append(c);
            }
        }
        return segment.toString();
    }

    /** The array index a segment names: digits without a leading zero, within {@code int}; else not an index. */
    private static int index(final String segment) {
        final int length = segment.length();
        if (length == 0 || length > MAX_INDEX_DIGITS || segment.charAt(0) == '0' && length > 1) {
            return NOT_AN_INDEX;
        }
        long value = 0L;
        for (int i = 0; i < length; i++) {
            final char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                return NOT_AN_INDEX;
            }
            value = value * 10L + (c - '0');
        }
        return value > Integer.MAX_VALUE ? NOT_AN_INDEX : (int) value;
    }
}
