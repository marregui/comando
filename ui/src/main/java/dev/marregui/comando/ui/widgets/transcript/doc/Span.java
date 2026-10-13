package dev.marregui.comando.ui.widgets.transcript.doc;

import java.util.Objects;

/**
 * A run of inline text with one style. {@code href} is empty unless the run is a link.
 *
 * @param style a set of the {@code BOLD}, {@code ITALIC}, {@code CODE} and {@code STRIKE} bits
 */
public record Span(String text, int style, String href) {

    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int CODE = 4;
    public static final int STRIKE = 8;

    public Span {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(href, "href");
    }

    public static Span plain(String text) {
        return new Span(text, 0, "");
    }

    public boolean has(int bit) {
        return (style & bit) != 0;
    }

    public boolean isLink() {
        return !href.isEmpty();
    }
}
