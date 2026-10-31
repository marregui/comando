package dev.marregui.comando.ui.kit.text;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.swing.text.Segment;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * Syntax colours for text painted outside a text area, from the same token makers and theme as the
 * editors. Safe on any thread: each call makes its own token maker.
 */
public final class Highlighter {

    /** Characters {@code [start, end)} take colour {@code color}. */
    public record Run(int start, int end, Color color) {
    }

    private Highlighter() {
    }

    /** Colour runs of {@code text} for {@code syntax} (a file extension or language name); empty for plain text. */
    public static List<Run> runs(final String text, final String syntax) {
        final String style = Editors.styleFor(syntax);
        final List<Run> out = new ArrayList<>();
        if (style.equals(SyntaxConstants.SYNTAX_STYLE_NONE)) {
            return out;
        }
        final TokenMaker maker = TokenMakerFactory.getDefaultInstance().getTokenMaker(style);
        final char[] chars = text.toCharArray();
        int lineStart = 0;
        int state = TokenTypes.NULL;
        while (lineStart <= chars.length) {
            int lineEnd = lineStart;
            while (lineEnd < chars.length && chars[lineEnd] != '\n') {
                lineEnd++;
            }
            final Segment seg = new Segment(chars, lineStart, lineEnd - lineStart);
            for (Token t = maker.getTokenList(seg, state, lineStart); t != null && t.isPaintable(); t = t.getNextToken()) {
                final Color c = Editors.colorOf(t.getType());
                if (c != null && t.length() > 0) {
                    out.add(new Run(t.getTextOffset(), t.getTextOffset() + t.length(), c));
                }
            }
            state = maker.getLastTokenTypeOnLine(new Segment(chars, lineStart, lineEnd - lineStart), state);
            lineStart = lineEnd + 1;
        }
        return out;
    }
}
