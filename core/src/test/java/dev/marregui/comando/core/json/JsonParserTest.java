package dev.marregui.comando.core.json;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Reading: the strict and the lenient entry points, empty input, the grammar's refusals and where they point, the
/// nesting bound, byte order marks, UTF-8 decoding and the surrogate rules of property names.
class JsonParserTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /// Empty or whitespace-only input reads as the missing node through every entry point.
    @Test
    void emptyInputIsMissing() {
        for (final String text : new String[] {"", " ", "\n\t\r "}) {
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            assertThat(Json.readTree(text).isMissingNode()).isTrue();
            assertThat(Json.readTree(bytes).isMissingNode()).isTrue();
            assertThat(Json.readTree(new ByteArrayInputStream(bytes)).isMissingNode()).isTrue();
            assertThat(Json.readTree(new StringReader(text)).isMissingNode()).isTrue();
            assertThat(Json.readFirst(text).isMissingNode()).isTrue();
            assertThat(Json.readFirst(bytes).isMissingNode()).isTrue();
        }
    }

    /// `readTree` refuses anything after the document; `readFirst` returns the first document and ignores the rest,
    /// but still refuses a first document that is broken.
    @Test
    void readFirstIgnoresTrailingContent() {
        for (final String text : new String[] {"1 2", "[1] x", "{} {}", "true]", "\"a\"\"b\""}) {
            assertThatThrownBy(() -> Json.readTree(text)).isInstanceOf(JsonException.class);
        }
        assertThat(Json.write(Json.readFirst("1 2"))).isEqualTo("1");
        assertThat(Json.write(Json.readFirst("[1] x"))).isEqualTo("[1]");
        assertThat(Json.write(Json.readFirst("{\"a\":1}{"))).isEqualTo("{\"a\":1}");
        assertThat(Json.write(Json.readFirst(new StringReader("[2] ]")))).isEqualTo("[2]");
        assertThat(Json.write(Json.readFirst(new ByteArrayInputStream("3 x".getBytes(StandardCharsets.UTF_8)))))
                .isEqualTo("3");
        assertThatThrownBy(() -> Json.readFirst("[1,")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.readFirst("[1,]")).isInstanceOf(JsonException.class);
    }

    /// Input outside RFC 8259 is refused: trailing commas, leading zeros, bare names, single quotes, comments, the
    /// non-finite literals, unescaped controls in strings and unknown escapes.
    @Test
    void grammarViolationsAreRefused() {
        final String[] broken = {
            "[1,]", "{\"a\":1,}", "[,1]", "01", "-01", "1.", "1e", ".5", "+1", "[1 2]", "{\"a\" 1}", "{a:1}", "'a'",
            "nul", "truex", "/* c */ 1", "NaN", "Infinity", "\"\t\"", "\"\\x\"", "\"\\u12G4\"", "[", "{", "\"abc",
            "[1}", "{\"a\"}", "]", "\u00a01", "\uFEFF1",
        };
        for (final String text : broken) {
            assertThatThrownBy(() -> Json.readTree(text)).as(text).isInstanceOf(JsonException.class);
        }
    }

    /// A parse failure's message ends with the line and column of the offending character, both from 1, and the
    /// original message leaves them out.
    @Test
    void failuresNameTheirPlace() {
        assertThatThrownBy(() -> Json.readTree("[1,\n 2,]"))
                .isInstanceOfSatisfying(JsonException.class, e -> {
                    assertThat(e.getMessage()).endsWith("line=2, column=4]");
                    assertThat(e.getOriginalMessage()).doesNotContain("line=");
                });
        assertThatThrownBy(() -> Json.readTree("{\"a\":tru"))
                .isInstanceOfSatisfying(JsonException.class, e ->
                        assertThat(e.getMessage()).contains("line=1"));
    }

    /// Duplicate property names keep the last value at the first name's place, as Jackson's tree reader does.
    @Test
    void duplicateNamesKeepTheLastValue() {
        assertThat(Json.write(Json.readTree("{\"a\":1,\"b\":2,\"a\":3}"))).isEqualTo("{\"a\":3,\"b\":2}");
    }

    /// Escapes decode to their characters, an escaped surrogate pair to its code point, and a lone escaped surrogate
    /// in a string value is kept as it is.
    @Test
    void escapesDecode() {
        final JsonNode text = Json.readTree("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\\u00e9\\uD83D\\uDE00\"");
        assertThat(text.stringValue()).isEqualTo("\"\\/\b\f\n\r\tA\u00e9\uD83D\uDE00");
        assertThat(Json.readTree("\"\\uD800\"").stringValue()).isEqualTo("\uD800");
        assertThat(Json.readTree("\"\\uDC00\\uD800\"").stringValue()).isEqualTo("\uDC00\uD800");
    }

    /// A property name refuses an escaped high surrogate not followed by an escaped low one, and a lone escaped low
    /// surrogate; an escaped pair reads as its code point.
    @Test
    void propertyNamesRefuseBrokenSurrogates() {
        assertThat(Json.readTree("{\"\\uD83D\\uDE00\":1}").propertyNames()).containsExactly("\uD83D\uDE00");
        for (final String text : new String[] {
            "{\"\\uD800\":1}", "{\"\\uDC00\":1}", "{\"\\uD800x\":1}", "{\"\\uD800\\n\":1}", "{\"\\uD800\\uD800\":1}",
        }) {
            assertThatThrownBy(() -> Json.readTree(text)).as(text).isInstanceOf(JsonException.class);
        }
    }

    /// Nesting is bounded at 500 levels, objects and arrays counted together.
    @Test
    void nestingIsBounded() {
        assertThat(Json.readTree(nested(JsonParser.MAX_DEPTH)).isArray()).isTrue();
        assertThatThrownBy(() -> Json.readTree(nested(JsonParser.MAX_DEPTH + 1))).isInstanceOf(JsonException.class);
        final String objects = "{\"a\":".repeat(JsonParser.MAX_DEPTH + 1) + "1" + "}".repeat(JsonParser.MAX_DEPTH + 1);
        assertThatThrownBy(() -> Json.readTree(objects)).isInstanceOf(JsonException.class);
    }

    /// A UTF-8 byte order mark is skipped when a byte follows it, from bytes and from a stream; a mark alone, or one
    /// in a string, is refused, as Jackson does.
    @Test
    void byteOrderMarkIsSkippedBeforeContent() {
        assertThat(Json.readTree(withBom("1")).intValue()).isEqualTo(1);
        assertThat(Json.readTree(new ByteArrayInputStream(withBom("[true]"))).path(0).booleanValue()).isTrue();
        assertThat(Json.readTree(withBom(" ")).isMissingNode()).isTrue();
        assertThatThrownBy(() -> Json.readTree(BOM)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.readTree(new ByteArrayInputStream(BOM))).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.readTree("\uFEFF1")).isInstanceOf(JsonException.class);
    }

    /// Bytes decode as UTF-8, four-byte sequences to surrogate pairs; a bad start or continuation byte, a truncated
    /// sequence or an encoded surrogate is refused.
    @Test
    void utf8IsDecodedAndChecked() {
        final String text = "[\"\u00e9\u4e2d\uD83D\uDE00\u07ff\u0800\"]";
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertThat(Json.readTree(bytes).path(0).stringValue()).isEqualTo("\u00e9\u4e2d\uD83D\uDE00\u07ff\u0800");
        assertThat(Json.readTree(new ByteArrayInputStream(bytes))).isEqualTo(Json.readTree(text));
        final byte[][] broken = {
            {'"', (byte) 0xFF, '"'}, {'"', (byte) 0x80, '"'}, {'"', (byte) 0xC3, '"'}, {(byte) 0xC3},
            {'"', (byte) 0xE2, (byte) 0x82, '"'}, {'"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"'},
            {'{', '"', (byte) 0xED, (byte) 0xBF, (byte) 0xBF, '"', ':', '1', '}'}, {'[', '1', ',', (byte) 0xFF, ']'},
        };
        for (final byte[] input : broken) {
            assertThatThrownBy(() -> Json.readTree(input)).isInstanceOf(JsonException.class);
            assertThatThrownBy(() -> Json.readTree(new ByteArrayInputStream(input))).isInstanceOf(JsonException.class);
        }
    }

    /// An overlong UTF-8 form is refused wherever it stands: an overlong `[`, `"`, `\\` or `A` would otherwise read as
    /// that character, a quote ending a string early or a bracket opening a document.
    @Test
    void overlongUtf8FormsAreRefused() {
        final byte[][] overlong = {
            {(byte) 0xC1, (byte) 0x9B, '1', ']'},
            {'"', 'a', (byte) 0xC0, (byte) 0xA2},
            {'"', (byte) 0xC1, (byte) 0x9C, 'n', '"'},
            {'{', '"', (byte) 0xE0, (byte) 0x81, (byte) 0x81, '"', ':', '1', '}'},
            {'"', (byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0xC1, '"'},
        };
        for (final byte[] input : overlong) {
            assertThatThrownBy(() -> Json.readTree(input)).isInstanceOf(JsonException.class);
            assertThatThrownBy(() -> Json.readTree(new ByteArrayInputStream(input))).isInstanceOf(JsonException.class);
        }
    }

    /// A number of the most digits allowed past a zero integer part reads anywhere in a document: the zero is not
    /// counted, as jackson-core does not count it.
    @Test
    void aZeroIntegerPartIsNotCountedTowardTheNumberBound() {
        final String fraction = "0." + "1".repeat(JsonParser.MAX_NUMBER_LENGTH);
        assertThat(Json.readTree("[" + fraction + "]").path(0).isNumber()).isTrue();
        final String exponent = "0e" + "1".repeat(JsonParser.MAX_NUMBER_LENGTH);
        assertThat(Json.readTree("{\"a\":" + exponent + "}").path("a").isNumber()).isTrue();
        assertThatThrownBy(() -> Json.readTree("[0." + "1".repeat(JsonParser.MAX_NUMBER_LENGTH + 1) + "]"))
                .isInstanceOf(JsonException.class);
    }

    /// A long document read from a stream and a reader crosses buffer boundaries without changing what it reads.
    @Test
    void longInputsReadAcrossBuffers() {
        final StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < 20_000; i++) {
            text.append(i == 0 ? "" : ",").append("\"\u00e9\uD83D\uDE00").append(i).append('"');
        }
        final String document = text.append(']').toString();
        final JsonNode expected = Json.readTree(document);
        assertThat(expected.size()).isEqualTo(20_000);
        final byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        assertThat(Json.readTree(new ByteArrayInputStream(bytes))).isEqualTo(expected);
        assertThat(Json.readTree(new StringReader(document))).isEqualTo(expected);
        assertThat(Json.readTree(bytes)).isEqualTo(expected);
    }

    /// The stream or reader handed in is closed after the read, whether the read succeeds or fails.
    @Test
    void inputsAreClosed() {
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream good = closing("[1]", closed);
        Json.readTree(good);
        assertThat(closed).isTrue();
        closed.set(false);
        final InputStream bad = closing("[1,", closed);
        assertThatThrownBy(() -> Json.readTree(bad)).isInstanceOf(JsonException.class);
        assertThat(closed).isTrue();
        final AtomicBoolean readerClosed = new AtomicBoolean();
        Json.readFirst(new StringReader("1 2") {
            @Override
            public void close() {
                readerClosed.set(true);
                super.close();
            }
        });
        assertThat(readerClosed).isTrue();
    }

    private static String nested(final int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    private static byte[] withBom(final String text) {
        final byte[] content = text.getBytes(StandardCharsets.UTF_8);
        final byte[] bytes = new byte[BOM.length + content.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(content, 0, bytes, BOM.length, content.length);
        return bytes;
    }

    private static InputStream closing(final String text, final AtomicBoolean closed) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
    }
}
