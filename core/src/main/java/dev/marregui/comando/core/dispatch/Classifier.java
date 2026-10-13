package dev.marregui.comando.core.dispatch;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a block of editor text is SQL for the panel's connection or a prompt for its session.
 *
 * <p>Rules, in order: (a) a first line {@code -- @sql} or {@code -- @claude} wins and is removed;
 * (b) a leading {@code /} is a Claude command; (c) a leading SQL keyword followed by the shape that
 * keyword takes, with no English function word outside quotes and no closing {@code ?} or {@code .},
 * is SQL; (d) anything else is a prompt. Rule (c) is deliberately strict: "update the README" and
 * "show me the log" are prompts. A statement the classifier misses is forced with the SQL key.</p>
 */
public final class Classifier {

    public static final String SQL_MARKER = "-- @sql";
    public static final String CLAUDE_MARKER = "-- @claude";

    /** Shape required after each leading keyword. */
    private static final Map<String, Pattern> SHAPES = Map.ofEntries(
            shape("SELECT", "SELECT\\s+\\S"),
            shape("WITH", "WITH\\s+(RECURSIVE\\s+)?\\w+\\s+AS\\s*\\("),
            shape("INSERT", "INSERT\\s+(ATOMIC\\s+|BATCH\\s+\\d+\\s+)?INTO\\s"),
            shape("UPDATE", "UPDATE\\s+\\S+\\s+SET\\s"),
            shape("DELETE", "DELETE\\s+FROM\\s"),
            shape("CREATE", "CREATE\\s+(OR\\s+REPLACE\\s+)?(TABLE|INDEX|VIEW|MATERIALIZED|USER|GROUP|SERVICE|TYPE|SCHEMA|DATABASE|FUNCTION|EXTENSION|SEQUENCE)\\b"),
            shape("ALTER", "ALTER\\s+(TABLE|INDEX|VIEW|MATERIALIZED|USER|SERVICE|GROUP|SCHEMA|DATABASE|SEQUENCE)\\b"),
            shape("DROP", "DROP\\s+(TABLE|INDEX|VIEW|MATERIALIZED|USER|SERVICE|GROUP|SCHEMA|DATABASE|ALL|SEQUENCE|FUNCTION|TYPE)\\b"),
            shape("TRUNCATE", "TRUNCATE\\s+TABLE\\s"),
            shape("EXPLAIN", "EXPLAIN\\s+(ANALYZE\\s+)?(SELECT|WITH|INSERT|UPDATE|DELETE)\\b"),
            shape("SHOW", "SHOW\\s+\\w+"),
            shape("COPY", "COPY\\s+\\S+\\s+(FROM|TO)\\s"),
            shape("VACUUM", "VACUUM\\s+TABLE\\s"),
            shape("REINDEX", "REINDEX\\s+TABLE\\s"),
            shape("SET", "SET\\s+\\w+\\s*(=|TO\\b)"),
            shape("CANCEL", "CANCEL\\s+QUERY\\s"),
            shape("BACKUP", "BACKUP\\s+(TABLE|DATABASE)\\b"),
            shape("CHECKPOINT", "CHECKPOINT\\s+(CREATE|RELEASE)\\b"),
            shape("SNAPSHOT", "SNAPSHOT\\s+(PREPARE|COMPLETE)\\b"),
            shape("DECLARE", "DECLARE\\s+@"),
            shape("BEGIN", "BEGIN\\s*;?$"),
            shape("COMMIT", "COMMIT\\s*;?$"),
            shape("ROLLBACK", "ROLLBACK\\s*;?$"));

    /** English function words that are not SQL keywords; one outside quotes marks prose. */
    private static final Set<String> PROSE_WORDS = Set.of(
            "the", "me", "my", "you", "your", "we", "our", "us", "please", "why", "how", "what", "should",
            "could", "would", "can", "will", "it", "its", "this", "these", "those", "there", "here", "about",
            "i", "it's", "don't", "let's", "whether", "because");

    private static final Pattern LEADING_WORD = Pattern.compile("^([A-Za-z]+)");

    private Classifier() {
    }

    /** The target and the text to send, with any marker line removed. */
    public record Classification(Target target, String text) {
    }

    public static Classification classify(String raw) {
        String text = raw.strip();
        int eol = text.indexOf('\n');
        String firstLine = (eol < 0 ? text : text.substring(0, eol)).strip().toLowerCase(Locale.ROOT);
        if (firstLine.equals(SQL_MARKER)) {
            return new Classification(Target.SQL, eol < 0 ? "" : text.substring(eol + 1).strip());
        }
        if (firstLine.equals(CLAUDE_MARKER)) {
            return new Classification(Target.CLAUDE, eol < 0 ? "" : text.substring(eol + 1).strip());
        }
        if (text.length() > 1 && text.charAt(0) == '/' && Character.isLetter(text.charAt(1))) {
            return new Classification(Target.CLAUDE, text);
        }
        return new Classification(isSql(text) ? Target.SQL : Target.CLAUDE, text);
    }

    /** Removes a marker first line, if any, for text whose target is forced by a key. */
    public static String stripMarker(String raw) {
        String text = raw.strip();
        int eol = text.indexOf('\n');
        String firstLine = (eol < 0 ? text : text.substring(0, eol)).strip().toLowerCase(Locale.ROOT);
        if (firstLine.equals(SQL_MARKER) || firstLine.equals(CLAUDE_MARKER)) {
            return eol < 0 ? "" : text.substring(eol + 1).strip();
        }
        return text;
    }

    static boolean isSql(String text) {
        String body = stripLeadingComments(text);
        if (body.isEmpty() || body.endsWith("?") || body.endsWith(".")) {
            return false;
        }
        var m = LEADING_WORD.matcher(body);
        if (!m.find()) {
            return false;
        }
        Pattern shape = SHAPES.get(m.group(1).toUpperCase(Locale.ROOT));
        if (shape == null || !shape.matcher(body).lookingAt()) {
            return false;
        }
        return !hasProseWord(body);
    }

    /** Removes leading {@code --} line comments and {@code /* *\/} block comments. */
    static String stripLeadingComments(String text) {
        String s = text.strip();
        while (true) {
            if (s.startsWith("--")) {
                int eol = s.indexOf('\n');
                s = eol < 0 ? "" : s.substring(eol + 1).strip();
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = end < 0 ? "" : s.substring(end + 2).strip();
            } else {
                return s;
            }
        }
    }

    /**
     * True when a prose word appears outside single, double or back quotes and outside comments. Words are
     * whole identifiers: letters, digits, {@code _} and {@code $} belong to the word, so {@code my_table},
     * {@code can_id} and {@code t1} are not prose, and a word next to a dot is part of a qualified name
     * ({@code i.k}). The pronoun "I" counts only as a capital letter, so the alias {@code i} stays SQL.
     */
    static boolean hasProseWord(String body) {
        int n = body.length();
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int close = body.indexOf(c, i + 1);
                i = close < 0 ? n : close + 1;
            } else if (c == '-' && i + 1 < n && body.charAt(i + 1) == '-') {
                int eol = body.indexOf('\n', i);
                i = eol < 0 ? n : eol;
            } else if (c == '/' && i + 1 < n && body.charAt(i + 1) == '*') {
                int close = body.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && isWordChar(body.charAt(i))) {
                    i++;
                }
                // an apostrophe inside a word: "don't", "it's"
                if (i + 1 < n && body.charAt(i) == '\'' && Character.isLetter(body.charAt(i + 1))) {
                    i++;
                    while (i < n && Character.isLetter(body.charAt(i))) {
                        i++;
                    }
                }
                boolean qualified = (start > 0 && body.charAt(start - 1) == '.') || (i < n && body.charAt(i) == '.' && i + 1 < n
                        && isWordChar(body.charAt(i + 1)));
                if (!qualified && isProse(body.substring(start, i))) {
                    return true;
                }
            } else {
                i++;
            }
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isProse(String word) {
        if (word.equals("i")) {
            return false; // lowercase i is an alias or a loop variable; the pronoun is written "I"
        }
        return PROSE_WORDS.contains(word.toLowerCase(java.util.Locale.ROOT));
    }

    private static Map.Entry<String, Pattern> shape(String keyword, String regex) {
        return Map.entry(keyword, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }
}
