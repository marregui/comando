package dev.marregui.comando.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Writing: the compact form, the pretty form of Jackson's default pretty printer, UTF-8 bytes, string escaping and
/// `quote`, number forms, and the nesting bound.
class JsonWriterTest {

    /// The compact form has no whitespace between tokens; the missing node writes as JSON null, as Jackson writes it,
    /// though its `toString` is empty.
    @Test
    void compactHasNoWhitespace() {
        final ObjectNode object = Json.object();
        object.putArray("a").add(1).add(2.5).add("x").add(true).addNull().addObject();
        object.putArray("b");
        object.putObject("c").put("d", false);
        assertThat(Json.write(object))
                .isEqualTo("{\"a\":[1,2.5,\"x\",true,null,{}],\"b\":[],\"c\":{\"d\":false}}");
        assertThat(Json.write(Json.text("x"))).isEqualTo("\"x\"");
        assertThat(Json.write(Json.nullNode())).isEqualTo("null");
        assertThat(Json.write(Json.missing())).isEqualTo("null");
    }

    /// The pretty form puts each property on its own line, indented two spaces a level, with " : " after the name;
    /// arrays stay on one line as `[ a, b ]`; empty containers write as `{ }` and `[ ]`; lines end with the system
    /// line separator.
    @Test
    void prettyIsJacksonsDefaultPrettyPrinter() {
        final ObjectNode object = Json.object().put("a", 1);
        object.putArray("b").add(1).add(2);
        object.putObject("c");
        object.putArray("d");
        object.putArray("e").addObject().put("x", 1);
        object.putObject("f").putObject("g").put("h", "i");
        assertThat(Json.pretty(object)).isEqualTo("""
                {
                  "a" : 1,
                  "b" : [ 1, 2 ],
                  "c" : { },
                  "d" : [ ],
                  "e" : [ {
                    "x" : 1
                  } ],
                  "f" : {
                    "g" : {
                      "h" : "i"
                    }
                  }
                }""".replace("\n", System.lineSeparator()));
        assertThat(Json.pretty(Json.array())).isEqualTo("[ ]");
        assertThat(Json.pretty(Json.object())).isEqualTo("{ }");
        assertThat(Json.pretty(Json.number(3))).isEqualTo("3");
        assertThat(Json.pretty(Json.missing())).isEqualTo("null");
    }

    /// `writeBytes` and `prettyBytes` give the UTF-8 bytes of the compact and the pretty form, non-ASCII and
    /// supplementary characters included.
    @Test
    void writeBytesIsUtf8OfCompactAndPrettyBytesOfPretty() {
        final JsonNode tree = Json.object().put("\u00e9", "\u4e2d\ud83d\ude00").put("a", 1);
        assertThat(Json.writeBytes(tree)).isEqualTo(Json.write(tree).getBytes(StandardCharsets.UTF_8));
        assertThat(Json.prettyBytes(tree)).isEqualTo(Json.pretty(tree).getBytes(StandardCharsets.UTF_8));
        assertThat(Json.writeBytes(Json.missing())).isEqualTo("null".getBytes(StandardCharsets.US_ASCII));
        assertThat(Json.prettyBytes(Json.missing())).isEqualTo("null".getBytes(StandardCharsets.US_ASCII));
    }

    /// A lone surrogate, in a name or a value, is written as its escape with upper-case hex digits, where a String's
    /// own UTF-8 encoding would write `?`; a pair written whole is its four-byte sequence.
    @Test
    void loneSurrogatesAreEscapedInBytes() {
        final JsonNode tree = Json.object().put("n\udc00", "a\ud800b\ud83d\ude00\udfff");
        final String escaped = "{\"n\\uDC00\":\"a\\uD800b\ud83d\ude00\\uDFFF\"}";
        assertThat(Json.writeBytes(tree)).isEqualTo(escaped.getBytes(StandardCharsets.UTF_8));
        assertThat(Json.prettyBytes(tree)).isEqualTo(("{" + System.lineSeparator()
                + "  \"n\\uDC00\" : \"a\\uD800b\ud83d\ude00\\uDFFF\"" + System.lineSeparator() + "}")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(Json.writeBytes(Json.text("\ud800"))).isEqualTo("\"\\uD800\"".getBytes(StandardCharsets.US_ASCII));
    }

    /// Strings escape the quote, the backslash and every control character, the usual ones in their short form and
    /// the rest as a six-character escape with upper-case hex digits; everything else, `/`, DEL, U+2028 and non-ASCII
    /// included, is written as it is.
    @Test
    void stringsEscapeOnlyWhatJsonRequires() {
        final String raw = "\"\\/\b\f\n\r\t\u0000\u001f\u007f\u00e9\u2028\ud83d\ude00";
        final String escaped = "\\\"\\\\/\\b\\f\\n\\r\\t\\u0000\\u001F\u007f\u00e9\u2028\ud83d\ude00";
        assertThat(Json.write(Json.text(raw))).isEqualTo('"' + escaped + '"');
        assertThat(Json.write(Json.object().put(raw, 1))).isEqualTo("{\"" + escaped + "\":1}");
        assertThat(new String(Json.quote(raw))).isEqualTo(escaped);
        assertThat(new String(Json.quote(new StringBuilder("a\"b")))).isEqualTo("a\\\"b");
        assertThat(Json.quote("")).isEmpty();
    }

    /// Numbers write in Java's shortest round-trip form for doubles and floats, digits for integers, and as given
    /// for big decimals; a read number writes back as Jackson writes its node.
    @Test
    void numbersWriteTheirForm() {
        assertThat(Json.write(Json.number(1.0))).isEqualTo("1.0");
        assertThat(Json.write(Json.number(0.1f))).isEqualTo("0.1");
        assertThat(Json.write(Json.number(-0.0))).isEqualTo("-0.0");
        assertThat(Json.write(Json.number(Long.MIN_VALUE))).isEqualTo("-9223372036854775808");
        assertThat(Json.write(Json.number(new BigDecimal("1.50")))).isEqualTo("1.50");
        assertThat(Json.write(Json.number(BigInteger.TWO.pow(70)))).isEqualTo("1180591620717411303424");
        assertThat(Json.write(Json.readTree("1e3"))).isEqualTo("1000.0");
        assertThat(Json.write(Json.readTree("-0"))).isEqualTo("0");
        assertThat(Json.write(Json.readTree("1.5e10"))).isEqualTo("1.5E10");
    }

    /// A built tree nested deeper than the writer's bound is refused; one at the bound is written.
    @Test
    void writeNestingIsBounded() {
        final ArrayNode atBound = Json.array();
        ArrayNode inner = atBound;
        for (int i = 1; i < JsonWriter.MAX_DEPTH; i++) {
            inner = inner.addArray();
        }
        assertThat(Json.write(atBound)).hasSize(2 * JsonWriter.MAX_DEPTH);
        inner.addArray().addArray();
        assertThatThrownBy(() -> Json.write(atBound)).isInstanceOf(JsonException.class);
    }
}
