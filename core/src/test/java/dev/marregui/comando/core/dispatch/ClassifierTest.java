package dev.marregui.comando.core.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// [Classifier] routes an editor block to SQL or a prompt after applying markers, syntax shape, and prose safeguards.
///
/// - recognised statement shapes select SQL
/// - prose, commands, and ambiguous punctuation select a prompt
/// - marker lines override classification and are removed
/// - comment-only blocks produce empty SQL
class ClassifierTest {

    /// SQL-shaped statements, including comments and quoted prose, select SQL; English requests, slash commands, and
    /// question-ended text select a prompt.
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            SELECT * FROM trades                                         | SQL
            select count() from trades where symbol = 'BTC-USD'          | SQL
            SELECT 1                                                     | SQL
            WITH t AS (SELECT 1) SELECT * FROM t                         | SQL
            INSERT INTO t VALUES (1)                                     | SQL
            UPDATE t SET x = 1                                           | SQL
            DELETE FROM t WHERE x = 1                                    | SQL
            CREATE TABLE t (ts TIMESTAMP, x LONG) TIMESTAMP(ts)          | SQL
            ALTER TABLE t ADD COLUMN y DOUBLE                            | SQL
            DROP TABLE t                                                 | SQL
            TRUNCATE TABLE t                                             | SQL
            EXPLAIN SELECT * FROM t                                      | SQL
            SHOW TABLES                                                  | SQL
            -- daily volume\\nSELECT sum(amount) FROM trades SAMPLE BY 1d | SQL
            SELECT 'what is the price' FROM t                            | SQL
            SELECT * FROM my_table                                       | SQL
            SELECT i, x FROM t                                           | SQL
            SELECT a, /* the delay */ b FROM t                           | SQL
            SELECT can_id FROM orders                                    | SQL
            SELECT x FROM t1 i JOIN t2 j ON i.k = j.k                    | SQL
            SELECT ts, us_east FROM metrics SAMPLE BY 1m                 | SQL
            select what I need from the trades                           | CLAUDE
            update the README with the new flags                         | CLAUDE
            Update the docs.                                             | CLAUDE
            show me the log                                              | CLAUDE
            select the best option for me                                | CLAUDE
            create a test for the classifier                             | CLAUDE
            drop it                                                      | CLAUDE
            explain why the delay spikes every 300 s                     | CLAUDE
            SELECT * FROM trades?                                        | CLAUDE
            why is this slow                                             | CLAUDE
            go                                                           | CLAUDE
            /deep                                                        | CLAUDE
            /model sonnet                                                | CLAUDE
            don't drop table t                                           | CLAUDE
            """)
    void classifies(final String text, final Target expected) {
        assertThat(Classifier.classify(text.replace("\\n", "\n")).target()).isEqualTo(expected);
    }

    /// A first-line `-- @sql` or `-- @claude` overrides inferred routing and is absent from the dispatched text.
    @Test
    void markerLineWinsAndIsRemoved() {
        final Classifier.Classification sql = Classifier.classify("-- @sql\ntrades WHERE price > 10");
        assertThat(sql.target()).isEqualTo(Target.SQL);
        assertThat(sql.text()).isEqualTo("trades WHERE price > 10");

        final Classifier.Classification prompt = Classifier.classify("-- @claude\nSELECT * FROM t");
        assertThat(prompt.target()).isEqualTo(Target.CLAUDE);
        assertThat(prompt.text()).isEqualTo("SELECT * FROM t");
    }

    /// Removing no marker strips only surrounding whitespace, while removing a marker also removes its full first line.
    @Test
    void stripMarkerKeepsUnmarkedText() {
        assertThat(Classifier.stripMarker("  SELECT 1  ")).isEqualTo("SELECT 1");
        assertThat(Classifier.stripMarker("-- @sql\nSELECT 1")).isEqualTo("SELECT 1");
    }

    /// Prose words inside string literals and comments do not make a SQL statement a prompt, but unquoted prose does.
    @Test
    void proseWordsInsideQuotesDoNotCount() {
        assertThat(Classifier.hasProseWord("SELECT * FROM t WHERE note = 'the best'")).isFalse();
        assertThat(Classifier.hasProseWord("SELECT * FROM t -- the comment\nWHERE x = 1")).isFalse();
        assertThat(Classifier.hasProseWord("SELECT the best")).isTrue();
    }

    /// Blank and comment-only blocks classify as empty SQL, whereas a comment followed by a statement remains runnable.
    @Test
    void aBlockOfCommentsHasNothingToRunAndIsNoPrompt() {
        assertThat(Classifier.isCommentOnly("-- one\n/* two */\n-- three")).isTrue();
        assertThat(Classifier.isCommentOnly("-- one\nSELECT 1")).isFalse();
        assertThat(Classifier.classify("-- Welcome to comando.\n-- A block is a run of lines.").target()).isEqualTo(Target.SQL);
    }

    /// CREATE, ALTER, DROP, and TRUNCATE after comments are schema changes, while row writes and identifier prefixes
    /// are not.
    @Test
    void ddlIsTheStatementsThatChangeTheSchema() {
        assertThat(Classifier.isDdl("-- make it\ncreate table t (x int)")).isTrue();
        assertThat(Classifier.isDdl("ALTER TABLE t ADD COLUMN y INT")).isTrue();
        assertThat(Classifier.isDdl("INSERT INTO t VALUES (1)")).isFalse();
        assertThat(Classifier.isDdl("created_at")).isFalse();
    }
}
