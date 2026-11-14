package dev.marregui.comando.ui.widgets.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;

/**
 * The words completion offers, sorted once, and the ones a typed prefix starts, ignoring case. The prefix is the run of
 * word characters before the caret on its line; a word character is a letter, a digit or {@code _}. Not thread-safe:
 * the editor reads and sets it on the EDT.
 */
final class Completions {

    /**
     * By text ignoring case, the order a prefix search needs; then by description ignoring case, so a column
     * {@code count} of type INT comes before the keyword {@code COUNT}, as the editor has always listed them; then
     * exactly, so ties keep one order.
     */
    private static final Comparator<Completion> ORDER = Comparator
            .comparing(Completion::text, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Completion::description, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Completion::text)
            .thenComparing(Completion::description);

    private List<Completion> sorted = List.of();

    /** Whether {@code ch} belongs to a word that completion completes. */
    static boolean isWordChar(final char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    /** The word characters before {@code caret} on its line of {@code doc}; empty when the character before is none. */
    static String wordBefore(final Document doc, final int caret) {
        final Element root = doc.getDefaultRootElement();
        final int lineStart = root.getElement(root.getElementIndex(caret)).getStartOffset();
        final String line;
        try {
            line = doc.getText(lineStart, caret - lineStart);
        } catch (final BadLocationException e) {
            throw new IllegalStateException(
                    "caret outside its document [caret=" + caret + ", length=" + doc.getLength() + "]", e);
        }
        int start = line.length();
        while (start > 0 && isWordChar(line.charAt(start - 1))) {
            start--;
        }
        return line.substring(start);
    }

    /** Replaces the words with {@code words}, sorted in one pass. */
    void set(final Collection<Completion> words) {
        final List<Completion> copy = new ArrayList<>(words);
        copy.sort(ORDER);
        sorted = Collections.unmodifiableList(copy);
    }

    /** How many words there are. */
    int size() {
        return sorted.size();
    }

    /**
     * The words {@code prefix} starts, ignoring case, in their sorted order; every word for an empty prefix. A view of
     * the sorted words, found by a binary search: no copy, however many match, and valid until the next {@link #set}.
     */
    List<Completion> matching(final String prefix) {
        final int from = firstNotBefore(prefix);
        int to = from;
        while (to < sorted.size() && sorted.get(to).text().regionMatches(true, 0, prefix, 0, prefix.length())) {
            to++;
        }
        return sorted.subList(from, to);
    }

    /** The words whose text is {@code text}, ignoring case. */
    List<Completion> named(final String text) {
        return matching(text).stream().filter(c -> c.text().equalsIgnoreCase(text)).toList();
    }

    /** The index of the first word not before {@code prefix}, ignoring case: where the words it starts begin. */
    private int firstNotBefore(final String prefix) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (String.CASE_INSENSITIVE_ORDER.compare(sorted.get(mid).text(), prefix) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
