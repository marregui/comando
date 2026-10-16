package dev.marregui.comando.core.view;

/**
 * Text shortened for a label or a tooltip to at most {@code max} characters, the ellipsis included, so every clip in
 * comando counts the same way, in the window and in what it tells Claude. A clip never splits a surrogate pair.
 */
public final class Clip {

    private static final String ELLIPSIS = "…";

    private Clip() {
    }

    /** {@code text}, or its start and an ellipsis when it is longer than {@code max}: for names and messages. */
    public static String keepStart(String text, int max) {
        check(max);
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    /** {@code text}, or an ellipsis and its end when it is longer than {@code max}: for paths, whose end names them. */
    public static String keepEnd(String text, int max) {
        check(max);
        if (text.length() <= max) {
            return text;
        }
        int start = text.length() - (max - 1);
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return ELLIPSIS + text.substring(start);
    }

    private static void check(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("a clip keeps at least the ellipsis [max=" + max + ']');
        }
    }
}
