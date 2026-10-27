package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The YAML front matter at the top of a Markdown text: a {@code ---} line, {@code key: value} lines, and a closing
 * {@code ---} or {@code ...} line. Read as its facts, a key and its value a line: a nested key is named by its path
 * ({@code metadata.type}), a list's items are joined by commas, quotes are dropped. Not a YAML parser: a block that
 * does not look like front matter is left to the Markdown it is in.
 */
public final class FrontMatter {

    /** A key and its value, as read. */
    public record Fact(String key, String value) {
    }

    /** The facts read and the text without them: the front matter's characters are spaces, so lines and offsets hold. */
    public record Split(List<Fact> facts, String body) {

        public boolean any() {
            return !facts.isEmpty();
        }

        /** The value of {@code key}; null when there is none. */
        public String value(String key) {
            return facts.stream().filter(f -> f.key().equals(key)).map(Fact::value).findFirst().orElse(null);
        }
    }

    /** The lines front matter may span, closing line included: a longer block is not taken for it. */
    static final int MAX_LINES = 200;

    private static final Pattern ENTRY = Pattern.compile("^( *)([A-Za-z0-9_][A-Za-z0-9_.\\- ]*?):(?: +(.*))?$");
    private static final Pattern ITEM = Pattern.compile("^( *)- +(.*)$");

    private FrontMatter() {
    }

    public static Split split(String text) {
        Split none = new Split(List.of(), text);
        if (!text.startsWith("---")) {
            return none;
        }
        String[] lines = text.split("\n", MAX_LINES + 2);
        if (!lines[0].strip().equals("---")) {
            return none;
        }
        int close = -1;
        for (int i = 1; i < Math.min(lines.length, MAX_LINES); i++) {
            String l = lines[i].strip();
            if (l.equals("---") || l.equals("...")) {
                close = i;
                break;
            }
        }
        if (close < 2) {
            return none;
        }
        List<Fact> facts = read(lines, close);
        if (facts == null || facts.isEmpty()) {
            return none;
        }
        int at = 0;
        for (int i = 0; i <= close; i++) {
            at = text.indexOf('\n', at) + 1;
            if (at == 0) {
                at = text.length();
                break;
            }
        }
        // blanked in place, so every line and character of the rest keeps its offset
        StringBuilder body = new StringBuilder(text);
        for (int i = 0; i < at; i++) {
            if (body.charAt(i) != '\n') {
                body.setCharAt(i, ' ');
            }
        }
        return new Split(List.copyOf(facts), body.toString());
    }

    /** The facts of lines 1 to {@code close}, exclusive; null when a line is not one front matter has. */
    private static List<Fact> read(String[] lines, int close) {
        List<Fact> out = new ArrayList<>();
        // the keys open at each indent: a nested key's name is the path to it
        List<int[]> indents = new ArrayList<>();
        List<String> path = new ArrayList<>();
        String listKey = null;
        StringBuilder list = null;
        for (int i = 1; i < close; i++) {
            String line = lines[i].stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (item.matches() && listKey != null) {
                list.append(list.isEmpty() ? "" : ", ").append(unquote(item.group(2)));
                continue;
            }
            Matcher m = ENTRY.matcher(line);
            if (!m.matches()) {
                if (line.startsWith(" ") && !out.isEmpty()) {
                    // a folded or block value's next line
                    Fact last = out.removeLast();
                    out.add(new Fact(last.key(), (last.value() + " " + line.strip()).strip()));
                    continue;
                }
                return null;
            }
            if (listKey != null) {
                // a key with nothing under it but more keys is a map, not a fact of its own
                if (!list.isEmpty()) {
                    out.add(new Fact(listKey, list.toString()));
                }
                listKey = null;
            }
            int indent = m.group(1).length();
            while (!indents.isEmpty() && indents.getLast()[0] >= indent) {
                indents.removeLast();
                path.removeLast();
            }
            String key = (path.isEmpty() ? "" : String.join(".", path) + ".") + m.group(2).strip();
            String value = m.group(3) == null ? "" : m.group(3).strip();
            if (value.isEmpty()) {
                // a map or a list follows; a list's items become the value
                indents.add(new int[]{indent});
                path.add(m.group(2).strip());
                listKey = key;
                list = new StringBuilder();
            } else if (value.equals("|") || value.equals(">") || value.equals("|-") || value.equals(">-")) {
                out.add(new Fact(key, ""));
            } else {
                out.add(new Fact(key, value.startsWith("[") && value.endsWith("]")
                        ? String.join(", ", splitList(value.substring(1, value.length() - 1)))
                        : unquote(value)));
            }
        }
        if (listKey != null && !list.isEmpty()) {
            out.add(new Fact(listKey, list.toString()));
        }
        return out;
    }

    private static List<String> splitList(String inner) {
        List<String> out = new ArrayList<>();
        for (String s : inner.split(",")) {
            if (!s.isBlank()) {
                out.add(unquote(s.strip()));
            }
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            String inner = s.substring(1, s.length() - 1);
            return s.startsWith("\"") ? inner.replace("\\\"", "\"").replace("\\\\", "\\") : inner.replace("''", "'");
        }
        return s;
    }
}
