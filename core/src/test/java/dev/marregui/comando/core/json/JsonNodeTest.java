package dev.marregui.comando.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The node kinds and their accessors, one kind or accessor family per test: navigation, type tests, coercions with
/// and without defaults, exact accessors, views, equality, copies and printing.
class JsonNodeTest {

    private static final String DOCUMENT =
            "{\"a\":{\"b\":[10,{\"c\":\"x\"}]},\"n\":null,\"a/b\":1,\"m~n\":2,\"s\":\"t\"}";

    /// `get` gives null where `path` gives the missing node, on objects, arrays, values and the missing node itself.
    @Test
    void getGivesNullWherePathGivesMissing() {
        final JsonNode tree = Json.readTree(DOCUMENT);
        assertThat(tree.get("a")).isSameAs(tree.path("a"));
        assertThat(tree.get("zz")).isNull();
        assertThat(tree.path("zz").isMissingNode()).isTrue();
        assertThat(tree.get(0)).isNull();
        assertThat(tree.path(0).isMissingNode()).isTrue();
        final JsonNode array = tree.path("a").path("b");
        assertThat(array.get(0).intValue()).isEqualTo(10);
        assertThat(array.get(-1)).isNull();
        assertThat(array.get(2)).isNull();
        assertThat(array.path(2).isMissingNode()).isTrue();
        assertThat(array.get("c")).isNull();
        assertThat(tree.path("s").get("x")).isNull();
        assertThat(tree.path("s").path("x").isMissingNode()).isTrue();
        assertThat(tree.path("zz").path("y").path(3).isMissingNode()).isTrue();
        assertThat(tree.get("n").isNull()).isTrue();
    }

    /// `has` counts a JSON null value, `hasNonNull` does not; neither finds anything on a non-object.
    @Test
    void hasCountsNullHasNonNullDoesNot() {
        final JsonNode tree = Json.readTree(DOCUMENT);
        assertThat(tree.has("n")).isTrue();
        assertThat(tree.hasNonNull("n")).isFalse();
        assertThat(tree.has("s")).isTrue();
        assertThat(tree.hasNonNull("s")).isTrue();
        assertThat(tree.has("zz")).isFalse();
        assertThat(tree.path("s").has("s")).isFalse();
        assertThat(Json.missing().has("s")).isFalse();
    }

    /// `required` gives a present value, JSON null included, and throws for an absent one or on a non-object.
    @Test
    void requiredThrowsWhereNothingIs() {
        final JsonNode tree = Json.readTree(DOCUMENT);
        assertThat(tree.required("s").stringValue()).isEqualTo("t");
        assertThat(tree.required("n").isNull()).isTrue();
        assertThatThrownBy(() -> tree.required("zz")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> tree.path("s").required("s")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.missing().required("s")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.array().required("0")).isInstanceOf(JsonException.class);
    }

    /// A JSON Pointer walks objects by name and arrays by index, with `~1` for `/` and `~0` for `~`; an index with a
    /// leading zero or a sign is no index; the empty pointer names the node; a pointer without a slash is refused.
    @Test
    void atFollowsJsonPointer() {
        final JsonNode tree = Json.readTree(DOCUMENT);
        assertThat(tree.at("")).isSameAs(tree);
        assertThat(tree.at(null)).isSameAs(tree);
        assertThat(tree.at("/a/b/1/c").stringValue()).isEqualTo("x");
        assertThat(tree.at("/a/b/0").intValue()).isEqualTo(10);
        assertThat(tree.at("/a~1b").intValue()).isEqualTo(1);
        assertThat(tree.at("/m~0n").intValue()).isEqualTo(2);
        assertThat(tree.at("/a/b/00").isMissingNode()).isTrue();
        assertThat(tree.at("/a/b/-1").isMissingNode()).isTrue();
        assertThat(tree.at("/a/b/2").isMissingNode()).isTrue();
        assertThat(tree.at("/s/x").isMissingNode()).isTrue();
        assertThat(tree.at("/zz/y/z").isMissingNode()).isTrue();
        assertThat(tree.at("/n").isNull()).isTrue();
        assertThatThrownBy(() -> tree.at("a")).isInstanceOf(IllegalArgumentException.class);
    }

    /// Each kind answers its own type tests and no other.
    @Test
    void typeTestsNameTheKind() {
        final JsonNode object = Json.object();
        assertThat(object.isObject()).isTrue();
        assertThat(object.isContainer()).isTrue();
        assertThat(object.isValueNode()).isFalse();
        assertThat(object.isArray()).isFalse();
        final JsonNode array = Json.array();
        assertThat(array.isArray()).isTrue();
        assertThat(array.isContainer()).isTrue();
        assertThat(array.isValueNode()).isFalse();
        final JsonNode text = Json.text("x");
        assertThat(text.isString()).isTrue();
        assertThat(text.isValueNode()).isTrue();
        assertThat(text.isNumber()).isFalse();
        final JsonNode integer = Json.number(1);
        assertThat(integer.isNumber()).isTrue();
        assertThat(integer.isIntegralNumber()).isTrue();
        assertThat(integer.isInt()).isTrue();
        assertThat(Json.number(1L).isInt()).isFalse();
        assertThat(Json.number(1L).isIntegralNumber()).isTrue();
        assertThat(Json.number(BigInteger.ONE).isIntegralNumber()).isTrue();
        assertThat(Json.number(1.0).isIntegralNumber()).isFalse();
        assertThat(Json.number(1.0f).isNumber()).isTrue();
        assertThat(Json.number(BigDecimal.ONE).isIntegralNumber()).isFalse();
        assertThat(Json.bool(true).isBoolean()).isTrue();
        assertThat(Json.nullNode().isNull()).isTrue();
        assertThat(Json.nullNode().isValueNode()).isTrue();
        assertThat(Json.missing().isMissingNode()).isTrue();
        assertThat(Json.missing().isValueNode()).isFalse();
        assertThat(Json.missing().isNull()).isFalse();
    }

    /// Reading maps each number to the narrowest of int, long and big integer, and every fraction or exponent to a
    /// double, as Jackson's tree reader does by default.
    @Test
    void readNumbersTakeJacksonsKinds() {
        assertThat(Json.readTree("1").numberValue()).isEqualTo(1);
        assertThat(Json.readTree("-0").numberValue()).isEqualTo(0);
        assertThat(Json.readTree("2147483647").numberValue()).isEqualTo(Integer.MAX_VALUE);
        assertThat(Json.readTree("2147483648").numberValue()).isEqualTo(2_147_483_648L);
        assertThat(Json.readTree("-9223372036854775808").numberValue()).isEqualTo(Long.MIN_VALUE);
        assertThat(Json.readTree("9223372036854775808").numberValue())
                .isEqualTo(new BigInteger("9223372036854775808"));
        assertThat(Json.readTree("1.5").numberValue()).isEqualTo(1.5);
        assertThat(Json.readTree("1e3").numberValue()).isEqualTo(1000.0);
        assertThat(Json.readTree("-0.0").numberValue()).isEqualTo(-0.0);
        assertThat(Json.number(1.5f).numberValue()).isEqualTo(1.5f);
        assertThat(Json.number(new BigDecimal("1.50")).numberValue()).isEqualTo(new BigDecimal("1.50"));
    }

    /// `size` and `isEmpty` count properties and elements; every value node has size 0 and is empty, a string too.
    @Test
    void sizeCountsChildrenOnly() {
        final JsonNode tree = Json.readTree(DOCUMENT);
        assertThat(tree.size()).isEqualTo(5);
        assertThat(tree.isEmpty()).isFalse();
        assertThat(tree.path("a").path("b").size()).isEqualTo(2);
        assertThat(Json.object().isEmpty()).isTrue();
        assertThat(Json.array().isEmpty()).isTrue();
        assertThat(Json.text("abc").size()).isZero();
        assertThat(Json.text("abc").isEmpty()).isTrue();
        assertThat(Json.number(3).isEmpty()).isTrue();
        assertThat(Json.missing().size()).isZero();
    }

    /// `asString` gives a string's text and a scalar's JSON form, "" for null and missing, and throws for a
    /// container; its default form gives the default for null and containers.
    @Test
    void asStringCoercesScalars() {
        assertThat(Json.text("x").asString()).isEqualTo("x");
        assertThat(Json.number(12).asString()).isEqualTo("12");
        assertThat(Json.number(1.5).asString()).isEqualTo("1.5");
        assertThat(Json.bool(false).asString()).isEqualTo("false");
        assertThat(Json.nullNode().asString()).isEmpty();
        assertThat(Json.missing().asString()).isEmpty();
        assertThatThrownBy(() -> Json.object().asString()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.array().asString()).isInstanceOf(JsonException.class);
        assertThat(Json.text("x").asString("d")).isEqualTo("x");
        assertThat(Json.text("").asString("d")).isEmpty();
        assertThat(Json.nullNode().asString("d")).isEqualTo("d");
        assertThat(Json.missing().asString("d")).isEqualTo("d");
        assertThat(Json.object().asString("d")).isEqualTo("d");
    }

    /// `asInt` and `asLong` truncate a number in range and parse an integral string; null and missing give 0; the
    /// rest throws, and the default forms give the default instead.
    @Test
    void asIntAndAsLongCoerceNumbersAndStrings() {
        assertThat(Json.number(7).asInt()).isEqualTo(7);
        assertThat(Json.number(1.9).asInt()).isEqualTo(1);
        assertThat(Json.number(-1.9).asLong()).isEqualTo(-1L);
        assertThat(Json.number(1L << 40).asLong()).isEqualTo(1L << 40);
        assertThat(Json.text("12").asInt()).isEqualTo(12);
        assertThat(Json.text("-12").asLong()).isEqualTo(-12L);
        assertThat(Json.nullNode().asInt()).isZero();
        assertThat(Json.missing().asLong()).isZero();
        assertThatThrownBy(() -> Json.text("x").asInt()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.text("1.5").asInt()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.number(1e20).asInt()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.number(1e20).asLong()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.bool(true).asInt()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.object().asLong()).isInstanceOf(JsonException.class);
        assertThat(Json.text("x").asInt(5)).isEqualTo(5);
        assertThat(Json.number(1e20).asInt(5)).isEqualTo(5);
        assertThat(Json.nullNode().asInt(5)).isEqualTo(5);
        assertThat(Json.missing().asLong(5L)).isEqualTo(5L);
        assertThat(Json.array().asLong(5L)).isEqualTo(5L);
        assertThat(Json.text("12").asInt(5)).isEqualTo(12);
    }

    /// `asDouble` converts a number and parses a numeric string; null and missing give 0; the rest throws, and the
    /// default form gives the default instead.
    @Test
    void asDoubleCoercesNumbersAndStrings() {
        assertThat(Json.number(3).asDouble()).isEqualTo(3.0);
        assertThat(Json.number(1.25).asDouble()).isEqualTo(1.25);
        assertThat(Json.text("1e2").asDouble()).isEqualTo(100.0);
        assertThat(Json.nullNode().asDouble()).isZero();
        assertThat(Json.missing().asDouble()).isZero();
        assertThatThrownBy(() -> Json.text("one").asDouble()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.bool(true).asDouble()).isInstanceOf(JsonException.class);
        assertThat(Json.text("one").asDouble(2.5)).isEqualTo(2.5);
        assertThat(Json.nullNode().asDouble(2.5)).isEqualTo(2.5);
        assertThat(Json.object().asDouble(2.5)).isEqualTo(2.5);
    }

    /// `asBoolean` takes a boolean, an integral number (non-zero is true) and the strings "true" and "false"; null and
    /// missing give false; a floating number or other text throws, and the default form gives the default instead.
    @Test
    void asBooleanCoercesBooleansIntegersAndWords() {
        assertThat(Json.bool(true).asBoolean()).isTrue();
        assertThat(Json.number(2).asBoolean()).isTrue();
        assertThat(Json.number(0L).asBoolean()).isFalse();
        assertThat(Json.text("true").asBoolean()).isTrue();
        assertThat(Json.text("false").asBoolean()).isFalse();
        assertThat(Json.nullNode().asBoolean()).isFalse();
        assertThat(Json.missing().asBoolean()).isFalse();
        assertThatThrownBy(() -> Json.number(1.0).asBoolean()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.text("yes").asBoolean()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.object().asBoolean()).isInstanceOf(JsonException.class);
        assertThat(Json.text("yes").asBoolean(true)).isTrue();
        assertThat(Json.nullNode().asBoolean(true)).isTrue();
        assertThat(Json.missing().asBoolean(true)).isTrue();
        assertThat(Json.text("false").asBoolean(true)).isFalse();
    }

    /// The exact number accessors give a number that fits without loss and throw for a fraction, a value out of range
    /// or a node that is not a number.
    @Test
    void numberAccessorsAreExact() {
        assertThat(Json.number(2.0).intValue()).isEqualTo(2);
        assertThat(Json.number(3L).intValue()).isEqualTo(3);
        assertThat(Json.number(BigInteger.TEN).longValue()).isEqualTo(10L);
        assertThat(Json.number(7).doubleValue()).isEqualTo(7.0);
        assertThatThrownBy(() -> Json.number(1.5).intValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.number(1.5).longValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.number(Long.MAX_VALUE).intValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.text("1").intValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.nullNode().longValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.bool(true).doubleValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.text("1").numberValue()).isInstanceOf(JsonException.class);
        assertThat(Json.number(3.0).canConvertToInt()).isTrue();
        assertThat(Json.number(3.5).canConvertToInt()).isFalse();
        assertThat(Json.number(Long.MAX_VALUE).canConvertToInt()).isFalse();
        assertThat(Json.number(Long.MAX_VALUE).canConvertToLong()).isTrue();
        assertThat(Json.number(BigInteger.TWO.pow(64)).canConvertToLong()).isFalse();
        assertThat(Json.text("1").canConvertToInt()).isFalse();
    }

    /// `booleanValue` and `stringValue` give their own kind only; `stringValue` of JSON null is null.
    @Test
    void booleanAndStringAccessorsAreExact() {
        assertThat(Json.bool(true).booleanValue()).isTrue();
        assertThat(Json.text("x").stringValue()).isEqualTo("x");
        assertThat(Json.nullNode().stringValue()).isNull();
        assertThatThrownBy(() -> Json.text("true").booleanValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.number(1).booleanValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.number(1).stringValue()).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.missing().stringValue()).isInstanceOf(JsonException.class);
    }

    /// An object's views list its properties in order and follow later changes; an array's values do the same; the
    /// iterator, `forEach` and the streams see the same values; a value node's views are empty.
    @Test
    void viewsAreLiveAndOrdered() {
        final ObjectNode object = Json.object().put("b", 1).put("a", 2);
        final List<String> names = new ArrayList<>(object.propertyNames());
        assertThat(names).containsExactly("b", "a");
        final Collection<String> liveNames = object.propertyNames();
        object.put("c", 3);
        assertThat(liveNames).containsExactly("b", "a", "c");
        assertThat(object.properties()).extracting(Map.Entry::getKey).containsExactly("b", "a", "c");
        assertThat(object.values()).containsExactly(Json.number(1), Json.number(2), Json.number(3));
        assertThat(object.valueStream().map(JsonNode::intValue).toList()).containsExactly(1, 2, 3);
        assertThat(object.propertyStream().map(Map.Entry::getKey).toList()).containsExactly("b", "a", "c");
        final List<JsonNode> seen = new ArrayList<>();
        object.forEach(seen::add);
        assertThat(seen).containsExactlyElementsOf(object.values());
        final List<JsonNode> iterated = new ArrayList<>();
        for (final JsonNode value : object) {
            iterated.add(value);
        }
        assertThat(iterated).containsExactlyElementsOf(object.values());
        final ArrayNode array = Json.array().add("x").add(true);
        assertThat(array.values()).containsExactly(Json.text("x"), Json.bool(true));
        assertThat(array.valueStream().count()).isEqualTo(2L);
        assertThat(array.properties()).isEmpty();
        assertThat(array.propertyNames()).isEmpty();
        assertThat(Json.text("x").values()).isEmpty();
        assertThat(Json.text("x").iterator().hasNext()).isFalse();
        assertThat(Json.missing().propertyStream().count()).isZero();
    }

    /// `findValuesAsString` collects every matching property's text in document order and does not search inside a
    /// match.
    @Test
    void findValuesAsStringWalksTheTree() {
        final JsonNode tree =
                Json.readTree("{\"k\":1,\"x\":[{\"k\":\"two\"},{\"y\":{\"k\":true}}],\"z\":{\"k\":null}}");
        assertThat(tree.findValuesAsString("k")).containsExactly("1", "two", "true", "");
        assertThat(tree.findValuesAsString("none")).isEmpty();
    }

    /// Trees are equal by content and kind: the same number held as an int and as a long differs, as in Jackson;
    /// equal trees hash alike; property order does not matter to an object's equality.
    @Test
    void equalityIsByContentAndKind() {
        final JsonNode read = Json.readTree("{\"a\":[1,\"x\",true,null,1.5],\"b\":{}}");
        final ObjectNode built = Json.object();
        built.putArray("a").add(1).add("x").add(true).addNull().add(1.5);
        built.putObject("b");
        assertThat(read).isEqualTo(built);
        assertThat(read.hashCode()).isEqualTo(built.hashCode());
        assertThat(Json.number(1)).isNotEqualTo(Json.number(1L));
        assertThat(Json.number(1.0)).isNotEqualTo(Json.number(1.0f));
        assertThat(Json.text("1")).isNotEqualTo(Json.number(1));
        assertThat(Json.readTree("{\"a\":1,\"b\":2}")).isEqualTo(Json.readTree("{\"b\":2,\"a\":1}"));
        assertThat(Json.readTree("[1,2]")).isNotEqualTo(Json.readTree("[2,1]"));
        assertThat(Json.nullNode()).isEqualTo(Json.readTree("null"));
        assertThat(Json.missing()).isEqualTo(Json.readTree(""));
        assertThat(Json.missing()).isNotEqualTo(Json.nullNode());
    }

    /// A deep copy shares no container with its original; value nodes are their own copies.
    @Test
    void deepCopySharesNothingMutable() {
        final ObjectNode original = (ObjectNode) Json.readTree("{\"a\":{\"b\":[1]},\"c\":\"d\"}");
        final ObjectNode copy = original.deepCopy();
        assertThat(copy).isEqualTo(original).isNotSameAs(original);
        ((ArrayNode) copy.at("/a/b")).add(2);
        ((ObjectNode) copy.path("a")).put("e", 3);
        assertThat(Json.write(original)).isEqualTo("{\"a\":{\"b\":[1]},\"c\":\"d\"}");
        assertThat(Json.write(copy)).isEqualTo("{\"a\":{\"b\":[1,2],\"e\":3},\"c\":\"d\"}");
        assertThat(original.path("c").deepCopy()).isSameAs(original.path("c"));
        assertThat(Json.missing().deepCopy()).isSameAs(Json.missing());
    }

    /// `toString` is the compact form and `toPrettyString` the pretty one; the missing node prints as nothing.
    @Test
    void toStringPrintsJson() {
        final JsonNode tree = Json.readTree(" { \"a\" : [ 1 , 2 ] } ");
        assertThat(tree.toString()).isEqualTo("{\"a\":[1,2]}");
        assertThat(tree.toPrettyString())
                .isEqualTo("{\n  \"a\" : [ 1, 2 ]\n}".replace("\n", System.lineSeparator()));
        assertThat(Json.text("q\"").toString()).isEqualTo("\"q\\\"\"");
        assertThat(Json.missing().toString()).isEmpty();
        assertThat(Json.missing().toPrettyString()).isEmpty();
    }

    /// The builders give the node kinds they name; null text and null big numbers give JSON null.
    @Test
    void buildersGiveTheirKinds() {
        assertThat(Json.text(null).isNull()).isTrue();
        assertThat(Json.number((BigDecimal) null).isNull()).isTrue();
        assertThat(Json.number((BigInteger) null).isNull()).isTrue();
        assertThat(Json.text("").stringValue()).isEmpty();
        assertThat(Json.bool(false).booleanValue()).isFalse();
        assertThat(Json.object()).isNotSameAs(Json.object());
        assertThat(Json.nullNode()).isSameAs(Json.nullNode());
    }
}
