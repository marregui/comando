package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns {@code path/to/File.java:42} references in rendered HTML into links, so a click opens the file at
 * the line. Works on text and on {@code <code>} spans; leaves {@code <pre>} blocks and existing links alone.
 */
public final class SourceLinks {

    public static final String SCHEME = "comando-file:";
    static final Pattern REF = Pattern.compile(
            "(?<![\\w/.\\-])((?:/|~/)?(?:[\\w.\\-]+/)*[\\w\\-]+(?:\\.[\\w\\-]+)*\\.[A-Za-z][A-Za-z0-9]{0,7}):(\\d{1,6})(?![\\w])");

    private SourceLinks() {
    }

    public static String linkHtml(String html) {
        StringBuilder out = new StringBuilder(html.length() + 64);
        int i = 0;
        int pre = 0;
        int anchor = 0;
        while (i < html.length()) {
            int lt = html.indexOf('<', i);
            String text = lt < 0 ? html.substring(i) : html.substring(i, lt);
            out.append(pre > 0 || anchor > 0 ? text : linkText(text));
            if (lt < 0) {
                break;
            }
            int gt = html.indexOf('>', lt);
            if (gt < 0) {
                out.append(html.substring(lt));
                break;
            }
            String tag = html.substring(lt, gt + 1);
            String lower = tag.toLowerCase();
            if (lower.startsWith("<pre")) {
                pre++;
            } else if (lower.startsWith("</pre")) {
                pre = Math.max(0, pre - 1);
            } else if (lower.startsWith("<a ") || lower.equals("<a>")) {
                anchor++;
            } else if (lower.startsWith("</a")) {
                anchor = Math.max(0, anchor - 1);
            }
            out.append(tag);
            i = gt + 1;
        }
        return out.toString();
    }

    private static String linkText(String text) {
        Matcher m = REF.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        do {
            m.appendReplacement(sb, Matcher.quoteReplacement("<a href=\"" + SCHEME + m.group(1) + "#" + m.group(2) + "\">" + m.group() + "</a>"));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /** A {@code path:line} reference in plain text: its character range and the link it opens. */
    public record Ref(int start, int end, String href) {
    }

    /** The {@code path:line} references in {@code text}, in order. */
    public static List<Ref> refs(CharSequence text) {
        Matcher m = REF.matcher(text);
        List<Ref> out = new ArrayList<>();
        while (m.find()) {
            out.add(new Ref(m.start(), m.end(), SCHEME + m.group(1) + "#" + m.group(2)));
        }
        return out;
    }

    /** Path of a {@code comando-file:} link. */
    public static String path(String href) {
        String rest = href.substring(SCHEME.length());
        int hash = rest.lastIndexOf('#');
        return hash < 0 ? rest : rest.substring(0, hash);
    }

    /** Line of a {@code comando-file:} link, 0 when none. */
    public static int line(String href) {
        int hash = href.lastIndexOf('#');
        try {
            return hash < 0 ? 0 : Integer.parseInt(href.substring(hash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
