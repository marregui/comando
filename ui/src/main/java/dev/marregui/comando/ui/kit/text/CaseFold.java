package dev.marregui.comando.ui.kit.text;

/**
 * The one case fold of comando's finds, char by char as {@link String#equalsIgnoreCase} compares: upper case, then
 * lower, so {@code ς} and {@code Σ} fold alike. Unlike {@link String#toLowerCase}, which lengthens {@code İ} (U+0130)
 * to two chars, it keeps the length, so an offset in the folded text is the same offset in the original. A find folds
 * its needle with {@link #fold(String)} and scans the text with {@link #contains} or the folded text, never both ways.
 */
public final class CaseFold {

    private CaseFold() {
    }

    /** {@code c} folded. */
    public static char fold(final char c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }

    /**
     * {@code text} folded, the same length; {@code text} itself when no char changes, so a find over text already in
     * lower case allocates nothing.
     */
    public static String fold(final String text) {
        final int n = text.length();
        int i = 0;
        while (i < n && fold(text.charAt(i)) == text.charAt(i)) {
            i++;
        }
        if (i == n) {
            return text;
        }
        final char[] a = text.toCharArray();
        for (; i < n; i++) {
            a[i] = fold(a[i]);
        }
        return new String(a);
    }

    /** Whether {@code text}, folded char by char, holds {@code needle}, already folded; no allocation. */
    public static boolean contains(final CharSequence text, final String needle) {
        final int n = needle.length();
        for (int i = 0, last = text.length() - n; i <= last; i++) {
            int j = 0;
            while (j < n && fold(text.charAt(i + j)) == needle.charAt(j)) {
                j++;
            }
            if (j == n) {
                return true;
            }
        }
        return false;
    }
}
