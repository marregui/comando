package dev.marregui.comando.core.json;

import java.io.IOException;
import java.math.BigInteger;

/**
 * Reads one JSON document (RFC 8259) into a tree, as jackson-databind 3.2.4's {@code readTree} does with its defaults:
 * integers as the narrowest of {@code int}, {@code long} and big integer, other numbers as {@code double}; a property
 * repeated keeps its first place and its last value; no comments, no trailing commas, no leading zeros. A number or a
 * literal at the root must end at whitespace or at the end of the input, there as here.
 */
final class JsonParser {

    /** jackson-core 3.2.4's StreamReadConstraints DEFAULT_MAX_DEPTH: deeper containers are refused. */
    static final int MAX_DEPTH = 500;
    /**
     * Its DEFAULT_MAX_NUM_LEN: the integer, fraction and exponent digits a number may have together, counted as its
     * parser counts a number that ends inside its buffer.
     */
    static final int MAX_NUMBER_LENGTH = 1_000;
    /** Its DEFAULT_MAX_STRING_LEN: the characters a string value may have. */
    static final int MAX_STRING_LENGTH = 100_000_000;
    /** Its DEFAULT_MAX_NAME_LEN: the characters a property name may have. */
    static final int MAX_NAME_LENGTH = 50_000;
    private static final int BUFFER_CHARS = 8_192;
    private static final int MAX_INT_DIGITS = 9;
    private static final int MAX_LONG_DIGITS = 18;
    private static final int LONG_BITS = 63;
    private static final int HEX_DIGITS = 4;
    private static final int FIRST_PRINTABLE = 0x20;
    private final JsonInput input;
    private final char[] buffer = new char[BUFFER_CHARS];
    private final StringBuilder text = new StringBuilder();
    private int position;
    private int limit;
    /** The characters before {@code buffer[0]}. */
    private long offset;
    private long line = 1L;
    /** The input index of the current line's first character. */
    private long lineStart;
    private int depth;

    private JsonParser(final JsonInput input) {
        this.input = input;
    }

    /**
     * The document {@code input} holds, or the missing node when it holds only whitespace. {@code strict} refuses
     * anything but whitespace after the document; otherwise what follows is not read.
     *
     * @throws JsonException when the input is not JSON, or cannot be read
     */
    static JsonNode parse(final JsonInput input, final boolean strict) {
        final JsonParser parser = new JsonParser(input);
        final int first = parser.skipWhitespace();
        if (first < 0) {
            return MissingNode.INSTANCE;
        }
        final JsonNode root = parser.value(first);
        if (strict) {
            final int next = parser.skipWhitespace();
            if (next >= 0) {
                throw parser.failHere("unexpected content after the document", "character=" + (char) next);
            }
        }
        return root;
    }

    private JsonNode value(final int c) {
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> {
                position++;
                yield StringNode.valueOf(string(false));
            }
            case 't' -> literal("true", BooleanNode.TRUE);
            case 'f' -> literal("false", BooleanNode.FALSE);
            case 'n' -> literal("null", NullNode.INSTANCE);
            case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
            default -> throw unexpected(c, "a value");
        };
    }

    private ObjectNode object() {
        enter();
        final ObjectNode node = new ObjectNode();
        int c = skipWhitespace();
        if (c == '}') {
            position++;
            depth--;
            return node;
        }
        while (true) {
            if (c != '"') {
                throw unexpected(c, "a property name");
            }
            position++;
            final String name = string(true);
            c = skipWhitespace();
            if (c != ':') {
                throw unexpected(c, "a colon");
            }
            position++;
            node.set(name, value(skipWhitespace()));
            c = skipWhitespace();
            if (c == ',') {
                position++;
                c = skipWhitespace();
            } else if (c == '}') {
                position++;
                depth--;
                return node;
            } else {
                throw unexpected(c, "a comma or the end of the object");
            }
        }
    }

    private ArrayNode array() {
        enter();
        final ArrayNode node = new ArrayNode();
        int c = skipWhitespace();
        if (c == ']') {
            position++;
            depth--;
            return node;
        }
        while (true) {
            node.add(value(c));
            c = skipWhitespace();
            if (c == ',') {
                position++;
                c = skipWhitespace();
            } else if (c == ']') {
                position++;
                depth--;
                return node;
            } else {
                throw unexpected(c, "a comma or the end of the array");
            }
        }
    }

    /** Steps over the opening bracket at the current character into one more level of nesting. */
    private void enter() {
        if (depth == MAX_DEPTH) {
            throw failHere("document nests too deep", "max=" + MAX_DEPTH);
        }
        depth++;
        position++;
    }

    /**
     * The literal {@code word} at the current character. As in jackson-core, it must not run on into a character that
     * could continue a Java identifier, other than one below {@code '0'}.
     */
    private JsonNode literal(final String word, final JsonNode node) {
        for (int i = 0; i < word.length(); i++) {
            final int c = peek();
            if (c != word.charAt(i)) {
                throw unexpected(c, word);
            }
            position++;
        }
        final int next = peek();
        if (next >= '0' && next != ']' && next != '}' && Character.isJavaIdentifierPart((char) next)) {
            throw unexpected(next, "the end of " + word);
        }
        return node;
    }

    private NumericNode number() {
        text.setLength(0);
        final long start = offset + position;
        int c = peek();
        if (c == '-') {
            text.append('-');
            position++;
            c = peek();
        }
        if (!isDigit(c)) {
            throw unexpected(c, "a digit");
        }
        int integerDigits = 0;
        // a lone leading zero does not count toward the length bound, as jackson-core does not count it
        boolean leadingZero = false;
        if (c == '0') {
            text.append('0');
            position++;
            integerDigits = 1;
            leadingZero = true;
            c = peek();
            if (isDigit(c)) {
                throw failHere("number has a leading zero", "character=" + (char) c);
            }
        } else {
            while (isDigit(c)) {
                text.append((char) c);
                position++;
                integerDigits++;
                c = peek();
            }
        }
        int fractionDigits = 0;
        if (c == '.') {
            text.append('.');
            position++;
            c = peek();
            while (isDigit(c)) {
                text.append((char) c);
                position++;
                fractionDigits++;
                c = peek();
            }
            if (fractionDigits == 0) {
                throw unexpected(c, "a digit of the fraction");
            }
        }
        int exponentDigits = 0;
        final boolean exponent = c == 'e' || c == 'E';
        if (exponent) {
            text.append((char) c);
            position++;
            c = peek();
            if (c == '+' || c == '-') {
                text.append((char) c);
                position++;
                c = peek();
            }
            while (isDigit(c)) {
                text.append((char) c);
                position++;
                exponentDigits++;
                c = peek();
            }
            if (exponentDigits == 0) {
                throw unexpected(c, "a digit of the exponent");
            }
        }
        if (depth == 0 && c >= 0 && !isWhitespace(c)) {
            throw unexpected(c, "whitespace after a number at the root");
        }
        final boolean floating = fractionDigits > 0 || exponent;
        final int digits = (leadingZero ? 0 : integerDigits) + fractionDigits + exponentDigits;
        if (digits > MAX_NUMBER_LENGTH) {
            throw failAt(start, "number is too long", "digits=" + digits + ", max=" + MAX_NUMBER_LENGTH);
        }
        final String literal = text.toString();
        if (floating) {
            return new DoubleNode(Double.parseDouble(literal));
        }
        if (integerDigits <= MAX_INT_DIGITS) {
            return IntNode.valueOf(Integer.parseInt(literal));
        }
        if (integerDigits <= MAX_LONG_DIGITS) {
            final long value = Long.parseLong(literal);
            return value == (int) value ? IntNode.valueOf((int) value) : new LongNode(value);
        }
        final BigInteger value = new BigInteger(literal);
        return value.bitLength() <= LONG_BITS ? new LongNode(value.longValue()) : new BigIntegerNode(value);
    }

    /**
     * The rest of a string whose opening quote is behind, up to and past its closing quote. In a property name, as in
     * jackson-core 3.2.4, an escaped surrogate must be half of an escaped pair; a string value takes it alone.
     *
     * @param name whether the string is a property name, which is shorter and checks its escaped surrogates
     */
    private String string(final boolean name) {
        final int max = name ? MAX_NAME_LENGTH : MAX_STRING_LENGTH;
        final String what = name ? "property name" : "string";
        text.setLength(0);
        while (true) {
            if (position == limit && !fill()) {
                throw failHere("ran out of input in a " + what, "");
            }
            final int start = position;
            while (position < limit) {
                final char c = buffer[position];
                if (c == '"' || c == '\\' || c < FIRST_PRINTABLE) {
                    break;
                }
                position++;
            }
            text.append(buffer, start, position - start);
            if (text.length() > max) {
                throw failHere(what + " is too long", "max=" + max);
            }
            if (position < limit) {
                final char c = buffer[position];
                if (c == '"') {
                    position++;
                    return text.toString();
                }
                if (c != '\\') {
                    throw failHere("unescaped control character in a " + what, "character=" + c);
                }
                position++;
                final char escaped = escape(what);
                if (name && Character.isSurrogate(escaped)) {
                    text.append(escaped).append(lowSurrogate(escaped));
                } else {
                    text.append(escaped);
                }
            }
        }
    }

    /** The low half of the escaped pair in a property name whose escaped high half {@code high} is behind. */
    private char lowSurrogate(final char high) {
        if (Character.isLowSurrogate(high)) {
            throw failHere("unpaired low surrogate in a property name", "code=" + Integer.toHexString(high));
        }
        final int next = peek();
        if (next != '\\') {
            throw unexpected(next, "the escaped low surrogate after a high surrogate");
        }
        position++;
        final char low = escape("property name");
        if (!Character.isLowSurrogate(low)) {
            throw failHere("broken surrogate pair in a property name", "code=" + Integer.toHexString(low));
        }
        return low;
    }

    /** The character the escape after a backslash stands for, consumed. */
    private char escape(final String what) {
        final int c = peek();
        if (c < 0) {
            throw failHere("ran out of input in a " + what, "");
        }
        position++;
        return switch (c) {
            case '"', '\\', '/' -> (char) c;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> {
                int code = 0;
                for (int i = 0; i < HEX_DIGITS; i++) {
                    final int digit = Character.digit(peek(), 16);
                    if (digit < 0 || peek() > 'f') {
                        throw unexpected(peek(), "a hex digit");
                    }
                    code = code << 4 | digit;
                    position++;
                }
                yield (char) code;
            }
            default -> {
                position--;
                throw failHere("unrecognized escape in a " + what, "character=" + (char) c);
            }
        };
    }

    /** The next character not JSON whitespace, not consumed, or -1 at the end of the input. */
    private int skipWhitespace() {
        while (true) {
            if (position == limit && !fill()) {
                return -1;
            }
            final char c = buffer[position];
            if (c == '\n') {
                position++;
                line++;
                lineStart = offset + position;
            } else if (c == ' ' || c == '\t' || c == '\r') {
                position++;
            } else {
                return c;
            }
        }
    }

    /** The current character, not consumed, or -1 at the end of the input. */
    private int peek() {
        if (position == limit && !fill()) {
            return -1;
        }
        return buffer[position];
    }

    /** Whether the next buffer of input was read; false at its end. */
    private boolean fill() {
        offset += limit;
        position = 0;
        limit = 0;
        final int read;
        try {
            read = input.read(buffer);
        } catch (final IOException e) {
            throw new JsonException("cannot read JSON input", "", e);
        }
        if (read <= 0) {
            return false;
        }
        limit = read;
        return true;
    }

    /** The failure of a character, or of the end of the input, where {@code expected} should be. */
    private JsonException unexpected(final int c, final String expected) {
        if (c < 0) {
            return failHere("ran out of input", "expected=" + expected);
        }
        return failHere("unexpected character", "character=" + (char) c + ", expected=" + expected);
    }

    private JsonException failHere(final String what, final String details) {
        return failAt(offset + position, what, details);
    }

    private JsonException failAt(final long at, final String what, final String details) {
        return new JsonException(what, details, line, at - lineStart + 1L);
    }

    private static boolean isDigit(final int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isWhitespace(final int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
