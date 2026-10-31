package dev.marregui.comando.ui.widgets.editor;

import java.util.HashSet;
import java.util.Set;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Segment;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

/**
 * The command editor's document. Under {@link CommandEditor#PANEL_STYLE} its text is coloured block by block by
 * {@link PanelTokenMaker}; any other style, a file's language, is RSyntaxTextArea's own.
 *
 * <p>It counts its edits, so the token maker keeps what it learnt about the blocks until the text changes. An edit can
 * change the kind of the whole block it is in ("select" becomes a statement at its shape, a prose word makes it a
 * prompt), so after each edit the lines of the blocks it touched are repainted, not only the edited ones.</p>
 */
final class PanelDocument extends RSyntaxDocument {

    private long version;
    private boolean sqlLive;
    private final Segment probe = new Segment();

    PanelDocument() {
        super(SyntaxConstants.SYNTAX_STYLE_NONE);
        setTokenMakerFactory(new Factory(this));
    }

    /** Changes with every edit and with {@link #sqlLive(boolean)}. */
    long version() {
        return version;
    }

    boolean sqlLive() {
        return sqlLive;
    }

    /**
     * Whether SQL blocks are coloured as SQL: only while the panel's database is open. Without one, the text is
     * prompts and notes, and a statement written for later reads as text until the database opens.
     */
    void sqlLive(final boolean on) {
        if (on == sqlLive) {
            return;
        }
        sqlLive = on;
        version++;
        if (CommandEditor.PANEL_STYLE.equals(getSyntaxStyle())) {
            // a fresh token maker: the document drops the token list it keeps for the last line asked, and repaints
            setSyntaxStyle(CommandEditor.PANEL_STYLE);
        }
    }

    @Override
    protected void fireInsertUpdate(final DocumentEvent e) {
        version++;
        super.fireInsertUpdate(e);
        repaintBlocks(e.getOffset(), e.getOffset() + e.getLength());
    }

    @Override
    protected void fireRemoveUpdate(final DocumentEvent e) {
        version++;
        super.fireRemoveUpdate(e);
        repaintBlocks(e.getOffset(), e.getOffset());
    }

    /** Repaints every line of the blocks holding {@code from} to {@code to}: a change of kind recolours all of them. */
    private void repaintBlocks(final int from, final int to) {
        if (!CommandEditor.PANEL_STYLE.equals(getSyntaxStyle())) {
            return;
        }
        final Element root = getDefaultRootElement();
        final int first = firstLineOfBlock(root.getElementIndex(from));
        final int last = lastLineOfBlock(root.getElementIndex(Math.min(to, getLength())));
        final int start = root.getElement(first).getStartOffset();
        final int end = Math.min(getLength(), root.getElement(last).getEndOffset());
        if (end > start) {
            fireChangedUpdate(new DefaultDocumentEvent(start, end - start, DocumentEvent.EventType.CHANGE));
        }
    }

    /** The first line of the block holding {@code line}; a blank line is its own. */
    int firstLineOfBlock(int line) {
        if (isBlank(line)) {
            return line;
        }
        while (line > 0 && !isBlank(line - 1)) {
            line--;
        }
        return line;
    }

    /** The last line of the block holding {@code line}; a blank line is its own. */
    int lastLineOfBlock(int line) {
        if (isBlank(line)) {
            return line;
        }
        final int lines = getDefaultRootElement().getElementCount();
        while (line < lines - 1 && !isBlank(line + 1)) {
            line++;
        }
        return line;
    }

    boolean isBlank(final int line) {
        lineText(line, probe);
        for (int i = probe.offset, end = probe.offset + probe.count; i < end; i++) {
            if (!Character.isWhitespace(probe.array[i])) {
                return false;
            }
        }
        return true;
    }

    /** Fills {@code into} with the text of {@code line}, without its newline. */
    void lineText(final int line, final Segment into) {
        final Element e = getDefaultRootElement().getElement(line);
        final int start = e.getStartOffset();
        final int end = Math.min(e.getEndOffset() - 1, getLength());
        try {
            getText(start, Math.max(0, end - start), into);
        } catch (final BadLocationException ex) {
            into.array = new char[0];
            into.offset = 0;
            into.count = 0;
        }
    }

    /** The text of lines {@code first} to {@code last}, without the last newline. */
    String linesText(final int first, final int last) {
        final Element root = getDefaultRootElement();
        final int start = root.getElement(first).getStartOffset();
        final int end = Math.min(root.getElement(last).getEndOffset() - 1, getLength());
        try {
            return getText(start, Math.max(0, end - start));
        } catch (final BadLocationException ex) {
            return "";
        }
    }

    /** {@link CommandEditor#PANEL_STYLE} makes a {@link PanelTokenMaker}; other styles are RSyntaxTextArea's. */
    private static final class Factory extends TokenMakerFactory {
        private final PanelDocument doc;

        Factory(final PanelDocument doc) {
            this.doc = doc;
        }

        @Override
        protected TokenMaker getTokenMakerImpl(final String key) {
            return CommandEditor.PANEL_STYLE.equals(key) ? new PanelTokenMaker(doc) : TokenMakerFactory.getDefaultInstance().getTokenMaker(key);
        }

        @Override
        public Set<String> keySet() {
            final Set<String> keys = new HashSet<>(TokenMakerFactory.getDefaultInstance().keySet());
            keys.add(CommandEditor.PANEL_STYLE);
            return keys;
        }
    }
}
