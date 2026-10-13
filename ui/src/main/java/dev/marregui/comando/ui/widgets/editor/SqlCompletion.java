package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.core.schema.Schema;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Completion for the command editor: SQL keywords, then the tables and columns of the bound connection's
 * schema. A column found in several tables is offered once, with the tables it belongs to.
 */
final class SqlCompletion {

    static final List<String> KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "LIMIT", "JOIN", "LEFT JOIN", "ASOF JOIN", "LT JOIN", "SPLICE JOIN",
            "ON", "AS", "AND", "OR", "NOT", "IN", "BETWEEN", "LIKE", "ILIKE", "IS NULL", "IS NOT NULL", "DISTINCT", "COUNT", "SUM",
            "AVG", "MIN", "MAX", "FIRST", "LAST", "SAMPLE BY", "FILL", "ALIGN TO CALENDAR", "LATEST ON", "PARTITION BY", "WITH",
            "UNION", "UNION ALL", "EXCEPT", "INTERSECT", "CASE", "WHEN", "THEN", "ELSE", "END", "CAST", "INSERT INTO", "VALUES",
            "UPDATE", "SET", "DELETE", "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "TRUNCATE TABLE", "TIMESTAMP", "SHOW TABLES",
            "SHOW COLUMNS FROM", "EXPLAIN", "now()", "dateadd", "datediff", "to_timestamp", "long_sequence", "tables()");

    private final DefaultCompletionProvider provider = new DefaultCompletionProvider();
    private final AutoCompletion auto = new AutoCompletion(provider);
    private Schema shown;

    SqlCompletion() {
        provider.setAutoActivationRules(true, null);
        auto.setAutoActivationEnabled(true);
        auto.setAutoActivationDelay(400);
        auto.setAutoCompleteSingleChoices(false);
        setSchema(Schema.EMPTY);
    }

    /** Removes the completion and the listeners the library registered globally (look and feel). */
    void uninstall() {
        auto.uninstall();
    }

    void install(RSyntaxTextArea area) {
        auto.install(area);
    }

    void setSchema(Schema schema) {
        if (schema == shown) {
            return;
        }
        shown = schema;
        provider.clear();
        for (String k : KEYWORDS) {
            provider.addCompletion(new BasicCompletion(provider, k, "keyword"));
        }
        for (Schema.Table t : schema.tables()) {
            provider.addCompletion(new BasicCompletion(provider, t.name(), "table, " + t.columns().size() + " columns"));
        }
        for (Map.Entry<String, String> c : columns(schema).entrySet()) {
            provider.addCompletion(new BasicCompletion(provider, c.getKey(), c.getValue()));
        }
    }

    /** Each column name once, described by its type and its tables. */
    static Map<String, String> columns(Schema schema) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Schema.Table t : schema.tables()) {
            for (Schema.Column c : t.columns()) {
                out.merge(c.name(), c.type() + " in " + t.name(), (a, b) -> a.length() > 80 ? a : a + ", " + t.name());
            }
        }
        return out;
    }

    DefaultCompletionProvider provider() {
        return provider;
    }
}
