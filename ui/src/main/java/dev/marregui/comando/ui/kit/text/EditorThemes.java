package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.ContentPalette;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.Gutter;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The editors' colours, made in code from the theme's content palette ({@code Theme.content()}, record 101) on the
 * panels' ground: keywords in the tint, six categories on one wheel, comments muted. An editor shown takes the palette
 * again when another tint or ground is chosen, keeping its font and zoom.
 */
final class EditorThemes {

    /** One theme per palette; each area takes its own copy of the scheme. */
    private static final Map<ContentPalette, Theme> THEMES = new ConcurrentHashMap<>();
    /** The client property of an area that follows the palette chosen. */
    private static final String FOLLOWS_PALETTE = "comando.followsPalette";

    private EditorThemes() {
    }

    /** Gives {@code area} the palette chosen now, and again each time another is chosen while it is shown. */
    static void apply(RSyntaxTextArea area) {
        restyle(area);
        if (area.getClientProperty(FOLLOWS_PALETTE) == null) {
            area.putClientProperty(FOLLOWS_PALETTE, Boolean.TRUE);
            SidebarLook.whileShown(area, () -> {
                Font font = area.getFont();
                restyle(area);
                area.setFont(font);
                area.repaint();
            });
        }
    }

    /** {@code area}, its gutter and the gutter's columns in the palette chosen now; the font is the theme's base. */
    private static void restyle(RSyntaxTextArea area) {
        ContentPalette p = dev.marregui.comando.ui.kit.theme.Theme.content();
        Theme theme = theme(p);
        theme.apply(area);
        // each area its own copy: a zoom rewrites its scheme's fonts, which would reach every other editor
        area.setSyntaxScheme((SyntaxScheme) theme.scheme.clone());
        area.setForeground(p.ink);
        Gutter gutter = RSyntaxUtilities.getGutter(area);
        if (gutter != null) {
            for (Component c : gutter.getComponents()) {
                c.setBackground(theme.gutterBackgroundColor);
            }
        }
    }

    /** The scheme of the palette chosen now. */
    static SyntaxScheme scheme() {
        return theme(dev.marregui.comando.ui.kit.theme.Theme.content()).scheme;
    }

    private static Theme theme(ContentPalette p) {
        return THEMES.computeIfAbsent(p, EditorThemes::make);
    }

    private static Theme make(ContentPalette p) {
        Color ground = dev.marregui.comando.ui.kit.theme.Theme.ground();
        // the theme's only constructor reads an area; this one is never shown, and its caret would otherwise keep
        // blinking on a Swing timer that never stops, which keeps AWT, and a JVM with no window, alive
        RSyntaxTextArea stray = new RSyntaxTextArea();
        stray.getCaret().setBlinkRate(0);
        Theme t = new Theme(stray);
        t.baseFont = RSyntaxTextArea.getDefaultFont();
        t.bgColor = ground;
        t.caretColor = p.caret;
        t.useSelectionFG = false;
        t.selectionBG = p.selection;
        t.selectionRoundedEdges = false;
        t.currentLineHighlight = p.currentLine;
        t.fadeCurrentLineHighlight = false;
        t.tabLineColor = p.guide;
        t.marginLineColor = p.guide;
        t.markAllHighlightColor = dev.marregui.comando.ui.kit.theme.Theme.FIND_MATCH;
        t.markOccurrencesColor = p.occurrence;
        t.markOccurrencesBorder = false;
        t.matchedBracketFG = p.ink;
        t.matchedBracketBG = p.bracket;
        t.matchedBracketHighlightBoth = true;
        t.matchedBracketAnimate = false;
        t.hyperlinkFG = dev.marregui.comando.ui.kit.theme.Theme.LINK;
        t.secondaryLanguages = p.embedded.clone();
        t.gutterBackgroundColor = ground;
        t.gutterBorderColor = ground;
        t.activeLineRangeColor = p.keyword;
        t.iconRowHeaderInheritsGutterBG = true;
        t.lineNumberColor = p.lineNumber;
        t.currentLineNumberColor = p.currentLineNumber;
        t.lineNumberFont = null;
        t.lineNumberFontSize = 0;
        t.foldIndicatorBG = ground;
        t.foldIndicatorFG = p.lineNumber;
        t.foldIndicatorArmedFG = p.currentLineNumber;
        t.foldBG = ground;
        t.armedFoldBG = p.foldArmed;
        t.scheme = scheme(p, t.baseFont);
        return t;
    }

    /** Every token type's colour: the ink unless named below; documentation comments in italic. */
    private static SyntaxScheme scheme(ContentPalette p, Font base) {
        SyntaxScheme s = new SyntaxScheme(base, false);
        Font italic = base.deriveFont(Font.ITALIC);
        for (int i = 0; i < TokenTypes.DEFAULT_NUM_TOKEN_TYPES; i++) {
            s.setStyle(i, new Style(p.ink));
        }
        for (int type : new int[]{TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.DATA_TYPE,
                TokenTypes.LITERAL_BOOLEAN, TokenTypes.MARKUP_TAG_DELIMITER, TokenTypes.MARKUP_TAG_NAME,
                TokenTypes.MARKUP_DTD, TokenTypes.MARKUP_PROCESSING_INSTRUCTION, TokenTypes.MARKUP_CDATA_DELIMITER}) {
            s.setStyle(type, new Style(p.keyword));
        }
        for (int type : new int[]{TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
                TokenTypes.LITERAL_NUMBER_HEXADECIMAL, TokenTypes.MARKUP_ENTITY_REFERENCE}) {
            s.setStyle(type, new Style(p.number));
        }
        for (int type : new int[]{TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR,
                TokenTypes.LITERAL_BACKQUOTE, TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE}) {
            s.setStyle(type, new Style(p.string));
        }
        s.setStyle(TokenTypes.ANNOTATION, new Style(p.annotation));
        s.setStyle(TokenTypes.PREPROCESSOR, new Style(p.annotation));
        s.setStyle(TokenTypes.REGEX, new Style(p.regex));
        s.setStyle(TokenTypes.VARIABLE, new Style(p.variable));
        s.setStyle(TokenTypes.FUNCTION, new Style(p.ink));
        s.setStyle(TokenTypes.COMMENT_EOL, new Style(p.comment));
        s.setStyle(TokenTypes.COMMENT_MULTILINE, new Style(p.comment));
        s.setStyle(TokenTypes.MARKUP_COMMENT, new Style(p.comment));
        s.setStyle(TokenTypes.COMMENT_DOCUMENTATION, new Style(p.comment, null, italic));
        s.setStyle(TokenTypes.COMMENT_MARKUP, new Style(p.comment, null, italic));
        s.setStyle(TokenTypes.COMMENT_KEYWORD, new Style(p.commentTag, null, italic));
        s.setStyle(TokenTypes.WHITESPACE, new Style(p.lineNumber));
        for (int type : new int[]{TokenTypes.ERROR_IDENTIFIER, TokenTypes.ERROR_NUMBER_FORMAT,
                TokenTypes.ERROR_STRING_DOUBLE, TokenTypes.ERROR_CHAR}) {
            s.setStyle(type, new Style(p.error));
        }
        return s;
    }
}
