package dev.marregui.comando.ui.widgets.editor;

import java.awt.Component;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.schema.Schema;

import static org.assertj.core.api.Assertions.assertThat;

/// [SqlCompletion] offers SQL words from the bound connection's schema in the panel editor.
/// - offers keywords, tables and each column once, naming every table that has it
/// - a schema name like HTML is listed as text
/// - another schema replaces the words
/// - a disposed editor leaves no global listener
/// - completion opens on a statement being typed, not on a prompt
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
        assertThat(c.provider().getCompletionByInputText("trades")).hasSize(1);
        assertThat(c.provider().getCompletionByInputText("ts")).hasSize(1);
        assertThat(c.provider().getCompletionByInputText("SELECT")).hasSize(1);
        assertThat(SqlCompletion.columns(SCHEMA)).containsEntry("ts", "TIMESTAMP in trades, quotes").containsEntry("bid", "DOUBLE in quotes");
    }

    /// A table and a column named with HTML tags are shown escaped in the completion list.
    @Test
    void aTableNamedLikeHtmlIsListedAsText() throws Exception {
        final SqlCompletion c = new SqlCompletion();
        c.setSchema(new Schema(List.of(new Schema.Table("<img src=http://h/x.png>", List.of(new Schema.Column("<u>c", "INT"))))));
        final String[] shown = new String[2];
        SwingUtilities.invokeAndWait(() -> {
            final JList<Object> list = new JList<>();
            shown[0] = text(c.renderer().getListCellRendererComponent(list, c.provider().getCompletionByInputText("<img src=http://h/x.png>").getFirst(), 0, false, false));
            shown[1] = text(c.renderer().getListCellRendererComponent(list, c.provider().getCompletionByInputText("<u>c").getFirst(), 1, false, false));
        });
        assertThat(shown[0]).contains("&lt;img src=http://h/x.png&gt;").doesNotContain("<img");
        assertThat(shown[1]).contains("&lt;u&gt;c").doesNotContain("<u>");
    }

    private static String text(final Component c) {
        return ((JLabel) c).getText();
    }

    /// Setting an empty schema removes the previous tables and keeps the keywords.
    @Test
    void anotherSchemaReplacesTheWords() {
        final SqlCompletion c = new SqlCompletion();
        c.setSchema(SCHEMA);
        c.setSchema(Schema.EMPTY);
        assertThat(c.provider().getCompletionByInputText("trades")).isNull();
        assertThat(c.provider().getCompletionByInputText("FROM")).hasSize(1);
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
