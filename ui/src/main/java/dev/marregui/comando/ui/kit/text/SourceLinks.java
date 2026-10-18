package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.core.view.ViewSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code path/to/File.java:42} references in plain text, which the transcript shows as links that open the file
 * at the line; and reads such a link back.
 */
public final class SourceLinks {

    public static final String SCHEME = ViewSpec.TreeNode.FILE_SCHEME;
    static final Pattern REF = Pattern.compile(
            "(?<![\\w/.\\-])((?:/|~/)?(?:[\\w.\\-]+/)*[\\w\\-]+(?:\\.[\\w\\-]+)*\\.[A-Za-z][A-Za-z0-9]{0,7}):(\\d{1,6})(?![\\w])");

    private SourceLinks() {
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
