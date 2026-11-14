package dev.marregui.comando.core.json.bind;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Records to trees and back.
/// - a record writes its components in order, with nulls, and reads back equal; unknown properties are ignored
/// - scalars coerce as the files written before need, enums by name only
/// - a typed value carries its class name, and a name outside the allowed prefix is refused before it is looked up
/// - field visibility reads a record's fields, component visibility its accessors
class BinderTest {

    private static final String PREFIX = "dev.marregui.comando.core.json.bind.BinderTest$Inside";

    private static final Binder PLAIN = Binder.builder().build();

    private static final Binder TYPED = Binder.builder()
            .typed(Figure.class)
            .allowPrefix(PREFIX)
            .build();

    /// The modes a fixture holds.
    enum Mode { NEW, RESUME }

    /// A record of every component kind the binder handles.
    record All(String text, int number, long big, double ratio, boolean flag, Integer box, Optional<String> maybe,
               Path path, Mode mode, List<String> names, Map<String, Integer> counts, double[] values) {
    }

    /// A figure, whose implementations carry their class name.
    interface Figure {
    }

    /// A figure inside the allowed prefix.
    record InsideCircle(double radius) implements Figure {
    }

    /// Another figure inside the allowed prefix.
    record InsideSquare(double side) implements Figure {
    }

    /// A figure whose class name is outside the allowed prefix.
    record OutsideTriangle(double base) implements Figure {
    }

    /// A record holding figures where the declared type is the interface, a list of it, and a map of it.
    record Drawing(Figure main, List<Figure> others, Map<String, Figure> named) {
    }

    /// A record holding a figure as one concrete implementation.
    record Framed(InsideCircle circle) {
    }

    /// A record whose accessor masks the value its field holds.
    record Masked(String secret) {
        @Override
        public String secret() {
            return "***";
        }
    }

    /// A record whose constructor refuses a value.
    record Positive(int value) {
        Positive {
            if (value <= 0) {
                throw new IllegalArgumentException("value must be positive");
            }
        }
    }

    /// A record with two components that can be null.
    record Pair(String left, String right) {
    }

    private static Type listOf(final Type element) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {element};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }

    private static JsonNode tree(final String json) {
        return Json.readTree(json);
    }

    private static All all() {
        return new All("tëxt \"q\"", 7, Long.MAX_VALUE, Double.NaN, true, null, Optional.of("m"),
                Path.of("/tmp/a b/c.txt"), Mode.RESUME, List.of("x", "y"), new LinkedHashMap<>(Map.of("k", 1)),
                new double[] {1.5, Double.NaN});
    }

    /// A record writes its components in declaration order, null components as JSON null, an Optional as its value,
    /// a path as its file URI and an enum as its name.
    @Test
    void aRecordWritesItsComponentsInOrder() {
        assertThat(Json.write(PLAIN.toTree(all()))).isEqualTo(
                "{\"text\":\"tëxt \\\"q\\\"\",\"number\":7,\"big\":9223372036854775807,\"ratio\":\"NaN\","
                        + "\"flag\":true,\"box\":null,\"maybe\":\"m\",\"path\":\"file:///tmp/a%20b/c.txt\","
                        + "\"mode\":\"RESUME\",\"names\":[\"x\",\"y\"],\"counts\":{\"k\":1},\"values\":[1.5,\"NaN\"]}");
    }

    /// What a record writes reads back as an equal record, array component included.
    @Test
    void aRecordReadsBackEqual() {
        final All read = PLAIN.fromTree(PLAIN.toTree(all()), All.class);
        // Double.equals, unlike ==, holds NaN equal to itself
        assertThat(read).usingRecursiveComparison().withEqualsForType(Double::equals, Double.class).isEqualTo(all());
        assertThat(read.values()).containsExactly(1.5, Double.NaN);
    }

    /// Missing and null properties give a primitive's default, an empty Optional, or null; unknown ones are ignored.
    @Test
    void missingPropertiesGiveDefaultsAndUnknownOnesAreIgnored() {
        final All read = PLAIN.fromTree(tree("{\"unknown\":{\"deep\":[1]},\"number\":null}"), All.class);
        assertThat(read.number()).isZero();
        assertThat(read.flag()).isFalse();
        assertThat(read.maybe()).isEmpty();
        assertThat(read.text()).isNull();
        assertThat(read.names()).isNull();
        assertThat(PLAIN.fromTree(tree("null"), All.class)).isNull();
        assertThat(PLAIN.fromTree(Json.missing(), int.class)).isZero();
    }

    /// Scalars coerce as the files written before need: numbers and booleans read as text give their text, text
    /// holding a number reads as it, a fraction read as an integer is truncated, and NaN reads from its text.
    @Test
    void scalarsCoerce() {
        assertThat(PLAIN.fromTree(tree("5"), String.class)).isEqualTo("5");
        assertThat(PLAIN.fromTree(tree("true"), String.class)).isEqualTo("true");
        assertThat(PLAIN.fromTree(tree("\" 12 \""), int.class)).isEqualTo(12);
        assertThat(PLAIN.fromTree(tree("-1.5"), int.class)).isEqualTo(-1);
        assertThat(PLAIN.fromTree(tree("1e3"), long.class)).isEqualTo(1000L);
        assertThat(PLAIN.fromTree(tree("\"\""), int.class)).isZero();
        assertThat(PLAIN.fromTree(tree("\"\""), Integer.class)).isNull();
        assertThat(PLAIN.fromTree(tree("\"NaN\""), double.class)).isNaN();
        assertThat(PLAIN.fromTree(tree("1e400"), double.class)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(PLAIN.fromTree(tree("\"FALSE\""), boolean.class)).isFalse();
        assertThat(PLAIN.fromTree(tree("1"), boolean.class)).isTrue();
    }

    /// Values a type cannot hold are refused: a number beyond the type's range, text that is not a number, a
    /// fraction read as a boolean, an object read as text, a single value read as a list.
    @Test
    void valuesATypeCannotHoldAreRefused() {
        assertThatThrownBy(() -> PLAIN.fromTree(tree("2147483648"), int.class)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("9223372036854775808"), long.class))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("\"0x10\""), int.class)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("0.5"), boolean.class)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("{}"), String.class)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("\"x\""), listOf(String.class)))
                .isInstanceOf(JsonException.class);
    }

    /// An enum reads by its exact name only: neither another case nor its position reads, so a reordered enum never
    /// changes what a file means.
    @Test
    void anEnumReadsByItsNameOnly() {
        assertThat(PLAIN.fromTree(tree("\"RESUME\""), Mode.class)).isEqualTo(Mode.RESUME);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("\"resume\""), Mode.class)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("1"), Mode.class)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.fromTree(tree("\"0\""), Mode.class)).isInstanceOf(JsonException.class);
    }

    /// A path reads from its file URI or from plain text; a URI of another scheme is refused.
    @Test
    void aPathReadsFromAFileUriOnly() {
        assertThat(PLAIN.fromTree(tree("\"file:///tmp/a%20b\""), Path.class)).isEqualTo(Path.of("/tmp/a b"));
        assertThat(PLAIN.fromTree(tree("\"/tmp/c\""), Path.class)).isEqualTo(Path.of("/tmp/c"));
        assertThat(PLAIN.fromTree(tree("\"\""), Path.class)).isNull();
        assertThatThrownBy(() -> PLAIN.fromTree(tree("\"http://host/x\""), Path.class))
                .isInstanceOf(JsonException.class);
    }

    /// Lists keep their null elements and maps their order, built as mutable collections.
    @Test
    void collectionsReadAsMutableOnes() {
        final Object list = PLAIN.fromTree(tree("[\"a\",null]"), listOf(String.class));
        assertThat(list).isInstanceOf(ArrayList.class);
        assertThat(list).isEqualTo(Arrays.asList("a", null));
        final Counts counts = PLAIN.fromTree(tree("{\"of\":{\"b\":2,\"a\":1}}"), Counts.class);
        assertThat(counts.of()).isInstanceOf(LinkedHashMap.class).containsExactly(Map.entry("b", 2), Map.entry("a", 1));
        final Sorted sorted = PLAIN.fromTree(tree("{\"of\":{\"b\":2,\"a\":1},\"tags\":[\"t\"]}"), Sorted.class);
        assertThat(sorted.of()).isInstanceOf(TreeMap.class).containsExactly(Map.entry("a", 1), Map.entry("b", 2));
        assertThat(sorted.tags()).containsExactly("t");
    }

    /// A record of a map.
    record Counts(Map<String, Integer> of) {
    }

    /// A record of a sorted map and a set.
    record Sorted(TreeMap<String, Integer> of, Set<String> tags) {
    }

    /// A typed value writes its class name first, as the interface, in a list and in a map, and reads back equal.
    @Test
    void aTypedValueCarriesItsClassName() {
        final Drawing drawing = new Drawing(new InsideCircle(1.0), List.of(new InsideSquare(2.0)),
                new LinkedHashMap<>(Map.of("c", new InsideCircle(3.0))));
        final JsonNode written = TYPED.toTree(drawing);
        assertThat(Json.write(written.get("main"))).isEqualTo("{\"@type\":\"" + PREFIX + "Circle\",\"radius\":1.0}");
        assertThat(written.at("/others/0/@type").asString()).isEqualTo(PREFIX + "Square");
        assertThat(written.at("/named/c/@type").asString()).isEqualTo(PREFIX + "Circle");
        assertThat(TYPED.fromTree(written, Drawing.class)).isEqualTo(drawing);
    }

    /// The class name may stand anywhere in the object, and a concrete declared type still needs it.
    @Test
    void theClassNameStandsAnywhereAndAConcreteTypeNeedsIt() {
        final String circle = "{\"radius\":4.0,\"@type\":\"" + PREFIX + "Circle\"}";
        assertThat(TYPED.fromTree(tree(circle), Figure.class)).isEqualTo(new InsideCircle(4.0));
        assertThat(TYPED.fromTree(tree("{\"circle\":" + circle + "}"), Framed.class).circle().radius()).isEqualTo(4.0);
        assertThatThrownBy(() -> TYPED.fromTree(tree("{\"circle\":{\"radius\":4.0}}"), Framed.class))
                .isInstanceOf(JsonException.class);
    }

    /// A class name outside the allowed prefix is refused, though the class exists and implements the interface.
    @Test
    void aClassNameOutsideThePrefixIsRefused() {
        final String outside = "{\"@type\":\"" + OutsideTriangle.class.getName() + "\",\"base\":1.0}";
        assertThatThrownBy(() -> TYPED.fromTree(tree(outside), Figure.class))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("outside the allowed prefix");
        final Binder none = Binder.builder().typed(Figure.class).build();
        assertThatThrownBy(() -> none.fromTree(tree("{\"@type\":\"" + PREFIX + "Circle\"}"), Figure.class))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("outside the allowed prefix");
    }

    /// A class name inside the prefix that names no class, a class of another type, or an interface, is refused.
    @Test
    void aClassNameThatIsNotAConcreteSubtypeIsRefused() {
        for (final String name : List.of(PREFIX + "Hexagon", Figure.class.getName(), PREFIX + "Square")) {
            final Binder wide = Binder.builder().typed(Figure.class).allowPrefix("dev.marregui.comando.core.").build();
            assertThatThrownBy(() -> wide.fromTree(tree("{\"@type\":\"" + name + "\"}"), InsideCircle.class))
                    .isInstanceOf(JsonException.class);
        }
        assertThatThrownBy(() -> TYPED.fromTree(tree("{\"radius\":1.0}"), Figure.class))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> TYPED.fromTree(tree("[]"), Figure.class)).isInstanceOf(JsonException.class);
    }

    /// Field visibility reads a record's fields, component visibility its accessors.
    @Test
    void fieldVisibilityReadsFieldsAndComponentVisibilityAccessors() {
        final Binder fields = Binder.builder().fields().build();
        assertThat(Json.write(fields.toTree(new Masked("s3")))).isEqualTo("{\"secret\":\"s3\"}");
        assertThat(Json.write(PLAIN.toTree(new Masked("s3")))).isEqualTo("{\"secret\":\"***\"}");
    }

    /// A record named to omit nulls leaves its null components out; any other record writes them.
    @Test
    void omitNullsLeavesNullComponentsOut() {
        final Binder omit = Binder.builder().omitNulls(Pair.class).build();
        assertThat(Json.write(omit.toTree(new Pair("l", null)))).isEqualTo("{\"left\":\"l\"}");
        assertThat(Json.write(PLAIN.toTree(new Pair("l", null)))).isEqualTo("{\"left\":\"l\",\"right\":null}");
    }

    /// A constructor's refusal is a JSON failure whose message names the type and the property, not the value.
    @Test
    void aConstructorsRefusalIsAJsonFailure() {
        assertThatThrownBy(() -> PLAIN.fromTree(tree("{\"value\":-5}"), Positive.class))
                .isInstanceOf(JsonException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Positive.class.getName())
                .hasMessageNotContaining("-5");
        assertThatThrownBy(() -> PLAIN.fromTree(tree("{\"value\":\"tok-secret\"}"), Positive.class))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("property=value")
                .hasMessageNotContaining("tok-secret");
    }

    /// A value that is neither a record nor a known kind is refused on write.
    @Test
    void aValueThatIsNotARecordIsRefused() {
        assertThatThrownBy(() -> PLAIN.toTree(new Object())).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> PLAIN.toTree(Map.of(1, "one"))).isInstanceOf(JsonException.class);
    }
}
