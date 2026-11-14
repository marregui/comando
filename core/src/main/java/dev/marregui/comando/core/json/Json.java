package dev.marregui.comando.core.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Reads, builds and writes JSON trees, as jackson-databind 3.2.4's {@code JsonMapper}, {@code JsonNodeFactory} and
 * {@code JsonStringEncoder} do with their defaults. Bytes and streams are UTF-8, a leading byte order mark skipped.
 * Every read closes the stream or reader it was given, also when it fails.
 */
public final class Json {

    private Json() {
    }

    /**
     * The document {@code text} holds; the missing node when it holds only whitespace.
     *
     * @throws JsonException when the text is not one JSON document, trailing content included
     */
    public static JsonNode readTree(final String text) {
        return read(JsonInput.of(text), true);
    }

    /**
     * The document the UTF-8 {@code bytes} hold; the missing node when they hold only whitespace.
     *
     * @throws JsonException when the bytes are not one JSON document, trailing content included
     */
    public static JsonNode readTree(final byte[] bytes) {
        return read(JsonInput.of(bytes), true);
    }

    /**
     * The document the UTF-8 {@code stream} holds; the missing node when it holds only whitespace.
     *
     * @throws JsonException when the stream is not one JSON document, trailing content included, or cannot be read
     */
    public static JsonNode readTree(final InputStream stream) {
        return read(JsonInput.of(stream), true);
    }

    /**
     * The document {@code reader} holds; the missing node when it holds only whitespace.
     *
     * @throws JsonException when the reader is not one JSON document, trailing content included, or cannot be read
     */
    public static JsonNode readTree(final Reader reader) {
        return read(JsonInput.of(reader), true);
    }

    /**
     * The first document {@code text} holds, whatever follows it; the missing node when it holds only whitespace.
     *
     * @throws JsonException when the text does not start with a JSON document
     */
    public static JsonNode readFirst(final String text) {
        return read(JsonInput.of(text), false);
    }

    /**
     * The first document the UTF-8 {@code bytes} hold, whatever follows it; the missing node when they hold only
     * whitespace.
     *
     * @throws JsonException when the bytes do not start with a JSON document
     */
    public static JsonNode readFirst(final byte[] bytes) {
        return read(JsonInput.of(bytes), false);
    }

    /**
     * The first document the UTF-8 {@code stream} holds, whatever follows it, which is not read to its end; the
     * missing node when it holds only whitespace.
     *
     * @throws JsonException when the stream does not start with a JSON document, or cannot be read
     */
    public static JsonNode readFirst(final InputStream stream) {
        return read(JsonInput.of(stream), false);
    }

    /**
     * The first document {@code reader} holds, whatever follows it, which is not read to its end; the missing node
     * when it holds only whitespace.
     *
     * @throws JsonException when the reader does not start with a JSON document, or cannot be read
     */
    public static JsonNode readFirst(final Reader reader) {
        return read(JsonInput.of(reader), false);
    }

    /** A new empty object. */
    public static ObjectNode object() {
        return new ObjectNode();
    }

    /** A new empty array. */
    public static ArrayNode array() {
        return new ArrayNode();
    }

    /** The string node of {@code value}, or JSON null for {@code null}. */
    public static JsonNode text(final String value) {
        return value == null ? NullNode.INSTANCE : StringNode.valueOf(value);
    }

    /** The number node of {@code value}. */
    public static JsonNode number(final int value) {
        return IntNode.valueOf(value);
    }

    /** The number node of {@code value}, held as a {@code long} whatever its size. */
    public static JsonNode number(final long value) {
        return new LongNode(value);
    }

    /** The number node of {@code value}. */
    public static JsonNode number(final double value) {
        return new DoubleNode(value);
    }

    /** The number node of {@code value}. */
    public static JsonNode number(final float value) {
        return new FloatNode(value);
    }

    /** The number node of {@code value}, scale kept, or JSON null for {@code null}. */
    public static JsonNode number(final BigDecimal value) {
        return value == null ? NullNode.INSTANCE : new DecimalNode(value);
    }

    /** The number node of {@code value}, held as a big integer whatever its size, or JSON null for {@code null}. */
    public static JsonNode number(final BigInteger value) {
        return value == null ? NullNode.INSTANCE : new BigIntegerNode(value);
    }

    /** The boolean node of {@code value}. */
    public static JsonNode bool(final boolean value) {
        return BooleanNode.valueOf(value);
    }

    /** The JSON null node. */
    public static JsonNode nullNode() {
        return NullNode.INSTANCE;
    }

    /** The missing node: what {@code path} gives for an absent property or index. */
    public static JsonNode missing() {
        return MissingNode.INSTANCE;
    }

    /**
     * The compact text of {@code node}; the missing node writes as {@code null}.
     *
     * @throws JsonException when the tree nests deeper than 500 containers
     */
    public static String write(final JsonNode node) {
        return JsonWriter.write(node, false);
    }

    /**
     * The compact text of {@code node} as UTF-8; a lone surrogate is written as the escape
     * <code>&#92;uXXXX</code>.
     *
     * @throws JsonException when the tree nests deeper than 500 containers
     */
    public static byte[] writeBytes(final JsonNode node) {
        return JsonWriter.writeBytes(node, false);
    }

    /**
     * The text of {@code node} through Jackson's default pretty printer, its lines ended by
     * {@link System#lineSeparator()} as Jackson's DefaultIndenter ends them, and none at the end.
     *
     * @throws JsonException when the tree nests deeper than 500 containers
     */
    public static String pretty(final JsonNode node) {
        return JsonWriter.write(node, true);
    }

    /**
     * The text {@link #pretty} gives, as UTF-8; a lone surrogate is written as the escape <code>&#92;uXXXX</code>.
     *
     * @throws JsonException when the tree nests deeper than 500 containers
     */
    public static byte[] prettyBytes(final JsonNode node) {
        return JsonWriter.writeBytes(node, true);
    }

    /** The JSON string escape of {@code text}, without the quotes around it. */
    public static char[] quote(final CharSequence text) {
        return JsonWriter.quote(text);
    }

    private static JsonNode read(final JsonInput input, final boolean strict) {
        try (input) {
            return JsonParser.parse(input, strict);
        } catch (final IOException e) {
            throw new JsonException("cannot close JSON input", "", e);
        }
    }
}
