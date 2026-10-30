package dev.marregui.comando.ui.kit.text;

import java.util.regex.Pattern;

/** Tooltip text: the theme draws every tooltip as a card (the transcript's ground, the tint's bar); this shapes its text. */
public final class Tooltips {

    /** Characters on a tooltip line before it wraps, about the 520 px the wrapping width allows. */
    private static final int LINE = 70;
    private static final Pattern LONG_WORD = Pattern.compile("\\S{" + LINE + "}(?=\\S)");

    private Tooltips() {
    }

    /**
     * A tooltip that keeps line breaks and wraps a long text at a readable width, while a short one stays as wide as
     * its text; a word longer than a line (a path) is broken, as Swing's HTML never breaks inside a word. The text is
     * escaped, so nothing in it is markup (a notice can hold text from a database or from Claude).
     */
    public static String wrapped(String text) {
        String broken = LONG_WORD.matcher(text).replaceAll("$0\n");
        String body = MarkdownHtml.escape(broken).replace("\n", "<br>");
        boolean wraps = broken.lines().anyMatch(l -> l.length() > LINE);
        return wraps ? "<html><div style='width:520px'>" + body + "</div></html>" : "<html>" + body + "</html>";
    }
}
