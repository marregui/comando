package dev.marregui.comando.ui.widgets.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.schema.Schema;

/**
 * Completion for the command editor: SQL keywords, then the tables and columns of the bound connection's
 * schema. A column found in several tables is offered once, with the tables it belongs to. It pops up on its
 * own only in a block that reads as SQL or starts like a statement being typed, so prose for Claude seldom opens
 * it; ⌃Space or ⌥⎋ asks anywhere. EDT only.
 */
final class SqlCompletion {

    static final List<String> KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "LIMIT", "JOIN", "LEFT JOIN", "ASOF JOIN", "LT JOIN", "SPLICE JOIN",
            "ON", "AS", "AND", "OR", "NOT", "IN", "BETWEEN", "LIKE", "ILIKE", "IS NULL", "IS NOT NULL", "DISTINCT", "COUNT", "SUM",
            "AVG", "MIN", "MAX", "FIRST", "LAST", "SAMPLE BY", "FILL", "ALIGN TO CALENDAR", "LATEST ON", "PARTITION BY", "WITH",
            "UNION", "UNION ALL", "EXCEPT", "INTERSECT", "CASE", "WHEN", "THEN", "ELSE", "END", "CAST", "INSERT INTO", "VALUES",
            "UPDATE", "SET", "DELETE", "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "TRUNCATE TABLE", "TIMESTAMP", "SHOW TABLES",
            "SHOW COLUMNS FROM", "EXPLAIN", "now()", "dateadd", "datediff", "to_timestamp", "long_sequence", "tables()");

    /** First words of a statement; a block that is only the start of one of these may still become SQL. */
    private static final List<String> LEADING = List.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP",
            "TRUNCATE", "EXPLAIN", "SHOW");

    private final Completions words = new Completions();
    private final CompletionPopup popup = new CompletionPopup(words);
    private Schema shown;

    SqlCompletion() {
        setSchema(Schema.EMPTY);
    }

    /** Removes the completion and its listeners, the global one (look and feel) too; idempotent. */
    void uninstall() {
        popup.uninstall();
    }

    /** Installs on {@code area}; {@code inSql} says whether the caret's block reads as SQL. */
    void install(final RSyntaxTextArea area, final BooleanSupplier inSql) {
        popup.install(area, inSql);
    }

    /**
     * A block being typed as SQL: its first word is a statement keyword, or the first three or more letters of one,
     * written all lower case or all upper case, and no English function word appears outside quotes and comments.
     * "UPDATE tr", "update tr" and "WITH x" qualify; "Show", "Explain" and "Update", capitalised as prose starts, do
     * not, nor does "show me the log".
     */
    private static final Pattern SQL_MARK = Pattern.compile("[*=<>(),;.']|\\d");
    private static final Set<String> CLAUSES = Set.of("FROM", "WHERE", "SET", "INTO", "TABLE", "VALUES", "JOIN",
            "GROUP", "ORDER", "LIMIT", "SAMPLE", "LATEST", "PARTITION", "SELECT", "DISTINCT", "HAVING", "UNION");

    /** The words one of which must follow a statement keyword; {@code UPDATE} is checked on its own. */
    private static final Map<String, Set<String>> NEXT = Map.of(
            "CREATE", Set.of("TABLE", "MATERIALIZED", "VIEW", "INDEX", "USER", "GROUP", "SERVICE", "OR", "UNIQUE", "TEMP",
                    "TEMPORARY", "SCHEMA", "DATABASE"),
            "ALTER", Set.of("TABLE", "MATERIALIZED", "VIEW", "INDEX", "USER", "GROUP", "SERVICE", "SCHEMA", "DATABASE"),
            "DROP", Set.of("TABLE", "ALL", "MATERIALIZED", "VIEW", "INDEX", "USER", "GROUP", "SERVICE", "SCHEMA", "DATABASE"),
            "TRUNCATE", Set.of("TABLE"),
            "INSERT", Set.of("INTO", "ATOMIC", "BATCH"),
            "DELETE", Set.of("FROM"));

    static boolean startsAStatement(final String block) {
        final String text = block.strip();
        int n = 0;
        while (n < text.length() && Character.isLetter(text.charAt(n))) {
            n++;
        }
        if (n < 3 || n < text.length() && !Character.isWhitespace(text.charAt(n)) && text.charAt(n) != ';' && text.charAt(n) != '(') {
            return false;
        }
        final String word = text.substring(0, n);
        final boolean oneCase = word.equals(word.toLowerCase(Locale.ROOT)) || word.equals(word.toUpperCase(Locale.ROOT));
        if (!oneCase) {
            return false;
        }
        final String upper = word.toUpperCase(Locale.ROOT);
        if (LEADING.stream().noneMatch(k -> k.startsWith(upper)) || !proseFree(text)) {
            return false;
        }
        // the keyword and one more word ("update tr", "create table") read as a statement being typed; a longer block
        // also needs a mark of SQL (an operator, a parenthesis, a digit, a clause keyword), which keeps lower-case
        // prose such as "delete stale rows in trades" out
        final String[] words = text.split("\\s+");
        final Boolean grammar = follows(upper, words);
        if (grammar != null) {
            return grammar;
        }
        return words.length <= 2 || SQL_MARK.matcher(text).find()
                || Arrays.stream(words).skip(1).anyMatch(w -> CLAUSES.contains(w.toUpperCase(Locale.ROOT)));
    }

    /**
     * Whether the words after a complete statement keyword are the ones its grammar requires ("drop table", "delete
     * from", "update trades set"), which lower-case prose ("drop old rows", "update dependencies, rerun tests") is not;
     * the word being typed, the last one, needs only to start one of them. Null when the keyword has no such word or
     * the block is too short to tell.
     */
    private static Boolean follows(final String keyword, final String[] words) {
        if (keyword.equals("UPDATE")) {
            if (words.length < 3) {
                return null;
            }
            // UPDATE table [alias] SET
            return isWord(words, 2, Set.of("SET")) || words.length > 3 && isWord(words, 3, Set.of("SET"));
        }
        final Set<String> next = NEXT.get(keyword);
        return next == null || words.length < 2 ? null : isWord(words, 1, next);
    }

    private static boolean isWord(final String[] words, final int i, final Set<String> allowed) {
        final String w = words[i].toUpperCase(Locale.ROOT);
        return i == words.length - 1 ? allowed.stream().anyMatch(a -> a.startsWith(w)) : allowed.contains(w);
    }

    /**
     * No English function word outside quotes and comments: the classifier's prose rule, reached through its public
     * API (the block behind a SELECT and before a semicolon is SQL exactly when it holds no prose word).
     */
    static boolean proseFree(final String text) {
        return Classifier.classify("SELECT " + text + "\n;").target() == Target.SQL;
    }

    void setSchema(final Schema schema) {
        if (schema == shown) {
            return;
        }
        shown = schema;
        // set in one call, which sorts once: one call per name would sort the whole list each time, seconds on the EDT
        // for a schema of thousands of columns
        final List<Completion> all = new ArrayList<>(KEYWORDS.size() + schema.tables().size());
        for (final String k : KEYWORDS) {
            all.add(new Completion(k, "keyword"));
        }
        for (final Schema.Table t : schema.tables()) {
            all.add(new Completion(t.name(), "table, " + t.columns().size() + " columns"));
        }
        for (final Map.Entry<String, String> c : columns(schema).entrySet()) {
            all.add(new Completion(c.getKey(), c.getValue()));
        }
        words.set(all);
    }

    /** Each column name once, described by its type and its tables. */
    static Map<String, String> columns(final Schema schema) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Schema.Table t : schema.tables()) {
            for (final Schema.Column c : t.columns()) {
                out.merge(c.name(), c.type() + " in " + t.name(), (a, b) -> a.length() > 80 ? a : a + ", " + t.name());
            }
        }
        return out;
    }

    /** The words offered. */
    @TestOnly
    Completions words() {
        return words;
    }

    /** The list that offers them. */
    @TestOnly
    CompletionPopup popup() {
        return popup;
    }
}
