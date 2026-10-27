package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.kit.text.TextLinks;
import dev.marregui.comando.ui.kit.theme.Theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.text.AttributedString;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Text with font and colour runs, links and per-line backgrounds, built once and laid out by
 * {@link TextBox}. Every character carries a font, so line breaking never resolves fonts itself.
 */
final class Rich {

    record Link(int start, int end, String href) {
    }

    private record Run(int start, int end, Font font, Color color, Color background, boolean underline, boolean strike) {
    }

    private final StringBuilder text = new StringBuilder();
    private final List<Run> runs = new ArrayList<>();
    /** The span of text one entry of the run index covers. */
    private static final int CHUNK = 256;
    private int[][] chunks;
    /** How many runs {@link #chunks} indexes; -1 before it is built. */
    private int indexed = -1;
    private final List<Link> links = new ArrayList<>();
    private final Font baseFont;
    private final Color baseColor;
    /** Background of whole source lines, by line number; null entries for none. */
    private List<Color> lineBackgrounds = List.of();

    Rich(Font baseFont, Color baseColor) {
        this.baseFont = baseFont;
        this.baseColor = baseColor;
    }

    Rich append(String s) {
        return append(s, baseFont, baseColor, null, false, false);
    }

    Rich append(String s, Font font, Color color) {
        return append(s, font, color, null, false, false);
    }

    Rich append(String s, Font font, Color color, Color background, boolean underline, boolean strike) {
        if (s.isEmpty()) {
            return this;
        }
        int start = text.length();
        if (s.indexOf('\t') < 0) {
            text.append(s);
        } else {
            expandTabs(s);
        }
        runs.add(new Run(start, text.length(), font, color, background, underline, strike));
        fallBack(start, font);
        return this;
    }

    /**
     * The characters from {@code start} that {@code font} cannot draw take {@link Theme#fallback}: a later run wins, so
     * only the font changes there. Nothing is added when the font draws them all, which is the common case.
     */
    private void fallBack(int start, Font font) {
        if (font == null) {
            return;
        }
        String added = text.substring(start);
        int missing = font.canDisplayUpTo(added);
        if (missing < 0) {
            return;
        }
        Font fallback = Theme.fallback(font);
        while (missing >= 0) {
            int end = missing;
            while (end < added.length()) {
                int cp = added.codePointAt(end);
                if (font.canDisplay(cp)) {
                    break;
                }
                end += Character.charCount(cp);
            }
            runs.add(new Run(start + missing, start + end, fallback, null, null, false, false));
            missing = end >= added.length() ? -1 : font.canDisplayUpTo(new StringCharacterIterator(added), end, added.length());
        }
    }

    /** Tabs become spaces to the next multiple of four columns; a text layout draws a tab as nothing. */
    private void expandTabs(String s) {
        int column = text.length() - (text.lastIndexOf("\n") + 1);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                int spaces = 4 - column % 4;
                text.repeat(' ', spaces);
                column += spaces;
            } else {
                text.append(c);
                column = c == '\n' ? 0 : column + 1;
            }
        }
    }

    /** {@code s} with tabs expanded to four-column stops from each line start. */
    static String detab(String s) {
        if (s.indexOf('\t') < 0) {
            return s;
        }
        return new Rich(null, null).append(s).text();
    }

    Rich link(int start, int end, String href) {
        links.add(new Link(start, end, href));
        return this;
    }

    /**
     * Makes the links in the text links, in {@code color} and underlined: web addresses, {@code [[name]]} notes,
     * Markdown links and {@code path:line} references, as written ({@link TextLinks}).
     */
    Rich linked(Color color) {
        for (TextLinks.Ref r : TextLinks.refs(text, true)) {
            runs.add(new Run(r.start(), r.end(), null, color, null, true, false));
            link(r.start(), r.end(), r.href());
        }
        return this;
    }

    /** Recolours {@code [start, end)}; used for syntax colours over text appended in the base style. */
    Rich color(int start, int end, Color color) {
        runs.add(new Run(start, end, null, color, null, false, false));
        return this;
    }

    Rich lineBackgrounds(List<Color> backgrounds) {
        lineBackgrounds = backgrounds;
        return this;
    }

    int length() {
        return text.length();
    }

    String text() {
        return text.toString();
    }

    Color lineBackground(int sourceLine) {
        return sourceLine < lineBackgrounds.size() ? lineBackgrounds.get(sourceLine) : null;
    }

    boolean hasLineBackgrounds() {
        return !lineBackgrounds.isEmpty();
    }

    String linkAt(int index) {
        for (Link l : links) {
            if (index >= l.start() && index < l.end()) {
                return l.href();
            }
        }
        return null;
    }

    List<Link> links() {
        return links;
    }

    /**
     * The runs touching each {@link #CHUNK} characters of the text, by index in the order they were added: a line's
     * layout visits only the runs over it, and a later run still wins over an earlier one. Built on first use and again
     * when runs were added since.
     */
    private int[][] chunks() {
        if (indexed != runs.size()) {
            int n = text.length() / CHUNK + 1;
            int[] counts = new int[n];
            for (Run r : runs) {
                for (int c = r.start() / CHUNK, last = lastChunk(r, n); c <= last; c++) {
                    counts[c]++;
                }
            }
            int[][] out = new int[n][];
            for (int c = 0; c < n; c++) {
                out[c] = new int[counts[c]];
            }
            int[] filled = new int[n];
            for (int k = 0; k < runs.size(); k++) {
                Run r = runs.get(k);
                for (int c = r.start() / CHUNK, last = lastChunk(r, n); c <= last; c++) {
                    out[c][filled[c]++] = k;
                }
            }
            chunks = out;
            indexed = runs.size();
        }
        return chunks;
    }

    private static int lastChunk(Run r, int chunks) {
        return Math.min(chunks - 1, Math.max(r.start(), r.end() - 1) / CHUNK);
    }

    /** The attributed form of {@code [start, end)}, which must not be empty. */
    AttributedString attributed(int start, int end) {
        AttributedString a = new AttributedString(text.substring(start, end));
        a.addAttribute(TextAttribute.FONT, baseFont);
        a.addAttribute(TextAttribute.FOREGROUND, baseColor);
        int[][] ch = chunks();
        int first = Math.min(ch.length - 1, start / CHUNK);
        int last = Math.min(ch.length - 1, (end - 1) / CHUNK);
        int[] touching = first == last ? ch[first] : IntStream.rangeClosed(first, last).flatMap(c -> IntStream.of(ch[c])).sorted().distinct().toArray();
        for (int k : touching) {
            Run r = runs.get(k);
            int s = Math.max(r.start(), start);
            int e = Math.min(r.end(), end);
            if (s >= e) {
                continue;
            }
            if (r.font() != null) {
                a.addAttribute(TextAttribute.FONT, r.font(), s - start, e - start);
            }
            if (r.color() != null) {
                a.addAttribute(TextAttribute.FOREGROUND, r.color(), s - start, e - start);
            }
            if (r.background() != null) {
                a.addAttribute(TextAttribute.BACKGROUND, r.background(), s - start, e - start);
            }
            if (r.underline()) {
                a.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, s - start, e - start);
            }
            if (r.strike()) {
                a.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, s - start, e - start);
            }
        }
        return a;
    }

    Font baseFont() {
        return baseFont;
    }
}
