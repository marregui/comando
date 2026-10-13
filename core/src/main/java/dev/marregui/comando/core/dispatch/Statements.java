package dev.marregui.comando.core.dispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a block of SQL into its statements at the semicolons outside quotes ({@code '…'}, {@code "…"}) and
 * comments ({@code --…}, {@code /* … *}{@code /}). A statement that holds only comments or blanks is dropped.
 */
public final class Statements {

    private Statements() {
    }

    /** The statements of {@code sql}, stripped, in order; empty for a blank or comment-only block. */
    public static List<String> split(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null) {
            return out;
        }
        int n = sql.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) {
                            j += 2; // a doubled quote inside the literal
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                i = Math.min(n, j + 1);
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == ';') {
                add(out, sql.substring(start, i));
                start = i + 1;
                i++;
            } else {
                i++;
            }
        }
        add(out, sql.substring(start));
        return out;
    }

    private static void add(List<String> out, String statement) {
        if (!Classifier.isCommentOnly(statement)) {
            out.add(statement.strip());
        }
    }
}
