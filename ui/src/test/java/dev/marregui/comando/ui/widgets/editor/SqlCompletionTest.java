package dev.marregui.comando.ui.widgets.editor;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.UIManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [SqlCompletion] offers SQL words from the bound connection's schema in the panel editor.
/// - offers keywords, tables and each column once, naming every table that has it
/// - a schema name like HTML is listed as text
/// - a large schema is set in one sort
/// - another schema replaces the words
/// - a disposed editor leaves no global listener
/// - completion opens on a statement being typed, not on a prompt
@ExtendWith(OnEdt.class)
class SqlCompletionTest {

    private static final Schema SCHEMA = new Schema(List.of(
            new Schema.Table("trades", List.of(new Schema.Column("ts", "TIMESTAMP"), new Schema.Column("price", "DOUBLE"))),
            new Schema.Table("quotes", List.of(new Schema.Column("ts", "TIMESTAMP"), new Schema.Column("bid", "DOUBLE")))));

    /// A table, a column shared by two tables and a keyword each give one completion; the column's description names
    /// its type and both tables.
    @Test
    void offersKeywordsTablesAndEachColumnOnce() {
        final SqlCompletion c = new SqlCompletion();
        c.setSchema(SCHEMA);
        assertThat(c.words().named("trades")).hasSize(1);
        assertThat(c.words().named("ts")).hasSize(1);
        assertThat(c.words().named("SELECT")).hasSize(1);
        assertThat(SqlCompletion.columns(SCHEMA)).containsEntry("ts", "TIMESTAMP in trades, quotes").containsEntry("bid", "DOUBLE in quotes");
    }

    /// A table and a column named with HTML tags are shown escaped in the completion list.
    @Test
    void aTableNamedLikeHtmlIsListedAsText() {
        final SqlCompletion c = new SqlCompletion();
        c.setSchema(new Schema(List.of(new Schema.Table("<img src=http://h/x.png>", List.of(new Schema.Column("<u>c", "INT"))))));
        final JList<Completion> list = c.popup().list();
        final String[] shown = {
            text(list.getCellRenderer().getListCellRendererComponent(
                    list, c.words().named("<img src=http://h/x.png>").getFirst(), 0, false, false)),
            text(list.getCellRenderer().getListCellRendererComponent(
                    list, c.words().named("<u>c").getFirst(), 1, false, false)),
        };
        assertThat(shown[0]).contains("&lt;img src=http://h/x.png&gt;").doesNotContain("<img");
        assertThat(shown[1]).contains("&lt;u&gt;c").doesNotContain("<u>");
    }

    private static String text(final Component c) {
        return ((JLabel) c).getText();
    }

    /// A schema of 1,000 tables of 20 columns each, all names distinct, gives every table and column one completion and
    /// is set in far less than the EDT's 200 ms budget allows ten times over: the completions are sorted once, not once
    /// per name added (5.8 s when each addition sorted the list).
    @Test
    void aLargeSchemaIsSetInOneSort() {
        final List<Schema.Table> tables = new ArrayList<>();
        for (int t = 0; t < 1_000; t++) {
            final List<Schema.Column> columns = new ArrayList<>();
            for (int c = 0; c < 20; c++) {
                columns.add(new Schema.Column("c" + t + "_" + c, "DOUBLE"));
            }
            tables.add(new Schema.Table("t" + t, columns));
        }
        final SqlCompletion c = new SqlCompletion();
        final long start = System.nanoTime();
        c.setSchema(new Schema(tables));
        final long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(c.words().named("t999")).hasSize(1);
        assertThat(c.words().named("c999_19")).hasSize(1);
        assertThat(c.words().named("SELECT")).hasSize(1);
        assertThat(c.words().size()).isEqualTo(SqlCompletion.KEYWORDS.size() + 1_000 + 20_000);
        assertThat(millis).as("ms to set 21,000 names").isLessThan(2_000);
    }

    /// Setting an empty schema removes the previous tables and keeps the keywords.
    @Test
    void anotherSchemaReplacesTheWords() {
        final SqlCompletion c = new SqlCompletion();
        c.setSchema(SCHEMA);
        c.setSchema(Schema.EMPTY);
        assertThat(c.words().named("trades")).isEmpty();
        assertThat(c.words().named("FROM")).hasSize(1);
    }

    /// A [CommandEditor] adds a `UIManager` listener and removes it on unmount.
    @Test
    void anUnmountedEditorLeavesNoGlobalListener() {
        final int before = UIManager.getPropertyChangeListeners().length;
        final CommandEditor editor = new CommandEditor();
        assertThat(UIManager.getPropertyChangeListeners().length).isGreaterThan(before);
        editor.unmount();
        assertThat(UIManager.getPropertyChangeListeners().length).isEqualTo(before);
    }

    /// [SqlCompletion#startsAStatement] accepts a leading SQL keyword, or a prefix of it of three letters or more,
    /// written all in lower or all in upper case and followed by at most one word, the word its grammar requires, or a
    /// mark of SQL; a mixed-case word such as `Show` and prose that starts with a SQL verb are rejected.
    @Test
    void completionOpensOnAStatementBeingTypedNotOnAPrompt() {
        assertThat(SqlCompletion.startsAStatement("update tr")).isTrue();
        assertThat(SqlCompletion.startsAStatement("UPDATE tr")).isTrue();
        assertThat(SqlCompletion.startsAStatement("sel")).isTrue();
        assertThat(SqlCompletion.startsAStatement("Show")).isFalse();
        assertThat(SqlCompletion.startsAStatement("create a unit test for Lane")).isFalse();
        assertThat(SqlCompletion.startsAStatement("delete stale rows in trades")).isFalse();
        assertThat(SqlCompletion.startsAStatement("update dependencies and rerun tests")).isFalse();
        assertThat(SqlCompletion.startsAStatement("select * From trades t where t.")).isTrue();
        assertThat(SqlCompletion.startsAStatement("select sym from trad")).isTrue();
        // lower-case prose with a mark of SQL in it: the word after the keyword is not the one its grammar requires
        for (final String prose : new String[]{"drop old rows from trades", "delete rows from trades older than a week",
                "update dependencies, rerun tests", "create 3 tests for Lane", "update readme.md and commit", "update tests for ENG-1234"}) {
            assertThat(SqlCompletion.startsAStatement(prose)).as(prose).isFalse();
        }
        for (final String sql : new String[]{"create table t", "create ta", "drop table trades", "drop all tables", "truncate table x",
                "insert into trades", "insert int", "delete from trades where", "update trades set price = 1", "update trades t set",
                "update trades s", "create materialized view v", "alter table trades add column"}) {
            assertThat(SqlCompletion.startsAStatement(sql)).as(sql).isTrue();
        }
    }
}
