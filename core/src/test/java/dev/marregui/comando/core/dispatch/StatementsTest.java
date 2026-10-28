package dev.marregui.comando.core.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Statements] splits SQL only at executable semicolons and omits blank or comment-only segments.
///
/// - quoted strings and comments retain internal semicolons
/// - dollar quotes retain internal semicolons
/// - escape-string backslashes apply only after an `E` prefix
class StatementsTest {

    /// Semicolons outside literals and comments split statements, while null, whitespace, and comment-only segments yield no statement.
    @Test
    void splitsAtSemicolonsOutsideQuotesAndComments() {
        assertThat(Statements.split("insert into t values (';'); -- a; comment\nselect * from nope;"))
                .containsExactly("insert into t values (';')", "-- a; comment\nselect * from nope");
        assertThat(Statements.split("select 'it''s; fine' /* ; */ from t")).containsExactly("select 'it''s; fine' /* ; */ from t");
        assertThat(Statements.split("select 1;\n\n")).containsExactly("select 1");
        assertThat(Statements.split("-- only a comment;\n")).isEmpty();
        assertThat(Statements.split(null)).isEmpty();
    }

    /// PostgreSQL `$$` and `$tag$` bodies keep their semicolons, whereas parameters and identifier dollars do not start quoted bodies.
    @Test
    void semicolonsInsideDollarQuotesDoNotSplit() {
        assertThat(Statements.split("CREATE FUNCTION f() RETURNS int AS $$ SELECT 1; $$ LANGUAGE sql")).hasSize(1);
        assertThat(Statements.split("DO $body$ BEGIN PERFORM 1; PERFORM $$x;$$; END $body$; SELECT 2"))
                .containsExactly("DO $body$ BEGIN PERFORM 1; PERFORM $$x;$$; END $body$", "SELECT 2");
        assertThat(Statements.split("SELECT $1; SELECT a$b$ FROM t; SELECT 3")).hasSize(3);
    }

    /// A backslash escapes a quote only in an `E'...'` literal, so semicolons after standard strings still split statements.
    @Test
    void semicolonsInsideEscapeStringsDoNotSplit() {
        assertThat(Statements.split("SELECT E'it\\'s; x'")).containsExactly("SELECT E'it\\'s; x'");
        assertThat(Statements.split("SELECT e'a\\\\'; SELECT 2")).containsExactly("SELECT e'a\\\\'", "SELECT 2");
        // a backslash in a standard string is a plain character, and a word ending in E is no escape prefix
        assertThat(Statements.split("SELECT 'a\\'; SELECT type'x\\'; SELECT 3")).hasSize(3);
    }
}
