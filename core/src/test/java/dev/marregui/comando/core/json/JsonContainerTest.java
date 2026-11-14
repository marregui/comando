package dev.marregui.comando.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The mutators of `ObjectNode` and `ArrayNode`: what each adds, replaces or removes, what it returns, and the
/// order the result writes in.
class JsonContainerTest {

    /// Each `put` overload stores its kind, a null boxed or reference value stores JSON null, and a put over an
    /// existing name keeps the name's place.
    @Test
    void objectPutStoresEachKind() {
        final ObjectNode object = Json.object()
                .put("s", "x").put("i", 1).put("l", 2L).put("d", 1.5).put("f", 2.5f).put("b", true)
                .put("bd", new BigDecimal("1.50")).put("bi", new BigInteger("123456789012345678901"))
                .put("bi0", (Integer) null).put("bl0", (Long) null).put("bd0", (Double) null).put("bb0", (Boolean) null)
                .put("s0", (String) null).put("dec0", (BigDecimal) null).put("big0", (BigInteger) null)
                .put("I", Integer.valueOf(3)).put("L", Long.valueOf(4L)).put("D", Double.valueOf(0.5))
                .put("B", Boolean.FALSE).putNull("n");
        assertThat(Json.write(object)).isEqualTo("{\"s\":\"x\",\"i\":1,\"l\":2,\"d\":1.5,\"f\":2.5,\"b\":true,"
                + "\"bd\":1.50,\"bi\":123456789012345678901,\"bi0\":null,\"bl0\":null,\"bd0\":null,\"bb0\":null,"
                + "\"s0\":null,\"dec0\":null,\"big0\":null,\"I\":3,\"L\":4,\"D\":0.5,\"B\":false,\"n\":null}");
        assertThat(object.path("i").isInt()).isTrue();
        assertThat(object.path("l").isInt()).isFalse();
        assertThat(object.path("f").numberValue()).isEqualTo(2.5f);
        object.put("s", 9);
        assertThat(object.propertyNames()).first().isEqualTo("s");
        assertThat(object.path("s").intValue()).isEqualTo(9);
    }

    /// `putObject` and `putArray` add an empty child and return it; `set` stores a node, JSON null for null.
    @Test
    void objectPutContainersReturnTheChild() {
        final ObjectNode object = Json.object();
        object.putObject("o").put("k", "v");
        object.putArray("a").add(1).add(2);
        object.set("x", Json.text("y"));
        object.set("z", null);
        assertThat(Json.write(object)).isEqualTo("{\"o\":{\"k\":\"v\"},\"a\":[1,2],\"x\":\"y\",\"z\":null}");
        assertThatThrownBy(() -> object.set(null, Json.nullNode())).isInstanceOf(NullPointerException.class);
    }

    /// `withArray` gives the array already there, replaces an absent or null value with a new one, refuses any other
    /// kind of value, and refuses a JSON Pointer in place of a name.
    @Test
    void objectWithArrayFindsOrCreates() {
        final ObjectNode object = Json.object().putNull("n").put("s", "x");
        final ArrayNode created = object.withArray("a");
        created.add(1);
        assertThat(object.withArray("a")).isSameAs(created);
        object.withArray("n").add(true);
        assertThat(Json.write(object)).isEqualTo("{\"n\":[true],\"s\":\"x\",\"a\":[1]}");
        assertThatThrownBy(() -> object.withArray("s")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> object.withArray("/a")).isInstanceOf(IllegalArgumentException.class);
    }

    /// `setAll` adds every property of a map or another object, in its order, over existing names; `replace` and
    /// `remove` return the previous value or null.
    @Test
    void objectSetAllReplaceAndRemove() {
        final ObjectNode object = Json.object().put("a", 1);
        final Map<String, JsonNode> more = new LinkedHashMap<>();
        more.put("b", Json.number(2));
        more.put("a", Json.number(3));
        more.put("c", null);
        object.setAll(more);
        assertThat(Json.write(object)).isEqualTo("{\"a\":3,\"b\":2,\"c\":null}");
        object.setAll(Json.object().put("d", "x").put("b", false));
        assertThat(Json.write(object)).isEqualTo("{\"a\":3,\"b\":false,\"c\":null,\"d\":\"x\"}");
        assertThat(object.replace("a", Json.text("r"))).isEqualTo(Json.number(3));
        assertThat(object.replace("e", null)).isNull();
        assertThat(object.path("e").isNull()).isTrue();
        assertThat(object.remove("d")).isEqualTo(Json.text("x"));
        assertThat(object.remove("d")).isNull();
        assertThat(Json.write(object)).isEqualTo("{\"a\":\"r\",\"b\":false,\"c\":null,\"e\":null}");
    }

    /// `remove` of names, `retain` of names and `removeAll` keep or drop properties and return the object.
    @Test
    void objectRemoveRetainAndRemoveAll() {
        final ObjectNode object = Json.object().put("a", 1).put("b", 2).put("c", 3).put("d", 4);
        assertThat(object.remove(List.of("b", "zz"))).isSameAs(object);
        assertThat(object.propertyNames()).containsExactly("a", "c", "d");
        assertThat(object.retain(List.of("d", "a", "zz"))).isSameAs(object);
        assertThat(object.propertyNames()).containsExactly("a", "d");
        assertThat(object.retain("d")).isSameAs(object);
        assertThat(Json.write(object)).isEqualTo("{\"d\":4}");
        assertThat(object.removeAll()).isSameAs(object);
        assertThat(object.isEmpty()).isTrue();
        assertThat(Json.write(object)).isEqualTo("{}");
    }

    /// Each `add` overload appends its kind, a null boxed or reference value appends JSON null.
    @Test
    void arrayAddAppendsEachKind() {
        final ArrayNode array = Json.array()
                .add("x").add(1).add(2L).add(1.5).add(2.5f).add(true).add(new BigDecimal("1.50"))
                .add(new BigInteger("123456789012345678901")).add((String) null).add((Integer) null)
                .add((Long) null).add((Double) null).add((Boolean) null).add((BigDecimal) null)
                .add((BigInteger) null).add((JsonNode) null).add(Integer.valueOf(3)).add(Long.valueOf(4L))
                .add(Double.valueOf(0.5)).add(Boolean.TRUE).add(Json.text("n")).addNull();
        assertThat(Json.write(array)).isEqualTo("[\"x\",1,2,1.5,2.5,true,1.50,123456789012345678901,null,null,null,"
                + "null,null,null,null,null,3,4,0.5,true,\"n\",null]");
        assertThat(array.size()).isEqualTo(22);
    }

    /// `addObject` and `addArray` append an empty child and return it; `addAll` appends every element of an array or
    /// a collection, JSON null for a null element.
    @Test
    void arrayAddContainersAndAddAll() {
        final ArrayNode array = Json.array();
        array.addObject().put("k", 1);
        array.addArray().add(2);
        array.addAll(Json.array().add(3).add(4));
        array.addAll(Arrays.asList(Json.text("five"), null));
        assertThat(Json.write(array)).isEqualTo("[{\"k\":1},[2],3,4,\"five\",null]");
    }

    /// `set` replaces an element in range and refuses one out of range; `insert` puts an element before an index,
    /// first for a negative one and last past the end.
    @Test
    void arraySetAndInsert() {
        final ArrayNode array = Json.array().add(1).add(2);
        array.set(0, Json.text("a"));
        array.set(1, null);
        assertThat(Json.write(array)).isEqualTo("[\"a\",null]");
        assertThatThrownBy(() -> array.set(2, Json.text("x"))).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> array.set(-1, Json.text("x"))).isInstanceOf(JsonException.class);
        array.insert(1, Json.number(5));
        array.insert(-3, Json.number(0));
        array.insert(99, Json.number(9));
        array.insert(0, null);
        assertThat(Json.write(array)).isEqualTo("[null,0,\"a\",5,null,9]");
    }

    /// `remove` gives the removed element, or null out of range; `removeAll` empties the array and returns it.
    @Test
    void arrayRemoveAndRemoveAll() {
        final ArrayNode array = Json.array().add(1).add(2).add(3);
        assertThat(array.remove(1)).isEqualTo(Json.number(2));
        assertThat(array.remove(5)).isNull();
        assertThat(array.remove(-1)).isNull();
        assertThat(Json.write(array)).isEqualTo("[1,3]");
        assertThat(array.removeAll()).isSameAs(array);
        assertThat(Json.write(array)).isEqualTo("[]");
    }
}
