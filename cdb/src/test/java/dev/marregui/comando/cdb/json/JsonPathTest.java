package dev.marregui.comando.cdb.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The path grammar of `json_extract`: an optional `$`, then `.name`, `["name"]` and `[n]` steps in any order, each
/// compiled to a member name (UTF-8, escapes decoded) or an element index; anything else is refused with a
/// `CdbException`.
class JsonPathTest {

    /// The empty path and `$` alone name the whole text: no step.
    @Test
    void emptyPathAndDollarHaveNoStep() {
        assertThat(JsonPath.parse("").steps()).isZero();
        assertThat(JsonPath.parse("$").steps()).isZero();
    }

    /// `.name` steps, with and without the leading `$`, give the names in order.
    @Test
    void dotStepsNameMembers() {
        assertThat(steps(JsonPath.parse("$.a"))).containsExactly(".a");
        assertThat(steps(JsonPath.parse(".a"))).containsExactly(".a");
        assertThat(steps(JsonPath.parse("$.alpha.beta.gamma"))).containsExactly(".alpha", ".beta", ".gamma");
        assertThat(steps(JsonPath.parse("$.a b"))).containsExactly(".a b");
        assertThat(steps(JsonPath.parse("$.\"q\""))).containsExactly(".\"q\"");
    }

    /// A `.name` step may hold any character but `.` and `[`, non-ASCII ones as UTF-8.
    @Test
    void dotStepKeepsNonAsciiAsUtf8() {
        final JsonPath path = JsonPath.parse("$.caf\u00e9");
        assertThat(path.steps()).isEqualTo(1);
        assertThat(path.key(0)).isEqualTo("caf\u00e9".getBytes(StandardCharsets.UTF_8));
    }

    /// `[n]` steps give the element indexes, counted from 0, mixed with member steps.
    @Test
    void bracketStepsNameElements() {
        assertThat(steps(JsonPath.parse("$[0]"))).containsExactly("[0]");
        assertThat(steps(JsonPath.parse("$[12][3]"))).containsExactly("[12]", "[3]");
        assertThat(steps(JsonPath.parse("$.a[3].b"))).containsExactly(".a", "[3]", ".b");
        assertThat(steps(JsonPath.parse("[7]"))).containsExactly("[7]");
        assertThat(steps(JsonPath.parse("$[2147483647]"))).containsExactly("[2147483647]");
    }

    /// An index past `int` names no element: it compiles to a negative index, which `find` answers as absent.
    @Test
    void anIndexPastIntNamesNoElement() {
        final JsonPath path = JsonPath.parse("$[99999999999]");
        assertThat(path.steps()).isEqualTo(1);
        assertThat(path.isIndex(0)).isTrue();
        assertThat(path.index(0)).isNegative();
        final byte[] array = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
        assertThat(new Json().find(array, 0, array.length, path)).isEqualTo(Json.ABSENT);
    }

    /// `["name"]` takes any name as a JSON string: `.` and `[` inside it, the escapes decoded.
    @Test
    void quotedStepsDecodeTheName() {
        assertThat(steps(JsonPath.parse("$[\"a.b\"]"))).containsExactly(".a.b");
        assertThat(steps(JsonPath.parse("$[\"x[y]\"]"))).containsExactly(".x[y]");
        assertThat(steps(JsonPath.parse("$[\"a\\\"b\"]"))).containsExactly(".a\"b");
        assertThat(steps(JsonPath.parse("$[\"back\\\\slash\"]"))).containsExactly(".back\\slash");
        assertThat(steps(JsonPath.parse("$[\"tab\\there\"]"))).containsExactly(".tab\there");
        assertThat(steps(JsonPath.parse("$[\"\\u00e9t\\u00E9\"]"))).containsExactly(".\u00e9t\u00e9");
        assertThat(steps(JsonPath.parse("$[\"\"]"))).containsExactly(".");
        assertThat(steps(JsonPath.parse("$.a[\"b\"][0]"))).containsExactly(".a", ".b", "[0]");
    }

    /// A surrogate pair in a quoted name decodes to its code point's four UTF-8 bytes.
    @Test
    void quotedStepDecodesASurrogatePair() {
        final JsonPath path = JsonPath.parse("$[\"\\uD83D\\uDE00\"]");
        assertThat(path.key(0)).isEqualTo(new String(Character.toChars(0x1F600)).getBytes(StandardCharsets.UTF_8));
    }

    /// Every path that breaks the grammar is refused with a `CdbException`.
    @ParameterizedTest
    @ValueSource(strings = {"$.", "$..a", "$.a.", "a", "$a", "$$", "$ .a", " $.a", "$[", "$[]", "$[a]", "$[1",
            "$[-1]", "$[+1]", "$[1.5]", "$[ 1]", "$[1 ]", "$[\"a\"", "$[\"a]", "$[\"a\"x", "$[\"\\x\"]",
            "$[\"\\u12g4\"]", "$[\"a\nb\"]", "$[\"a\\\"]", "$[\"a\"].", "$]", "$[0]x"})
    void invalidPathsAreRefused(final String text) {
        assertThatThrownBy(() -> JsonPath.parse(text)).isInstanceOf(CdbException.class);
    }

    /// `member` gives one member step whose name is the string's UTF-8, any characters included.
    @Test
    void memberIsOneNameStep() {
        final JsonPath path = JsonPath.member("a.b[\"c\"]\u00e9");
        assertThat(path.steps()).isEqualTo(1);
        assertThat(path.isIndex(0)).isFalse();
        assertThat(path.key(0)).isEqualTo("a.b[\"c\"]\u00e9".getBytes(StandardCharsets.UTF_8));
    }

    /// `element` gives one index step; a negative index, or one past `int`, names none.
    @Test
    void elementIsOneIndexStep() {
        final JsonPath three = JsonPath.element(3L);
        assertThat(three.steps()).isEqualTo(1);
        assertThat(three.isIndex(0)).isTrue();
        assertThat(three.index(0)).isEqualTo(3);
        assertThat(JsonPath.element(0L).index(0)).isZero();
        assertThat(JsonPath.element(-1L).index(0)).isNegative();
        assertThat(JsonPath.element(Long.MIN_VALUE).index(0)).isNegative();
        assertThat(JsonPath.element(Integer.MAX_VALUE + 1L).index(0)).isNegative();
        assertThat(JsonPath.element(Integer.MAX_VALUE).index(0)).isEqualTo(Integer.MAX_VALUE);
    }

    // each step as ".name" (the name decoded from UTF-8) or "[n]"
    private static List<String> steps(final JsonPath path) {
        final List<String> steps = new ArrayList<>(path.steps());
        for (int s = 0; s < path.steps(); s++) {
            steps.add(path.isIndex(s) ? "[" + path.index(s) + ']'
                    : '.' + new String(path.key(s), StandardCharsets.UTF_8));
        }
        return steps;
    }
}
