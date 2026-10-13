package dev.marregui.comando.mcp;

import java.util.Locale;
import java.util.Set;

/**
 * Which statements Claude may run without asking. A statement is read-only when its first keyword, after
 * comments, is one that cannot change data, and no word outside quotes and comments is one that writes or
 * locks: a data-modifying CTE ({@code WITH d AS (DELETE ...) SELECT ...}), {@code EXPLAIN ANALYZE} of a
 * write, {@code SELECT ... INTO} and {@code SELECT ... FOR UPDATE} are not read-only.
 *
 * <p>The check is lexical and errs towards "not read-only": a second statement after a semicolon, a
 * dollar sign outside quotes (dollar quoting), and a backslash inside a quoted string (escape syntax the
 * scanner cannot settle) each make a statement not read-only. A function with side effects, such as
 * {@code pg_terminate_backend} or {@code setval}, is not detected.
 */
final class SqlPolicy {

    private static final Set<String> READ_ONLY = Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "TABLES");

    private static final Set<String> WRITES = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "INTO", "CREATE", "DROP", "ALTER", "TRUNCATE",
            "GRANT", "REVOKE", "COPY", "CALL", "VACUUM", "REINDEX", "CLUSTER", "LOCK", "REFRESH", "NOTIFY", "SHARE");

    private SqlPolicy() {
    }

    static boolean isReadOnly(String sql) {
        String s = stripComments(sql).strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.isEmpty()) {
            return false;
        }
        boolean first = true;
        char quote = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    return false;
                }
                if (c == quote) {
                    quote = 0;
                }
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
                i++;
            } else if (c == ';' || c == '$') {
                return false;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                String word = s.substring(start, i).toUpperCase(Locale.ROOT);
                if (first ? !READ_ONLY.contains(word) : WRITES.contains(word)) {
                    return false;
                }
                first = false;
            } else if (first && !Character.isWhitespace(c)) {
                return false;
            } else {
                i++;
            }
        }
        return !first && quote == 0;
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        char quote = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
                out.append(c);
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? sql.length() : eol;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
