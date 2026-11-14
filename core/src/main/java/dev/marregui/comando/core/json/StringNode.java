package dev.marregui.comando.core.json;

/** A JSON string. {@code asInt}, {@code asLong} and {@code asDouble} parse its text when it looks like a number. */
final class StringNode extends JsonNode {

    /** The longest text {@code asDouble} parses: jackson-core 3.2.4's StreamReadConstraints DEFAULT_MAX_NUM_LEN. */
    private static final int MAX_NUMBER_LENGTH = 1_000;
    private static final StringNode EMPTY = new StringNode("");
    private final String value;

    private StringNode(final String value) {
        this.value = value;
    }

    /** The node for {@code value}, which must not be {@code null}; the empty string's node is shared. */
    static StringNode valueOf(final String value) {
        return value.isEmpty() ? EMPTY : new StringNode(value);
    }

    /** The text. */
    String value() {
        return value;
    }

    @Override
    public boolean isString() {
        return true;
    }

    @Override
    public String asString() {
        return value;
    }

    @Override
    public String asString(final String defaultValue) {
        return value;
    }

    @Override
    public String stringValue() {
        return value;
    }

    @Override
    public int asInt() {
        final Integer parsed = parseInt();
        if (parsed == null) {
            throw new JsonException("string is not an int", "accessor=asInt(), value=" + value);
        }
        return parsed;
    }

    @Override
    public int asInt(final int defaultValue) {
        final Integer parsed = parseInt();
        return parsed == null ? defaultValue : parsed;
    }

    @Override
    public long asLong() {
        final Long parsed = parseLong();
        if (parsed == null) {
            throw new JsonException("string is not a long", "accessor=asLong(), value=" + value);
        }
        return parsed;
    }

    @Override
    public long asLong(final long defaultValue) {
        final Long parsed = parseLong();
        return parsed == null ? defaultValue : parsed;
    }

    /** @throws JsonException also when the text looks like a number longer than 1,000 characters */
    @Override
    public double asDouble() {
        if (looksLikeNumber(value) && value.length() > MAX_NUMBER_LENGTH) {
            throw new JsonException("number is too long", "length=" + value.length() + ", max=" + MAX_NUMBER_LENGTH);
        }
        final Double parsed = parseDouble();
        if (parsed == null) {
            throw new JsonException("string is not a double", "accessor=asDouble(), value=" + value);
        }
        return parsed;
    }

    @Override
    public double asDouble(final double defaultValue) {
        if (value.length() > MAX_NUMBER_LENGTH) {
            return defaultValue;
        }
        final Double parsed = parseDouble();
        return parsed == null ? defaultValue : parsed;
    }

    @Override
    public StringNode deepCopy() {
        return this;
    }

    @Override
    public boolean equals(final Object other) {
        return other == this || other instanceof StringNode string && value.equals(string.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    String asStringOrNull() {
        return value;
    }

    @Override
    Boolean asBooleanOrNull() {
        return switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    private Integer parseInt() {
        if (!looksLikeNumber(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException _) {
            return null;
        }
    }

    private Long parseLong() {
        if (!looksLikeNumber(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException _) {
            return null;
        }
    }

    private Double parseDouble() {
        if (!looksLikeNumber(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (final NumberFormatException _) {
            return null;
        }
    }

    /**
     * Whether {@code text} is {@code [+-]?[0-9]*[.]?[0-9]+([eE][+-]?[0-9]+)?} or {@code [+-]?[0-9]+[.]}: the strings
     * jackson-core's NumberInput.looksLikeValidNumber lets through to a parse.
     */
    private static boolean looksLikeNumber(final String text) {
        final int length = text.length();
        int i = 0;
        if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        final int integerStart = i;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
        }
        final int integerDigits = i - integerStart;
        if (i == length) {
            return integerDigits > 0;
        }
        if (text.charAt(i) == '.') {
            i++;
            if (i == length) {
                return integerDigits > 0;
            }
            final int fractionStart = i;
            while (i < length && isDigit(text.charAt(i))) {
                i++;
            }
            if (i == fractionStart) {
                return false;
            }
        } else if (integerDigits == 0) {
            return false;
        }
        if (i == length) {
            return true;
        }
        if (text.charAt(i) != 'e' && text.charAt(i) != 'E') {
            return false;
        }
        i++;
        if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        final int exponentStart = i;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
        }
        return i > exponentStart && i == length;
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }
}
