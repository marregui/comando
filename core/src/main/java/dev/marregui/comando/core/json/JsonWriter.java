package dev.marregui.comando.core.json;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Writes a tree as jackson-databind 3.2.4 does with its defaults, compact or through its DefaultPrettyPrinter: two
 * spaces of indent per enclosing object and {@code " : "} after a name, arrays on one line as {@code [ 1, 2 ]}, empty
 * containers as <code>{ }</code> and {@code [ ]}, lines ended by the system line separator as its DefaultIndenter ends
 * them, and none at the end. Strings escape
 * {@code "} and {@code \}, the controls Jackson writes short ({@code \b \t \n \f \r}) and every other control as
 * <code>&#92;u00XX</code>; anything else is written raw. A non-finite floating-point number is written as a quoted
 * string.
 */
final class JsonWriter {

    /** jackson-core 3.2.4's StreamWriteConstraints DEFAULT_MAX_DEPTH: deeper containers are refused. */
    static final int MAX_DEPTH = 500;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final String LINE_SEPARATOR = System.lineSeparator();
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int ONE_BYTE_LIMIT = 0x80;
    private static final int TWO_BYTE_LIMIT = 0x800;
    private final StringBuilder out = new StringBuilder();
    private final boolean pretty;
    private int depth;
    /** The objects around the value being written, which is what the pretty printer indents by. */
    private int objects;

    private JsonWriter(final boolean pretty) {
        this.pretty = pretty;
    }

    /**
     * The text of {@code node}, compact or pretty.
     *
     * @throws JsonException when the tree nests deeper than {@value #MAX_DEPTH} containers
     */
    static String write(final JsonNode node, final boolean pretty) {
        final JsonWriter writer = new JsonWriter(pretty);
        writer.value(node);
        return writer.out.toString();
    }

    /**
     * The text of {@code node}, compact or pretty, as UTF-8. A surrogate pair is written as its four-byte sequence, a
     * lone surrogate as the escape <code>&#92;uXXXX</code>, as jackson-core's UTF8JsonGenerator does with its default
     * of combining pairs.
     *
     * @throws JsonException when the tree nests deeper than {@value #MAX_DEPTH} containers
     */
    static byte[] writeBytes(final JsonNode node, final boolean pretty) {
        final String text = write(node, pretty);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length() + (text.length() >> 3));
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < ONE_BYTE_LIMIT) {
                bytes.write(c);
            } else if (c < TWO_BYTE_LIMIT) {
                bytes.write(0xC0 | c >> 6);
                bytes.write(0x80 | c & 0x3F);
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                final int codePoint = Character.toCodePoint(c, text.charAt(++i));
                bytes.write(0xF0 | codePoint >> 18);
                bytes.write(0x80 | codePoint >> 12 & 0x3F);
                bytes.write(0x80 | codePoint >> 6 & 0x3F);
                bytes.write(0x80 | codePoint & 0x3F);
            } else if (Character.isSurrogate(c)) {
                bytes.write('\\');
                bytes.write('u');
                for (int shift = 12; shift >= 0; shift -= 4) {
                    bytes.write(HEX[c >> shift & 0xF]);
                }
            } else {
                bytes.write(0xE0 | c >> 12);
                bytes.write(0x80 | c >> 6 & 0x3F);
                bytes.write(0x80 | c & 0x3F);
            }
        }
        return bytes.toByteArray();
    }

    /** The JSON string escape of {@code text}, without the quotes around it. */
    static char[] quote(final CharSequence text) {
        final StringBuilder escaped = new StringBuilder(text.length() + 8);
        escape(text, escaped);
        final char[] chars = new char[escaped.length()];
        escaped.getChars(0, chars.length, chars, 0);
        return chars;
    }

    private void value(final JsonNode node) {
        switch (node) {
            case ObjectNode object -> object(object);
            case ArrayNode array -> array(array);
            case StringNode string -> string(string.value());
            case NumericNode number -> number(number);
            case BooleanNode bool -> out.append(bool.value());
            case NullNode _, MissingNode _ -> out.append("null");
        }
    }

    private void object(final ObjectNode node) {
        enter();
        out.append('{');
        objects++;
        boolean first = true;
        for (final Map.Entry<String, JsonNode> property : node.properties()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            if (pretty) {
                newLine(objects);
            }
            string(property.getKey());
            out.append(pretty ? " : " : ":");
            value(property.getValue());
        }
        objects--;
        if (pretty) {
            if (first) {
                out.append(' ');
            } else {
                newLine(objects);
            }
        }
        out.append('}');
        depth--;
    }

    private void array(final ArrayNode node) {
        enter();
        out.append('[');
        boolean first = true;
        for (final JsonNode element : node.values()) {
            if (!first) {
                out.append(',');
            }
            if (pretty) {
                out.append(' ');
            }
            first = false;
            value(element);
        }
        if (pretty) {
            out.append(' ');
        }
        out.append(']');
        depth--;
    }

    private void enter() {
        if (depth == MAX_DEPTH) {
            throw new JsonException("document nests too deep", "max=" + MAX_DEPTH);
        }
        depth++;
    }

    private void newLine(final int level) {
        out.append(LINE_SEPARATOR);
        out.repeat(' ', 2 * level);
    }

    private void number(final NumericNode node) {
        switch (node) {
            case IntNode n -> out.append(n.value());
            case LongNode n -> out.append(n.value());
            case BigIntegerNode n -> out.append(n.value());
            case DoubleNode n -> floating(Double.isFinite(n.value()), Double.toString(n.value()));
            case FloatNode n -> floating(Float.isFinite(n.value()), Float.toString(n.value()));
            case DecimalNode n -> out.append(n.value());
        }
    }

    private void floating(final boolean finite, final String text) {
        if (finite) {
            out.append(text);
        } else {
            out.append('"').append(text).append('"');
        }
    }

    private void string(final String text) {
        out.append('"');
        escape(text, out);
        out.append('"');
    }

    private static void escape(final CharSequence text, final StringBuilder into) {
        final int length = text.length();
        int start = 0;
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c >= FIRST_PRINTABLE && c != '"' && c != '\\') {
                continue;
            }
            into.append(text, start, i).append('\\');
            switch (c) {
                case '"', '\\' -> into.append(c);
                case '\b' -> into.append('b');
                case '\t' -> into.append('t');
                case '\n' -> into.append('n');
                case '\f' -> into.append('f');
                case '\r' -> into.append('r');
                default -> into.append("u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
            start = i + 1;
        }
        into.append(text, start, length);
    }
}
