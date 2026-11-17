package dev.marregui.comando.cdb.sql;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;

/// Tokens of the lexer: kinds, values and offsets, and where its errors point.
class LexerTest {

    /// Two-character operators win over one-character ones; a doubled quote in a string is one quote.
    @Test
    void operatorsAndStrings() {
        assertThat(Lexer.tokenize("a<>b||'it''s'::x")).containsExactly(
                new Lexer.Token(Lexer.Kind.WORD, "a", 0, 1),
                new Lexer.Token(Lexer.Kind.SYMBOL, "<>", 1, 3),
                new Lexer.Token(Lexer.Kind.WORD, "b", 3, 4),
                new Lexer.Token(Lexer.Kind.SYMBOL, "||", 4, 6),
                new Lexer.Token(Lexer.Kind.STRING, "it's", 6, 13),
                new Lexer.Token(Lexer.Kind.SYMBOL, "::", 13, 15),
                new Lexer.Token(Lexer.Kind.WORD, "x", 15, 16),
                new Lexer.Token(Lexer.Kind.END, "", 16, 16));
    }

    /// A number stops before a unit letter; an exponent needs a digit, else the `e` is a word of its own.
    @Test
    void numbers() {
        assertThat(Lexer.tokenize("1h 2.5 3e-2 4e .5")).containsExactly(
                new Lexer.Token(Lexer.Kind.NUMBER, "1", 0, 1),
                new Lexer.Token(Lexer.Kind.WORD, "h", 1, 2),
                new Lexer.Token(Lexer.Kind.NUMBER, "2.5", 3, 6),
                new Lexer.Token(Lexer.Kind.NUMBER, "3e-2", 7, 11),
                new Lexer.Token(Lexer.Kind.NUMBER, "4", 12, 13),
                new Lexer.Token(Lexer.Kind.WORD, "e", 13, 14),
                new Lexer.Token(Lexer.Kind.NUMBER, ".5", 15, 17),
                new Lexer.Token(Lexer.Kind.END, "", 17, 17));
    }

    /// Digits may end in QuestDB's LONG suffix, either case, which the token's text leaves out.
    @Test
    void longSuffix() {
        assertThat(Lexer.tokenize("60000000L 7l)")).containsExactly(
                new Lexer.Token(Lexer.Kind.NUMBER, "60000000", 0, 9),
                new Lexer.Token(Lexer.Kind.NUMBER, "7", 10, 12),
                new Lexer.Token(Lexer.Kind.SYMBOL, ")", 12, 13),
                new Lexer.Token(Lexer.Kind.END, "", 13, 13));
    }

    /// The suffix after a fraction or an exponent, or followed by a letter or digit, fails at the number's start.
    @Test
    void longSuffixErrors() {
        assertThat(position("select 60000000Lx")).isEqualTo(7);
        assertThat(position("select 1.5L")).isEqualTo(7);
        assertThat(position("select 1e3L")).isEqualTo(7);
        assertThat(position("select 1L2")).isEqualTo(7);
    }

    /// Comments are dropped; a quoted identifier keeps its text, a doubled quote read as one.
    @Test
    void commentsAndQuotedIdentifiers() {
        assertThat(Lexer.tokenize("a -- x\n/* y */ \"Q\"\"t\"")).containsExactly(
                new Lexer.Token(Lexer.Kind.WORD, "a", 0, 1),
                new Lexer.Token(Lexer.Kind.QUOTED, "Q\"t", 15, 21),
                new Lexer.Token(Lexer.Kind.END, "", 21, 21));
    }

    /// Unterminated comments, strings and identifiers fail at their opening; an unknown character at itself.
    @Test
    void errorPositions() {
        assertThat(position("a /* x")).isEqualTo(2);
        assertThat(position("'abc")).isZero();
        assertThat(position("\"abc")).isZero();
        assertThat(position("a ? b")).isEqualTo(2);
    }

    private static int position(final String sql) {
        try {
            Lexer.tokenize(sql);
        } catch (final CdbException e) {
            return e.position();
        }
        throw new AssertionError("tokenized [sql=" + sql + "]");
    }
}
