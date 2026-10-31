package dev.marregui.comando.ui.widgets.editor;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.text.Element;
import javax.swing.text.Segment;

import org.fife.ui.rsyntaxtextarea.OccurrenceMarker;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerBase;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Target;

/**
 * Colours a panel's own text block by block, as the block will be run: the same blocks ({@link
 * dev.marregui.comando.core.dispatch.Blocks}: lines between blank lines) and the same {@link Classifier} that decide
 * where a block goes. A block that runs on the database is coloured as SQL, but only while the panel's database is
 * open; any other block is a prompt or a note, coloured as Markdown, with a leading Claude command ({@code /reflect})
 * marked. A marker line ({@code -- @claude}) reads as a comment.
 *
 * <p>RSyntaxTextArea asks for one line at a time and keeps each line's end state itself, but without telling the
 * token maker which line it is; that state cannot follow a block whose kind changed. So the state a line starts in is
 * kept here, per block, and a block starts afresh: SQL comments and Markdown fences do not run across a blank line,
 * which ends a block anyway. What is learnt about the blocks is kept until the document's next edit.</p>
 */
final class PanelTokenMaker extends TokenMakerBase {

    enum Kind {
        SQL, PROSE
    }

    private final PanelDocument doc;
    private final TokenMaker sql = TokenMakerFactory.getDefaultInstance().getTokenMaker(SyntaxConstants.SYNTAX_STYLE_SQL);
    private final TokenMaker prose = TokenMakerFactory.getDefaultInstance().getTokenMaker(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
    private final Segment other = new Segment();
    /** The blocks met since the last edit, by first line. */
    private final TreeMap<Integer, Span> spans = new TreeMap<>();
    private long version = -1;

    PanelTokenMaker(final PanelDocument doc) {
        this.doc = doc;
    }

    /**
     * A block: its lines, its kind, the line that holds a marker or a Claude command (-1 when none), and the state each
     * of its lines ends in, filled in order as far as a line has needed.
     */
    private static final class Span {
        final int first;
        final int last;
        final Kind kind;
        final int marker;
        final int command;
        final int[] ends;
        int known;

        Span(final int first, final int last, final Kind kind, final int marker, final int command) {
            this.first = first;
            this.last = last;
            this.kind = kind;
            this.marker = marker;
            this.command = command;
            this.ends = new int[last - first + 1];
        }
    }

    @Override
    public Token getTokenList(final Segment text, final int initialTokenType, final int startOffset) {
        final Element root = doc.getDefaultRootElement();
        final int line = root.getElementIndex(startOffset);
        if (root.getElement(line).getStartOffset() != startOffset || isBlank(text)) {
            // not one of the document's lines as they stand, or a blank line between blocks
            return prose.getTokenList(text, TokenTypes.NULL, startOffset);
        }
        final Span span = span(line);
        if (line == span.marker) {
            return whole(text, startOffset, TokenTypes.COMMENT_EOL);
        }
        if (line == span.command) {
            return command(text, startOffset);
        }
        return maker(span.kind).getTokenList(text, stateBefore(span, line), startOffset);
    }

    /**
     * The state a line ends in is kept per block, here; the document's own copy, kept without knowing the line, is
     * not used.
     */
    @Override
    public int getLastTokenTypeOnLine(final Segment text, final int initialTokenType) {
        return TokenTypes.NULL;
    }

    /** The kind of the block holding {@code line}; for tests. */
    Kind kindOf(final int line) {
        return span(line).kind;
    }

    private Span span(final int line) {
        if (doc.version() != version) {
            spans.clear();
            version = doc.version();
        }
        final Map.Entry<Integer, Span> e = spans.floorEntry(line);
        if (e != null && e.getValue().last >= line) {
            return e.getValue();
        }
        final int first = doc.firstLineOfBlock(line);
        final int last = doc.lastLineOfBlock(line);
        final String block = doc.linesText(first, last);
        final Classifier.Classification c = Classifier.classify(block);
        final boolean marked = isMarker(block);
        final int marker = marked ? first : -1;
        int command = -1;
        if (c.target() == Target.CLAUDE && c.text().length() > 1 && c.text().charAt(0) == '/' && Character.isLetter(c.text().charAt(1))) {
            command = marked ? first + 1 : first;
        }
        final Kind kind = c.target() == Target.SQL && doc.sqlLive() ? Kind.SQL : Kind.PROSE;
        final Span s = new Span(first, last, kind, kind == Kind.SQL ? -1 : marker, command > last ? -1 : command);
        spans.put(first, s);
        return s;
    }

    /** The state {@code line} starts in: what the line above it in the same block ended in, tokenized in order. */
    private int stateBefore(final Span s, final int line) {
        final int index = line - s.first;
        while (s.known < index) {
            final int at = s.first + s.known;
            final int start = s.known == 0 ? TokenTypes.NULL : s.ends[s.known - 1];
            int end;
            if (at == s.marker || at == s.command) {
                end = TokenTypes.NULL;
            } else {
                doc.lineText(at, other);
                end = maker(s.kind).getLastTokenTypeOnLine(other, start);
            }
            s.ends[s.known++] = end;
        }
        return index == 0 ? TokenTypes.NULL : s.ends[index - 1];
    }

    private TokenMaker maker(final Kind kind) {
        return kind == Kind.SQL ? sql : prose;
    }

    /** The line as one token of {@code type}, after its leading whitespace. */
    private Token whole(final Segment text, final int startOffset, final int type) {
        resetTokenList();
        final int i = leadingWhitespace(text);
        addSpan(text, text.offset, i, TokenTypes.WHITESPACE, startOffset);
        addSpan(text, i, text.offset + text.count, type, startOffset);
        addNullToken();
        return firstToken;
    }

    /** {@code /reflect now}: the command marked, its arguments plain. */
    private Token command(final Segment text, final int startOffset) {
        resetTokenList();
        final int i = leadingWhitespace(text);
        final int end = text.offset + text.count;
        int word = i + 1;
        while (word < end && !Character.isWhitespace(text.array[word])) {
            word++;
        }
        addSpan(text, text.offset, i, TokenTypes.WHITESPACE, startOffset);
        addSpan(text, i, word, TokenTypes.PREPROCESSOR, startOffset);
        addSpan(text, word, end, TokenTypes.IDENTIFIER, startOffset);
        addNullToken();
        return firstToken;
    }

    /** Adds {@code text.array[from, to)} as one token; nothing when empty. */
    private void addSpan(final Segment text, final int from, final int to, final int type, final int startOffset) {
        if (to > from) {
            addToken(text.array, from, to - 1, type, startOffset + from - text.offset);
        }
    }

    private static int leadingWhitespace(final Segment text) {
        int i = text.offset;
        final int end = text.offset + text.count;
        while (i < end && Character.isWhitespace(text.array[i])) {
            i++;
        }
        return i;
    }

    private static boolean isBlank(final Segment text) {
        return leadingWhitespace(text) == text.offset + text.count;
    }

    private static boolean isMarker(final String block) {
        final String text = block.strip();
        final int eol = text.indexOf('\n');
        final String first = (eol < 0 ? text : text.substring(0, eol)).strip().toLowerCase(Locale.ROOT);
        return first.equals(Classifier.SQL_MARKER) || first.equals(Classifier.CLAUDE_MARKER);
    }

    // ---- the editor's per-language questions are SQL's: comments toggle as "--", identifiers are marked

    @Override
    public String[] getLineCommentStartAndEnd(final int languageIndex) {
        return sql.getLineCommentStartAndEnd(languageIndex);
    }

    @Override
    public boolean getMarkOccurrencesOfTokenType(final int type) {
        return sql.getMarkOccurrencesOfTokenType(type);
    }

    @Override
    public OccurrenceMarker getOccurrenceMarker() {
        return sql.getOccurrenceMarker();
    }

    /** SQL's token types are all standard; Markdown keeps its line states in internal ones. */
    @Override
    public int getClosestStandardTokenTypeForInternalType(final int type) {
        return prose.getClosestStandardTokenTypeForInternalType(type);
    }
}
