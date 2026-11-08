package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The hits of a search to mark in a file's text, and the one that is current (record 141): each hit on its line, its
 * match found again there, ASCII letters in either case, nearest the column its snippet points at.
 *
 * @param hits    the hits, in any order
 * @param current the index in {@code hits} of the current one; -1 for none, which leaves the caret where it is
 */
public record HitMarks(List<Hit> hits, int current) {

    /** No hit marked. */
    public static final HitMarks NONE = new HitMarks(List.of(), -1);

    public HitMarks {
        hits = List.copyOf(hits);
        if (current < -1 || current >= hits.size()) {
            throw new IllegalArgumentException("current is not a hit [current=" + current + ", hits=" + hits.size()
                    + ']');
        }
    }

    /** The caret at the start of {@code line}, from 1, nothing marked. */
    public static HitMarks line(final int line) {
        return new HitMarks(List.of(new Hit(line, "", -1, "")), 0);
    }

    /**
     * One hit. A search's snippet drops the line's indent and keeps a bounded text before the match, so the match sits
     * {@code column} chars past the indent, unless the line holds {@code before} right in front of an occurrence, which
     * then wins.
     *
     * @param line   its line, from 1; 0 for none, a hit on the file's name
     * @param match  the text that matched; empty puts the caret at the line's start and marks nothing
     * @param column where on the line its snippet puts the match, in chars past the line's indent; -1 when unknown
     * @param before the snippet's text in front of the match; empty when unknown
     */
    public record Hit(int line, String match, int column, String before) {
        public Hit {
            Objects.requireNonNull(match, "match");
            Objects.requireNonNull(before, "before");
        }

        /** Where on {@code line} each occurrence of the match starts, as the search finds it, none overlapping. */
        public List<Integer> startsIn(final String line) {
            final List<Integer> out = new ArrayList<>();
            if (match.isEmpty()) {
                return out;
            }
            int at = 0;
            while (at + match.length() <= line.length()) {
                if (matchesAt(line, at, match)) {
                    out.add(at);
                    at += match.length();
                } else {
                    at++;
                }
            }
            return out;
        }

        /**
         * The start in {@code starts}, not empty, nearest where this hit's match sits on {@code line}: right after
         * {@link #before} where the line holds it there, else {@link #column} past the line's indent; the first start
         * when the column is unknown.
         */
        public int nearestIn(final String line, final List<Integer> starts) {
            int target = indent(line) + Math.max(column, 0);
            if (!before.isEmpty()) {
                for (final int start : starts) {
                    if (endsAt(line, start, before)) {
                        target = start;
                        break;
                    }
                }
            }
            int best = starts.getFirst();
            for (final int start : starts) {
                if (Math.abs(start - target) < Math.abs(best - target)) {
                    best = start;
                }
            }
            return best;
        }

        /** Whether {@code at} in {@code text} holds {@code needle}, ASCII letters in either case. */
        private static boolean matchesAt(final String text, final int at, final String needle) {
            for (int i = 0; i < needle.length(); i++) {
                final char a = text.charAt(at + i);
                final char b = needle.charAt(i);
                if (a != b && !(isAsciiLetter(a) && isAsciiLetter(b) && (a | 0x20) == (b | 0x20))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isAsciiLetter(final char c) {
            return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
        }

        /** Whether {@code before} ends at {@code at} on {@code line}; a control char there reads as a space. */
        private static boolean endsAt(final String line, final int at, final String before) {
            final int from = at - before.length();
            if (from < 0) {
                return false;
            }
            for (int i = 0; i < before.length(); i++) {
                final char c = line.charAt(from + i);
                if (c != before.charAt(i) && !(Character.isISOControl(c) && before.charAt(i) == ' ')) {
                    return false;
                }
            }
            return true;
        }

        private static int indent(final String line) {
            int i = 0;
            while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            return i;
        }
    }
}
