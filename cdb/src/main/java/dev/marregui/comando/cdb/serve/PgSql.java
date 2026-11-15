package dev.marregui.comando.cdb.serve;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.marregui.comando.cdb.CdbException;

/**
 * The SQL text work of the PostgreSQL wire, done outside cdb's parser: a simple query split into statements, the
 * {@code $n} placeholders of an extended query counted and replaced by literals, and the session statements a driver
 * sends answered locally. Quoted strings ({@code '…'}), quoted identifiers ({@code "…"}) and comments ({@code --…},
 * {@code /*…*}{@code /}) are skipped: a {@code ;} or a {@code $1} inside them is text.
 */
final class PgSql {

    /** What {@code SELECT version()} answers; QuestDB 10.2 answers the same shape, naming itself. */
    static final String VERSION = "PostgreSQL 12.3, compiled by cdb, 64-bit";
    /** The protocol counts parameters in an int16, read unsigned; PostgreSQL refuses a placeholder past 65535 too. */
    static final int MAX_PARAMETERS = 65_535;
    // pgjdbc sends its ApplicationName this way after the startup, and reads it back from a ParameterStatus
    private static final Pattern APPLICATION_NAME =
            Pattern.compile("(?is)\\s*set\\s+(?:session\\s+)?application_name\\s*(?:=|to)\\s*'((?:[^']|'')*)'\\s*");

    private PgSql() {
    }

    /** The statements of {@code sql}, split on {@code ;} outside quotes and comments, stripped, empty ones dropped. */
    static List<String> split(final String sql) {
        final List<String> statements = new ArrayList<>();
        int start = 0;
        int at = 0;
        while (at < sql.length()) {
            final int skipped = skip(sql, at);
            if (skipped > at) {
                at = skipped;
            } else if (sql.charAt(at) == ';') {
                add(statements, sql.substring(start, at));
                start = ++at;
            } else {
                at++;
            }
        }
        add(statements, sql.substring(start));
        return statements;
    }

    /** The highest {@code $n} placeholder of {@code sql}, 0 when it has none. */
    static int parameterCount(final String sql) {
        int highest = 0;
        int at = 0;
        while (at < sql.length()) {
            final int skipped = skip(sql, at);
            if (skipped > at) {
                at = skipped;
                continue;
            }
            final int end = placeholderEnd(sql, at);
            if (end > at) {
                highest = Math.max(highest, index(sql, at, end));
                at = end;
            } else {
                at++;
            }
        }
        return highest;
    }

    /** {@code sql} with each {@code $n} replaced by {@code literals[n - 1]}; a placeholder past them is refused. */
    static String bind(final String sql, final String[] literals) {
        final StringBuilder out = new StringBuilder(sql.length() + 16 * literals.length);
        int at = 0;
        while (at < sql.length()) {
            final int skipped = skip(sql, at);
            if (skipped > at) {
                out.append(sql, at, skipped);
                at = skipped;
                continue;
            }
            final int end = placeholderEnd(sql, at);
            if (end > at) {
                final int n = index(sql, at, end);
                if (n < 1 || n > literals.length) {
                    throw new CdbException("unbound parameter [parameter=$" + n + ", bound=" + literals.length + ']',
                            at);
                }
                out.append(literals[n - 1]);
                at = end;
            } else {
                out.append(sql.charAt(at++));
            }
        }
        return out.toString();
    }

    /** The first word of {@code sql}, upper-cased; leading spaces, comments and parentheses are passed over. */
    static String keyword(final String sql) {
        int at = 0;
        while (at < sql.length()) {
            final char c = sql.charAt(at);
            final int skipped = c == '-' || c == '/' ? skip(sql, at) : at;
            if (skipped > at) {
                at = skipped;
            } else if (Character.isWhitespace(c) || c == '(') {
                at++;
            } else {
                break;
            }
        }
        int end = at;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return sql.substring(at, end).toUpperCase(Locale.ROOT);
    }

    /**
     * The answer of a session statement a driver sends that cdb's parser does not take ({@code SET}, {@code BEGIN},
     * {@code COMMIT}, {@code ROLLBACK}, {@code DISCARD}, {@code RESET}, {@code DEALLOCATE}, {@code SHOW},
     * the {@code SHOW} of a session parameter, {@code SELECT version()}, {@code SELECT current_schema()}); null for a
     * statement cdb runs, any other {@code SHOW} ({@code SHOW TABLES}, {@code SHOW COLUMNS FROM t}) included.
     */
    static Local local(final String sql) {
        final String keyword = keyword(sql);
        return switch (keyword) {
            case "SET" -> set(sql);
            case "RESET", "DEALLOCATE", "LISTEN", "UNLISTEN" -> Local.command(keyword);
            case "BEGIN", "START" -> Local.command("BEGIN");
            case "COMMIT", "END" -> Local.command("COMMIT");
            case "ROLLBACK", "ABORT" -> Local.command("ROLLBACK");
            case "DISCARD" -> Local.command("DISCARD ALL");
            case "SHOW" -> show(sql);
            case "SELECT" -> select(sql);
            default -> null;
        };
    }

    /** Whether {@code sql} writes or changes the store, told by its first word: a describe of it plans nothing. */
    static boolean changesStore(final String sql) {
        return switch (keyword(sql)) {
            case "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COPY" -> true;
            default -> false;
        };
    }

    // SET application_name carries the name as the answer's value, to be reported back
    private static Local set(final String sql) {
        final Matcher m = APPLICATION_NAME.matcher(sql);
        return m.matches() ? new Local("SET", null, m.group(1).replace("''", "'")) : Local.command("SET");
    }

    // a session parameter's value; null for any other name, which cdb's own SHOW answers or refuses
    private static Local show(final String sql) {
        final String name = normalised(sql).substring("show".length()).strip();
        final String value = switch (name) {
            case "server_version" -> PgSession.SERVER_VERSION;
            case "server_version_num" -> PgSession.SERVER_VERSION_NUM;
            case "server_encoding", "client_encoding" -> "UTF8";
            case "datestyle" -> "ISO, MDY";
            case "integer_datetimes", "standard_conforming_strings" -> "on";
            case "timezone", "time zone" -> "UTC";
            case "transaction isolation level", "transaction_isolation" -> "read committed";
            case "search_path" -> "\"$user\", public";
            case "max_identifier_length" -> "63";
            case "is_superuser" -> "off";
            default -> null;
        };
        return value == null ? null : Local.row(name.replace(' ', '_'), value);
    }

    private static Local select(final String sql) {
        return switch (normalised(sql)) {
            case "select version()" -> Local.row("version", VERSION);
            case "select current_schema()" -> Local.row("current_schema", "public");
            default -> null;
        };
    }

    // lower-cased, runs of spaces as one, no space before a parenthesis
    private static String normalised(final String sql) {
        return sql.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replace(" (", "(");
    }

    private static void add(final List<String> statements, final String statement) {
        final String stripped = statement.strip();
        if (!stripped.isEmpty() && !onlyComments(stripped)) {
            statements.add(stripped);
        }
    }

    private static boolean onlyComments(final String text) {
        int at = 0;
        while (at < text.length()) {
            final char c = text.charAt(at);
            final int skipped = c == '-' || c == '/' ? skip(text, at) : at;
            if (skipped > at) {
                at = skipped;
            } else if (Character.isWhitespace(c)) {
                at++;
            } else {
                return false;
            }
        }
        return true;
    }

    // the end of the quoted text or comment that starts at `at`, or `at` when none starts there; an unclosed one runs
    // to the end of the text, where cdb's parser reports it
    private static int skip(final String sql, final int at) {
        final char c = sql.charAt(at);
        if (c == '\'' || c == '"') {
            int i = at + 1;
            while (i < sql.length()) {
                if (sql.charAt(i) == c) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == c) {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return sql.length();
        }
        if (c == '-' && at + 1 < sql.length() && sql.charAt(at + 1) == '-') {
            final int newline = sql.indexOf('\n', at);
            return newline < 0 ? sql.length() : newline + 1;
        }
        if (c == '/' && at + 1 < sql.length() && sql.charAt(at + 1) == '*') {
            final int close = sql.indexOf("*/", at + 2);
            return close < 0 ? sql.length() : close + 2;
        }
        return at;
    }

    // the end of the `$n` placeholder at `at`, or `at` when none is there; `$` inside a name (`a$1`) is not one
    private static int placeholderEnd(final String sql, final int at) {
        if (sql.charAt(at) != '$' || at + 1 >= sql.length() || !isDigit(sql.charAt(at + 1))) {
            return at;
        }
        if (at > 0) {
            final char before = sql.charAt(at - 1);
            if (Character.isLetterOrDigit(before) || before == '_' || before == '$') {
                return at;
            }
        }
        int end = at + 1;
        while (end < sql.length() && isDigit(sql.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int index(final String sql, final int at, final int end) {
        final int n;
        try {
            n = Integer.parseInt(sql, at + 1, end, 10);
        } catch (final NumberFormatException _) {
            throw new CdbException("refuse parameter over the limit [parameter=" + sql.substring(at, end) + ", limit="
                    + MAX_PARAMETERS + ']', at);
        }
        if (n > MAX_PARAMETERS) {
            throw new CdbException("refuse parameter over the limit [parameter=$" + n + ", limit=" + MAX_PARAMETERS
                    + ']', at);
        }
        return n;
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * A session statement's answer: its command tag, and for {@code SHOW} or a session function one row of one
     * VARCHAR column ({@code column} null for none); a {@code SET application_name} has no row and the name as its
     * {@code value}.
     */
    record Local(String tag, String column, String value) {

        static Local command(final String tag) {
            return new Local(tag, null, null);
        }

        static Local row(final String column, final String value) {
            return new Local("SELECT 1", column, value);
        }

        boolean hasRow() {
            return column != null;
        }
    }
}
