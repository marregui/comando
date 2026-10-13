package dev.marregui.comando.core.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementsTest {

    @Test
    void splitsAtSemicolonsOutsideQuotesAndComments() {
        assertThat(Statements.split("insert into t values (';'); -- a; comment\nselect * from nope;"))
                .containsExactly("insert into t values (';')", "-- a; comment\nselect * from nope");
        assertThat(Statements.split("select 'it''s; fine' /* ; */ from t")).containsExactly("select 'it''s; fine' /* ; */ from t");
        assertThat(Statements.split("select 1;\n\n")).containsExactly("select 1");
        assertThat(Statements.split("-- only a comment;\n")).isEmpty();
        assertThat(Statements.split(null)).isEmpty();
    }
}
