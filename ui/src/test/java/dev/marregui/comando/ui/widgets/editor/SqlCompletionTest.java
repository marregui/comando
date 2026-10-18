package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.core.schema.Schema;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlCompletionTest {

    private static final Schema SCHEMA = new Schema(List.of(
            new Schema.Table("trades", List.of(new Schema.Column("ts", "TIMESTAMP"), new Schema.Column("price", "DOUBLE"))),
            new Schema.Table("quotes", List.of(new Schema.Column("ts", "TIMESTAMP"), new Schema.Column("bid", "DOUBLE")))));

    @Test
    void offersKeywordsTablesAndEachColumnOnce() {
        SqlCompletion c = new SqlCompletion();
        c.setSchema(SCHEMA);
        assertThat(c.provider().getCompletionByInputText("trades")).hasSize(1);
        assertThat(c.provider().getCompletionByInputText("ts")).hasSize(1);
        assertThat(c.provider().getCompletionByInputText("SELECT")).hasSize(1);
        assertThat(SqlCompletion.columns(SCHEMA)).containsEntry("ts", "TIMESTAMP in trades, quotes").containsEntry("bid", "DOUBLE in quotes");
    }

    @Test
    void aTableNamedLikeHtmlIsListedAsText() throws Exception {
        SqlCompletion c = new SqlCompletion();
        c.setSchema(new Schema(List.of(new Schema.Table("<img src=http://h/x.png>", List.of(new Schema.Column("<u>c", "INT"))))));
        String[] shown = new String[2];
        SwingUtilities.invokeAndWait(() -> {
            JList<Object> list = new JList<>();
            shown[0] = text(c.renderer().getListCellRendererComponent(list, c.provider().getCompletionByInputText("<img src=http://h/x.png>").getFirst(), 0, false, false));
            shown[1] = text(c.renderer().getListCellRendererComponent(list, c.provider().getCompletionByInputText("<u>c").getFirst(), 1, false, false));
        });
        assertThat(shown[0]).contains("&lt;img src=http://h/x.png&gt;").doesNotContain("<img");
        assertThat(shown[1]).contains("&lt;u&gt;c").doesNotContain("<u>");
    }

    private static String text(Component c) {
        return ((JLabel) c).getText();
    }

    @Test
    void anotherSchemaReplacesTheWords() {
        SqlCompletion c = new SqlCompletion();
        c.setSchema(SCHEMA);
        c.setSchema(Schema.EMPTY);
        assertThat(c.provider().getCompletionByInputText("trades")).isNull();
        assertThat(c.provider().getCompletionByInputText("FROM")).hasSize(1);
    }

    @Test
    void aDisposedEditorLeavesNoGlobalListener() {
        int before = UIManager.getPropertyChangeListeners().length;
        CommandEditor editor = new CommandEditor();
        assertThat(UIManager.getPropertyChangeListeners().length).isGreaterThan(before);
        editor.dispose();
        assertThat(UIManager.getPropertyChangeListeners().length).isEqualTo(before);
    }

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
        for (String prose : new String[]{"drop old rows from trades", "delete rows from trades older than a week",
                "update dependencies, rerun tests", "create 3 tests for Lane", "update readme.md and commit", "update tests for ENG-1234"}) {
            assertThat(SqlCompletion.startsAStatement(prose)).as(prose).isFalse();
        }
        for (String sql : new String[]{"create table t", "create ta", "drop table trades", "drop all tables", "truncate table x",
                "insert into trades", "insert int", "delete from trades where", "update trades set price = 1", "update trades t set",
                "update trades s", "create materialized view v", "alter table trades add column"}) {
            assertThat(SqlCompletion.startsAStatement(sql)).as(sql).isTrue();
        }
    }
}
