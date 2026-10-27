package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The links in plain text, wherever comando shows text: web addresses ({@code https://…}), wiki-style links
 * ({@code [[name]]}, {@link NoteLinks}), {@code path:line} references ({@link SourceLinks}) and, in text that is not
 * Markdown already parsed, Markdown links ({@code [label](destination)}). One finder, so every surface sees the same
 * links.
 */
public final class TextLinks {

    /**
     * A link in a text: its character range, what it opens, and the text to show for it where the text is rendered
     * rather than kept as written (a wiki link's label, without its brackets).
     */
    public record Ref(int start, int end, String href, String label) {
    }

    static final Pattern WEB = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"'`\\[\\]{}|\\\\^]+");
    static final Pattern WIKI = Pattern.compile("\\[\\[([^\\[\\]\\n|#]+?)(?:#([^\\[\\]\\n|]*))?(?:\\|([^\\[\\]\\n]*))?]]");
    static final Pattern MARKDOWN = Pattern.compile("(?<!!)\\[([^\\[\\]\\n]+)]\\(<?([^)<>\\s]+)>?\\)");

    private TextLinks() {
    }

    /** The links in {@code text}, in order and not overlapping; with {@code markdown}, Markdown links too. */
    public static List<Ref> refs(CharSequence text, boolean markdown) {
        List<Ref> found = new ArrayList<>();
        Matcher wiki = WIKI.matcher(text);
        while (wiki.find()) {
            String name = wiki.group(1).strip();
            if (name.isEmpty()) {
                continue;
            }
            String heading = wiki.group(2);
            String label = wiki.group(3) != null && !wiki.group(3).isBlank() ? wiki.group(3).strip()
                    : heading == null || heading.isBlank() ? name : name + " › " + heading.strip();
            found.add(new Ref(wiki.start(), wiki.end(), NoteLinks.href(name, heading), label));
        }
        if (markdown) {
            Matcher md = MARKDOWN.matcher(text);
            while (md.find()) {
                found.add(new Ref(md.start(), md.end(), md.group(2), md.group(1)));
            }
        }
        Matcher web = WEB.matcher(text);
        while (web.find()) {
            int end = trimEnd(text, web.start(), web.end());
            if (end > web.start()) {
                String url = text.subSequence(web.start(), end).toString();
                found.add(new Ref(web.start(), end, url, url));
            }
        }
        for (SourceLinks.Ref r : SourceLinks.refs(text)) {
            found.add(new Ref(r.start(), r.end(), r.href(), text.subSequence(r.start(), r.end()).toString()));
        }
        // the first to start wins; of two starting together, the longer: a web address inside a Markdown link is the link's
        found.sort(Comparator.comparingInt(Ref::start).thenComparing(Comparator.comparingInt(Ref::end).reversed()));
        List<Ref> out = new ArrayList<>(found.size());
        int done = 0;
        for (Ref r : found) {
            if (r.start() >= done) {
                out.add(r);
                done = r.end();
            }
        }
        return out;
    }

    /** The link at {@code index} of {@code text}; null when none is there. */
    public static Ref at(CharSequence text, int index, boolean markdown) {
        for (Ref r : refs(text, markdown)) {
            if (index >= r.start() && index < r.end()) {
                return r;
            }
        }
        return null;
    }

    /**
     * Where a web address found up to {@code end} really ends: sentence punctuation after it is not part of it, nor a
     * closing bracket it did not open, as in "(see https://x.y/z)".
     */
    static int trimEnd(CharSequence text, int start, int end) {
        while (end > start) {
            char c = text.charAt(end - 1);
            if (".,;:!?'\"*_".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' && count(text, start, end, '(') < count(text, start, end, ')')) {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static int count(CharSequence text, int start, int end, char c) {
        int n = 0;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
