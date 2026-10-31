package dev.marregui.comando.core.dispatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a block of SQL into its statements at the semicolons outside quotes ({@code '…'}, {@code "…"}), PostgreSQL
 * escape strings ({@code E'…\'…'}), dollar quotes ({@code $$…$$}, {@code $tag$…$tag$}) and comments ({@code --…},
 * {@code /* … *}{@code /}), so the count matches the statements the server runs. A statement that holds only comments
 * or blanks is dropped.
 */
public final class Statements {

    private Statements() {
    }

    /** The statements of {@code sql}, stripped, in order; empty for a blank or comment-only block. */
    public static List<String> split(final String sql) {
        final List<String> out = new ArrayList<>();
        if (sql == null) {
            return out;
        }
        final int n = sql.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            final char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                final boolean escapes = c == '\'' && isEscapePrefix(sql, i);
                int j = i + 1;
                while (j < n) {
                    if (escapes && sql.charAt(j) == '\\') {
                        j += 2; // a backslash escape inside E'…'
                        continue;
                    }
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
            } else if (c == '$') {
                final int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd < 0) {
                    i++;
                } else {
                    final int close = sql.indexOf(sql.substring(i, tagEnd), tagEnd);
                    i = close < 0 ? n : close + tagEnd - i;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                final int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                final int end = sql.indexOf("*/", i + 2);
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

    /** An {@code E} or {@code e} right before the quote at {@code quote} that starts a word: an escape string. */
    private static boolean isEscapePrefix(final String sql, final int quote) {
        if (quote < 1) {
            return false;
        }
        final char e = sql.charAt(quote - 1);
        return (e == 'E' || e == 'e') && (quote < 2 || !isIdentifierPart(sql.charAt(quote - 2)));
    }

    /**
     * The index after the opening dollar quote at {@code at} ({@code $$} or {@code $tag$}, the tag an identifier
     * that does not start with a digit), or -1: {@code $1} is a parameter and {@code a$b} an identifier.
     */
    private static int dollarTagEnd(final String sql, final int at) {
        if (at > 0 && isIdentifierPart(sql.charAt(at - 1))) {
            return -1;
        }
        int j = at + 1;
        if (j < sql.length() && (Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
            while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                j++;
            }
        }
        return j < sql.length() && sql.charAt(j) == '$' ? j + 1 : -1;
    }

    private static boolean isIdentifierPart(final char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static void add(final List<String> out, final String statement) {
        if (!Classifier.isCommentOnly(statement)) {
            out.add(statement.strip());
        }
    }
}
