package dev.marregui.comando.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines a listing file (such as a scratchpad day's {@code ALPHA.md}) gives an entry beside it, taken out once the
 * entry went to the Trash. An entry's own line is a list item or a table row whose first element names it: a code span,
 * a link or bare text holding the entry's path relative to the listing file's folder ({@code READ-01-X.md},
 * {@code work/a.py}, {@code work/} for a folder, with its slash). A list item takes the lines indented under it.
 * Lines inside a fenced code block are never an entry's own. Any other line naming the entry, by a whole name, stays
 * and is reported, so the user decides what it should say now. Line ends ({@code \n} or {@code \r\n}) are kept.
 */
final class ListingLines {

    /** The text without the entries' own lines, how many lines went, and the 1-based numbers, in the text returned, of the lines that still name an entry. */
    record Result(String text, int removed, List<Integer> mentions) {
        Result {
            mentions = List.copyOf(mentions);
        }
    }

    private static final Pattern ITEM = Pattern.compile("^(\\s*)(?:[-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\|?\\s*:?-{3,}.*$");
    private static final Pattern LINK = Pattern.compile("^\\[([^\\]]*)]\\(([^)]*)\\)");

    private ListingLines() {
    }

    /**
     * {@code text} without the own lines of {@code entries}: paths relative to the listing file's folder, separated by
     * {@code /}, a folder's ending in {@code /}.
     */
    static Result without(String text, List<String> entries) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        List<Integer> mentions = new ArrayList<>();
        int removed = 0;
        int kept = 0;
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String body = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (body.strip().startsWith("```") || body.strip().startsWith("~~~")) {
                fenced = !fenced;
            } else if (!fenced && owns(body, entries)) {
                removed++;
                Matcher item = ITEM.matcher(body);
                if (item.matches()) {
                    int indent = width(item.group(1));
                    while (i + 1 < lines.length && !lines[i + 1].isBlank() && width(leading(lines[i + 1])) > indent) {
                        i++;
                        removed++;
                    }
                }
                continue;
            }
            if (kept > 0) {
                out.append('\n');
            }
            out.append(line);
            kept++;
            if (names(body, entries)) {
                mentions.add(kept);
            }
        }
        return new Result(out.toString(), removed, mentions);
    }

    /** Whether {@code line} is the own line of one of {@code entries}. */
    static boolean owns(String line, List<String> entries) {
        String subject = subject(line);
        if (subject.isEmpty()) {
            return false;
        }
        // a folder is named with its slash: "work" alone is as likely a word as the folder
        return entries.contains(subject);
    }

    /** What a list item or a table row is about: its first element, cleaned of marks; empty for any other line. */
    static String subject(String line) {
        String rest;
        Matcher item = ITEM.matcher(line);
        String stripped = line.strip();
        if (item.matches()) {
            rest = item.group(2).strip();
        } else if (stripped.startsWith("|") && !TABLE_RULE.matcher(stripped).matches()) {
            int end = stripped.indexOf('|', 1);
            rest = (end < 0 ? stripped.substring(1) : stripped.substring(1, end)).strip();
            // a cell is its own element: the whole of it names the entry, or nothing does
            rest = unwrap(rest);
            Matcher link = LINK.matcher(rest);
            return link.find() && rest.substring(link.end()).isBlank() ? clean(target(link)) : clean(rest);
        } else {
            return "";
        }
        return clean(firstElement(rest));
    }

    /** The first element of a list item's text: a code span, a link's target, else the text up to a space or a colon. */
    private static String firstElement(String rest) {
        rest = unwrap(rest);
        if (rest.startsWith("`")) {
            int end = rest.indexOf('`', 1);
            return end < 0 ? "" : rest.substring(1, end);
        }
        if (rest.startsWith("[")) {
            Matcher link = LINK.matcher(rest);
            return link.find() ? target(link) : "";
        }
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end)) && rest.charAt(end) != ':') {
            end++;
        }
        return rest.substring(0, end);
    }

    /** A link's target, the file it names, without a heading anchor. */
    private static String target(Matcher link) {
        String target = link.group(2).strip();
        int anchor = target.indexOf('#');
        return anchor < 0 ? target : target.substring(0, anchor);
    }

    /** Text without the bold or italic marks around it. */
    private static String unwrap(String s) {
        for (String mark : List.of("**", "__", "*", "_")) {
            if (s.startsWith(mark) && s.length() > 2 * mark.length()) {
                int end = s.indexOf(mark, mark.length());
                if (end > 0 && s.substring(end + mark.length()).isBlank()) {
                    return s.substring(mark.length(), end).strip();
                }
            }
        }
        return s;
    }

    /** A path as written, without code marks, a leading {@code ./} or trailing punctuation. */
    private static String clean(String s) {
        s = s.strip();
        if (s.length() >= 2 && s.startsWith("`") && s.endsWith("`")) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (!s.isEmpty() && ",;:".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Whether {@code line} names an entry: a file by its whole name ({@code a.md}, not {@code data.md} or {@code
     * a.md.bak}), a folder by its path with the slash.
     */
    private static boolean names(String line, List<String> entries) {
        for (String e : entries) {
            String name = e.endsWith("/") ? e : e.substring(e.lastIndexOf('/') + 1);
            for (int at = line.indexOf(name); at >= 0; at = line.indexOf(name, at + 1)) {
                int end = at + name.length();
                boolean startsWord = at == 0 || !nameChar(line.charAt(at - 1));
                // a dot after the name ends a sentence unless a name goes on after it
                boolean endsWord = end == line.length() || !nameChar(line.charAt(end))
                        || line.charAt(end) == '.' && (end + 1 == line.length() || !nameChar(line.charAt(end + 1)));
                if (startsWord && endsWord) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean nameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }

    private static String leading(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /** Indentation width, a tab counting four. */
    private static int width(String indent) {
        int w = 0;
        for (char c : indent.toCharArray()) {
            w += c == '\t' ? 4 : 1;
        }
        return w;
    }
}
