package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.core.schema.Schema;
import org.junit.jupiter.api.Test;

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
    void anotherSchemaReplacesTheWords() {
        SqlCompletion c = new SqlCompletion();
        c.setSchema(SCHEMA);
        c.setSchema(Schema.EMPTY);
        assertThat(c.provider().getCompletionByInputText("trades")).isNull();
        assertThat(c.provider().getCompletionByInputText("FROM")).hasSize(1);
    }

    @Test
    void aDisposedEditorLeavesNoGlobalListener() {
        int before = javax.swing.UIManager.getPropertyChangeListeners().length;
        CommandEditor editor = new CommandEditor();
        assertThat(javax.swing.UIManager.getPropertyChangeListeners().length).isGreaterThan(before);
        editor.dispose();
        assertThat(javax.swing.UIManager.getPropertyChangeListeners().length).isEqualTo(before);
    }
}
