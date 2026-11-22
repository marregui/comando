package dev.marregui.comando.ui.kit.web;

import javax.swing.UIManager;

import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

/**
 * The style sheet a page without one of its own is shown under: the look of rendered Markdown ({@code HtmlPane}) in
 * the theme and tint chosen, written for WebKit, so the page's ground is the theme's and its table cells have borders.
 */
final class PageTheme {

    private PageTheme() {
    }

    /** The rules under the theme, the tint and the font size as they are now, on one line. EDT. */
    static String css() {
        final String ground = Theme.hex(Theme.ground());
        final String fg = Theme.hex(Theme.TRANSCRIPT_TEXT);
        final String border = Theme.hex(Theme.CARD_BORDER);
        final String mono = Theme.mono().getFamily();
        final String serif = Theme.serif().getFamily();
        final Tint tint = Theme.tint();
        final int size = UIManager.getFont("Label.font").getSize();
        final float[] scale = Theme.HEADING_SCALE;
        return String.join(" ",
                "html, body { background-color: " + ground + "; color: " + fg + "; }",
                "body { font-family: -apple-system, 'Helvetica Neue', sans-serif; font-size: " + size + "pt;"
                        + " line-height: 1.45; margin: 16px 24px; }",
                "h1, h2, h3 { font-family: '" + serif + "'; font-weight: bold; color: " + Theme.hex(tint.body) + "; }",
                "h1 { font-size: " + Math.round(size * scale[0]) + "pt; }",
                "h2 { font-size: " + Math.round(size * scale[1]) + "pt; }",
                "h3 { font-size: " + Math.round(size * scale[2]) + "pt; }",
                "h1, h2 { border-bottom: 1px solid " + Theme.hex(tint.headingRule()) + "; padding-bottom: 3px; }",
                "a { color: " + Theme.hex(Theme.LINK) + "; }",
                "b, strong { color: " + Theme.hex(Theme.ANSWER_STRONG) + "; }",
                "code { font-family: '" + mono + "'; color: " + Theme.hex(Theme.content().code) + "; background-color: "
                        + Theme.hex(Theme.content().codeInlineGround) + "; padding: 0 2px; }",
                "pre { font-family: '" + mono + "'; background-color: " + Theme.hex(Theme.content().codeGround)
                        + "; padding: 6px; overflow: auto; }",
                "pre code { color: " + fg + "; background-color: transparent; padding: 0; }",
                "table { border-collapse: collapse; margin: 4px 0 6px 0; }",
                "th, td { border: 1px solid " + border + "; padding: 2px 6px; }",
                "th { color: " + Theme.hex(tint.highlight) + "; background-color: " + Theme.hex(tint.tableHeadGround())
                        + "; }",
                "blockquote { margin-left: 8px; font-style: italic; border-left: 3px solid " + Theme.hex(tint.body)
                        + "; padding-left: 8px; }");
    }
}
