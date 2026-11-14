package dev.marregui.comando.cdb.sql;

import java.util.ArrayList;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;

/**
 * Splits SQL text into tokens, ending with one {@link Kind#END} token at the text's length. Whitespace, line comments
 * (from {@code --} to the end of the line) and block comments (from a slash-star to the next star-slash) separate
 * tokens and are dropped. A string's or a quoted identifier's token text is its value: the quotes removed, a doubled
 * quote read as one.
 */
final class Lexer {

    private static final String ONE_CHARACTER_SYMBOLS = "=<>+-*/%(),.;";
    private static final List<String> TWO_CHARACTER_SYMBOLS = List.of("<=", ">=", "!=", "<>", "||", "::");

    private final String sql;
    private final List<Token> tokens = new ArrayList<>();
    private int offset;

    private Lexer(final String sql) {
        this.sql = sql;
    }

    /** The tokens of {@code sql}; the last is {@link Kind#END}. */
    static List<Token> tokenize(final String sql) {
        final Lexer lexer = new Lexer(sql);
        lexer.run();
        return List.copyOf(lexer.tokens);
    }

    /** {@code c} as an error message shows it: itself when printable ASCII, {@code U+XXXX} otherwise. */
    static String printable(final char c) {
        return c >= ' ' && c < 0x7f ? String.valueOf(c) : String.format("U+%04X", (int) c);
    }

    private static boolean isIdentifierStart(final char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(final char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private void run() {
        while (true) {
            skipBlanks();
            if (offset >= sql.length()) {
                tokens.add(new Token(Kind.END, "", sql.length(), sql.length()));
                return;
            }
            tokens.add(next());
        }
    }

    private void skipBlanks() {
        while (offset < sql.length()) {
            final char c = sql.charAt(offset);
            if (Character.isWhitespace(c)) {
                offset++;
            } else if (sql.startsWith("--", offset)) {
                final int eol = sql.indexOf('\n', offset);
                offset = eol < 0 ? sql.length() : eol + 1;
            } else if (sql.startsWith("/*", offset)) {
                final int close = sql.indexOf("*/", offset + 2);
                if (close < 0) {
                    throw new CdbException("unterminated comment [position=" + offset + "]", offset);
                }
                offset = close + 2;
            } else {
                return;
            }
        }
    }

    private Token next() {
        final int start = offset;
        final char c = sql.charAt(offset);
        if (c == '\'') {
            return quoted(Kind.STRING, '\'', "string");
        }
        if (c == '"') {
            return quoted(Kind.QUOTED, '"', "identifier");
        }
        if (isIdentifierStart(c)) {
            offset++;
            while (offset < sql.length() && isIdentifierPart(sql.charAt(offset))) {
                offset++;
            }
            return new Token(Kind.WORD, sql.substring(start, offset), start, offset);
        }
        if (isDigit(c) || c == '.' && offset + 1 < sql.length() && isDigit(sql.charAt(offset + 1))) {
            return number();
        }
        return symbol();
    }

    private Token quoted(final Kind kind, final char quote, final String what) {
        final int start = offset;
        final StringBuilder value = new StringBuilder();
        offset++;
        while (offset < sql.length()) {
            final char c = sql.charAt(offset);
            offset++;
            if (c != quote) {
                value.append(c);
            } else if (offset < sql.length() && sql.charAt(offset) == quote) {
                value.append(quote);
                offset++;
            } else {
                return new Token(kind, value.toString(), start, offset);
            }
        }
        throw new CdbException("unterminated " + what + " [position=" + start + "]", start);
    }

    /** Digits with an optional fraction and exponent; an exponent needs a digit after its {@code e} and sign. */
    private Token number() {
        final int start = offset;
        skipDigits();
        if (offset < sql.length() && sql.charAt(offset) == '.') {
            offset++;
            skipDigits();
        }
        if (offset < sql.length() && (sql.charAt(offset) == 'e' || sql.charAt(offset) == 'E')) {
            int exponent = offset + 1;
            if (exponent < sql.length() && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) {
                exponent++;
            }
            if (exponent < sql.length() && isDigit(sql.charAt(exponent))) {
                offset = exponent;
                skipDigits();
            }
        }
        return new Token(Kind.NUMBER, sql.substring(start, offset), start, offset);
    }

    private void skipDigits() {
        while (offset < sql.length() && isDigit(sql.charAt(offset))) {
            offset++;
        }
    }

    private Token symbol() {
        final int start = offset;
        if (offset + 1 < sql.length()) {
            final String pair = sql.substring(offset, offset + 2);
            if (TWO_CHARACTER_SYMBOLS.contains(pair)) {
                offset += 2;
                return new Token(Kind.SYMBOL, pair, start, offset);
            }
        }
        final char c = sql.charAt(offset);
        if (ONE_CHARACTER_SYMBOLS.indexOf(c) < 0) {
            throw new CdbException("unexpected character [char=" + printable(c) + ", position=" + start + "]", start);
        }
        offset++;
        return new Token(Kind.SYMBOL, String.valueOf(c), start, offset);
    }

    /** What a token is. */
    enum Kind {
        /** An unquoted word: a keyword or an identifier, as written. */
        WORD,
        /** A double-quoted identifier; never a keyword. */
        QUOTED,
        /** A single-quoted string. */
        STRING,
        /** An unsigned number as written. */
        NUMBER,
        /** An operator or punctuation. */
        SYMBOL,
        /** The end of the text. */
        END
    }

    /** One token: {@code start} and {@code end} are its offsets in the text, the end exclusive. */
    record Token(Kind kind, String text, int start, int end) {
    }
}
