package dev.marregui.comando.ui.kit.text;

/** The info string of a fenced code block. */
public final class Fences {

    private Fences() {
    }

    /** The fence's language: the first whitespace-separated word of {@code info}, empty for none or null. */
    public static String language(String info) {
        String s = info == null ? "" : info.strip();
        return s.isEmpty() ? "" : s.split("\\s+", 2)[0];
    }
}
