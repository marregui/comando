package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.core.dispatch.Blocks;
import dev.marregui.comando.core.dispatch.Classifier;
import dev.marregui.comando.core.dispatch.Statements;
import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.TextSearch;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SquiggleUnderlineHighlightPainter;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.IconRowEvent;
import org.fife.ui.rtextarea.IconRowListener;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Caret;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.Position;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The command editor: SQL and prompts in one document. The gutter marks each block with where it goes when run, a
 * database or Claude, before it runs; a click on a mark runs the selection inside that block, or the whole block. A
 * block that ran is tinted until the next edit, and the caret stays where it was, so the next keystroke never replaces
 * what ran and nothing written into the editor from outside lands on it. A session's write is one undo step of its own,
 * keeps the user's caret and selection, and never joins the line the user is typing.
 */
public final class CommandEditor implements Searchable, Zoomable {

    /**
     * The style of a panel's own text: each block coloured as what it is, SQL while the panel's database is open, a
     * prompt or a note otherwise (see {@link PanelTokenMaker}).
     */
    public static final String PANEL_STYLE = "text/comando-panel";

    private final PanelDocument doc = new PanelDocument();
    private final RSyntaxTextArea area = panelArea(doc);
    private final RTextScrollPane scroll = Editors.scroll(area, true);
    private final TextSearch search = new TextSearch(area);
    private final Debouncer markers = new Debouncer(250, this::refreshMarkers);
    private final Debouncer occurrences = new Debouncer(150, this::refreshOccurrences);
    /** The statement that ran, behind a band of the tint chosen as it paints (record 100). */
    private final Highlighter.HighlightPainter ranPainter = new DefaultHighlighter.DefaultHighlightPainter(null) {
        @Override
        public Color getColor() {
            return Theme.withAlpha(Theme.tint().body, 40);
        }
    };
    private Object ranTag;
    private final Highlighter.HighlightPainter errorPainter = new SquiggleUnderlineHighlightPainter(Theme.DANGER);
    private Object errorTag;
    /** Where the text that ran last starts, and that text; -1 once the text changes. */
    private int lastRunStart = -1;
    private String lastRunText = "";
    /** The end of the block that ran last; it follows later edits, and a session's insert lands after it. Null when none. */
    private Position ranEnd;
    private Consumer<String> onChange = t -> {
    };
    private boolean silent;
    private IntConsumer onMark = line -> {
    };
    private final SqlCompletion completion = new SqlCompletion();
    private int marked;

    /** The panel closed: releases what outlives the component (the completion's global listener, the gutter timer). */
    public void dispose() {
        completion.uninstall();
        markers.stop();
        occurrences.stop();
    }

    public CommandEditor() {
        completion.install(area, this::caretInSql);
        area.addCaretListener(e -> occurrences.request());
        scroll.setIconRowHeaderEnabled(true);
        scroll.getGutter().addIconRowListener(new IconRowListener() {
            @Override
            public void bookmarkAdded(IconRowEvent e) {
                // no bookmarks: the icon row holds the blocks' marks only
            }

            @Override
            public void bookmarkRemoved(IconRowEvent e) {
                // no bookmarks
            }

            @Override
            public void mouseClicked(IconRowEvent e, MouseEvent m) {
                if (SwingUtilities.isLeftMouseButton(m) && m.getClickCount() == 1 && markedBlock(e.getLine()) != null) {
                    onMark.accept(e.getLine());
                }
            }
        });
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // attribute changes do not change the text
            }
        });
        Capabilities.mark(scroll, this);
    }

    public JComponent component() {
        return scroll;
    }

    public RSyntaxTextArea area() {
        return area;
    }

    private static RSyntaxTextArea panelArea(PanelDocument doc) {
        RSyntaxTextArea a = Editors.command();
        a.setDocument(doc);
        a.setSyntaxEditingStyle(PANEL_STYLE);
        return a;
    }

    /**
     * The syntax the text is coloured as: {@link #PANEL_STYLE} for a panel's own text, where blocks run on a database
     * or go to Claude; a file's own language for a file opened in a panel, where the gutter marks no targets and
     * nothing reads as SQL.
     */
    public void syntax(String style) {
        if (style.equals(area.getSyntaxEditingStyle())) {
            return;
        }
        area.setSyntaxEditingStyle(style);
        refreshMarkers();
        refreshOccurrences();
    }

    private boolean sqlStyle() {
        return PANEL_STYLE.equals(area.getSyntaxEditingStyle());
    }

    /**
     * Whether the panel's database is open: only then are SQL blocks coloured as SQL and completed as they are typed;
     * without one, the text is prompts and notes.
     */
    public void sqlLive(boolean on) {
        if (on != doc.sqlLive()) {
            doc.sqlLive(on);
            refreshOccurrences();
        }
    }

    /** Whether SQL blocks are coloured as SQL; for tests. */
    boolean sqlLive() {
        return doc.sqlLive();
    }

    /** Empties the text as one step ⌘Z undoes. */
    public void clear() {
        replaceAll("");
    }

    /** Completes SQL keywords and the tables and columns of {@code schema}; Ctrl+Space, or after a pause in a word. */
    public void setSchema(Schema schema) {
        completion.setSchema(schema);
    }

    /** Called on the EDT after each edit with the whole text. */
    public void onChange(Consumer<String> listener) {
        onChange = listener;
    }

    private void changed() {
        clearRanMark();
        clearErrorMark();
        lastRunStart = -1;
        markers.request();
        if (!silent) {
            onChange.accept(area.getText());
        }
    }

    /** Sets the text without reporting a change; the undo history starts here. */
    public void load(String text) {
        silent = true;
        try {
            area.setText(text);
            area.setCaretPosition(0);
            area.discardAllEdits();
            ranEnd = null;
        } finally {
            silent = false;
        }
        refreshMarkers();
    }

    /**
     * Replaces the text with its file's new content without reporting a change: one undoable edit, so ⌘Z brings
     * back the text it replaced; the caret and the view stay where they were, within the new text.
     */
    public void reload(String text) {
        int caret = area.getCaretPosition();
        Rectangle view = area.getVisibleRect();
        silent = true;
        try {
            area.beginAtomicEdit();
            try {
                area.setText(text);
            } finally {
                area.endAtomicEdit();
            }
            area.setCaretPosition(Math.min(caret, area.getDocument().getLength()));
            ranEnd = null;
        } finally {
            silent = false;
        }
        area.scrollRectToVisible(view);
        refreshMarkers();
    }

    public String text() {
        return area.getText();
    }

    /**
     * The selection, or the block under the caret when nothing is selected. The block is tinted, not selected:
     * the caret stays, so typing goes on where it was and nothing replaces the text that ran.
     */
    public String runText() {
        String selected = area.getSelectedText();
        clearErrorMark();
        if (selected != null && !selected.isBlank()) {
            return runSelected(selected);
        }
        String text = area.getText();
        Blocks.Block b = Blocks.at(text, area.getCaretPosition());
        return b == null ? "" : runBlock(text, b);
    }

    /**
     * What a click on the mark of the block starting at {@code line} runs: the selection when it lies inside that
     * block, otherwise the whole block, as {@link #runText()} does for the block under the caret. The caret and a
     * selection elsewhere stay. Empty when no marked block starts there.
     */
    public String runText(int line) {
        Blocks.Block b = markedBlock(line);
        if (b == null) {
            return "";
        }
        clearErrorMark();
        String selected = area.getSelectedText();
        if (selected != null && !selected.isBlank() && area.getSelectionStart() >= b.start() && area.getSelectionEnd() <= b.end()) {
            return runSelected(selected);
        }
        return runBlock(area.getText(), b);
    }

    /** {@code run} gets the first line of a block whose gutter mark was clicked. */
    public void onMarkClicked(IntConsumer run) {
        onMark = run;
    }

    /** The block starting at {@code line} that the gutter marks with a target; null when none. */
    private Blocks.Block markedBlock(int line) {
        if (!sqlStyle()) {
            return null;
        }
        String text = area.getText();
        for (Blocks.Block b : Blocks.all(text)) {
            if (b.firstLine() == line) {
                Classifier.Classification c = Classifier.classify(b.text(text));
                return c.target() == Target.SQL && c.text().isEmpty() ? null : b;
            }
        }
        return null;
    }

    private String runSelected(String selected) {
        lastRunStart = area.getSelectionStart();
        lastRunText = selected;
        trackRanEnd(blockEnd(area.getText(), area.getSelectionEnd()));
        scrollHome();
        return selected;
    }

    private String runBlock(String text, Blocks.Block b) {
        markRan(b.start(), b.end());
        trackRanEnd(b.end());
        lastRunStart = b.start();
        lastRunText = b.text(text);
        scrollHome();
        return lastRunText;
    }

    /** The end of the block holding the character before {@code selectionEnd}, or {@code selectionEnd} outside any block. */
    private static int blockEnd(String text, int selectionEnd) {
        int last = Math.max(0, selectionEnd - 1);
        Blocks.Block b = Blocks.at(text, last);
        return b != null && b.start() <= last && last <= b.end() ? b.end() : selectionEnd;
    }

    private void trackRanEnd(int offset) {
        try {
            ranEnd = area.getDocument().createPosition(offset);
        } catch (BadLocationException e) {
            ranEnd = null;
        }
    }

    /** Where a session's insert lands: the end of the block that ran last, as it stands now; -1 when nothing ran. */
    int ranEnd() {
        return ranEnd == null ? -1 : ranEnd.getOffset();
    }

    /** Back to the first column after a run, so a long statement does not leave the line starts off screen. */
    private void scrollHome() {
        SwingUtilities.invokeLater(() -> scroll.getHorizontalScrollBar().setValue(0));
    }

    /**
     * Underlines the token at {@code position} (1-based, as the database reports it) of {@code sql}, which ran
     * from this editor; true when the text has not changed since and the position falls in it. The next edit clears it.
     * A block of several statements is not marked: the driver sends each statement on its own, and the database
     * counts the position from the start of the one that failed, which the error does not name.
     */
    public boolean markError(String sql, int position) {
        clearErrorMark();
        if (Statements.split(sql).size() != 1) {
            return false;
        }
        int within = lastRunStart < 0 ? -1 : lastRunText.indexOf(sql);
        if (within < 0 || position < 1 || position > sql.length()) {
            return false;
        }
        int at = lastRunStart + within + position - 1;
        String text = area.getText();
        if (at >= text.length()) {
            return false;
        }
        int end = at;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_' || text.charAt(end) == '.')) {
            end++;
        }
        try {
            errorTag = area.getHighlighter().addHighlight(at, Math.max(end, at + 1), errorPainter);
            return true;
        } catch (BadLocationException e) {
            return false;
        }
    }

    /** The start of the error underline, or -1; for tests. */
    int errorStart() {
        if (errorTag == null) {
            return -1;
        }
        for (Highlighter.Highlight h : area.getHighlighter().getHighlights()) {
            if (h == errorTag) {
                return h.getStartOffset();
            }
        }
        return -1;
    }

    private void clearErrorMark() {
        if (errorTag != null) {
            area.getHighlighter().removeHighlight(errorTag);
            errorTag = null;
        }
    }

    /** The block that ran last, or -1 when nothing is tinted. */
    int ranStart() {
        if (ranTag == null) {
            return -1;
        }
        for (Highlighter.Highlight h : area.getHighlighter().getHighlights()) {
            if (h == ranTag) {
                return h.getStartOffset();
            }
        }
        return -1;
    }

    private void markRan(int start, int end) {
        clearRanMark();
        try {
            ranTag = area.getHighlighter().addHighlight(start, end, ranPainter);
        } catch (BadLocationException e) {
            ranTag = null;
        }
    }

    private void clearRanMark() {
        if (ranTag != null) {
            area.getHighlighter().removeHighlight(ranTag);
            ranTag = null;
        }
    }

    /** Replaces the whole text as one step ⌘Z undoes. */
    public void replaceAll(String text) {
        area.beginAtomicEdit();
        try {
            area.selectAll();
            area.replaceSelection(text);
        } finally {
            area.endAtomicEdit();
        }
        ranEnd = null;
        area.setCaretPosition(0);
    }

    /**
     * Writes {@code text} as a block of its own right after the block that ran last from this editor, wherever that
     * block is now; appends it when nothing ran. The user's caret and selection stay exactly where they were, and a
     * second insert lands after the first.
     */
    public void insertAtCaret(String text) {
        int at = ranEnd();
        String current = area.getText();
        if (at < 0 || at >= current.length()) {
            appendQuietly(text);
            if (at >= 0) {
                trackRanEnd(area.getText().stripTrailing().length());
            }
            return;
        }
        String core = text.replaceFirst("^(?:[ \\t]*\\r?\\n)+", "").stripTrailing();
        // a caret on a line below the block that ran, in the blank lines before what follows, is where the user is
        // about to type: that line stays the user's, and the session's block goes after it
        Caret caret = area.getCaret();
        int gapEnd = at;
        while (gapEnd < current.length() && Character.isWhitespace(current.charAt(gapEnd))) {
            gapEnd++;
        }
        int dot = Math.max(caret.getDot(), caret.getMark());
        if (dot > at && dot <= gapEnd && current.indexOf('\n', at) >= 0 && current.indexOf('\n', at) < dot) {
            int lineEnd = current.indexOf('\n', dot);
            at = lineEnd < 0 ? current.length() : lineEnd;
        }
        String rest = current.substring(at);
        // a blank line must separate the new block from what follows it
        String suffix = rest.isBlank() || rest.matches("(?s)\\n[ \\t]*\\n.*") ? "" : rest.startsWith("\n") ? "\n" : "\n\n";
        String prefix = "\n\n";
        write(at, prefix + core + suffix);
        trackRanEnd(at + prefix.length() + core.length());
    }

    /** Appends {@code text} as a new block at the end and moves the caret under it: the user's own attachment. */
    public void append(String text) {
        appendBlock(text);
        area.setCaretPosition(area.getDocument().getLength());
    }

    /**
     * Appends {@code text} as a new block at the end, followed by a blank line so the user's next block starts on
     * its own, without moving the caret or the selection: a session writes while the user may be typing. When the
     * caret is at the end, the line it is on stays the user's: the block goes after it, past a blank line.
     */
    public void appendQuietly(String text) {
        String current = area.getText();
        int end = current.length();
        Caret caret = area.getCaret();
        // an empty editor counts too: the caret's line stays empty for the user, the block goes after it
        boolean atEnd = caret.getDot() == end || caret.getMark() == end;
        String body = text.endsWith("\n\n") ? text : text.endsWith("\n") ? text + "\n" : text + "\n\n";
        write(end, (atEnd ? "\n\n" : separator(current)) + body);
    }

    /**
     * Inserts a session's text at {@code at} as one undo step of its own, so ⌘Z takes back only the session's
     * text, never the user's typing around it; the caret and the selection keep their places and direction.
     */
    private void write(int at, String text) {
        Caret caret = area.getCaret();
        int dot = caret.getDot();
        int mark = caret.getMark();
        int ran = ranEnd();
        // ends the user's open compound edit first, so the user's typing and this text are separate undo steps
        area.beginAtomicEdit();
        try {
            area.insert(text, at);
        } finally {
            area.endAtomicEdit();
        }
        if (ran == at) {
            trackRanEnd(ran); // the block that ran did not grow; text written at its end does not move its end
        }
        restoreSelection(dot > at ? dot + text.length() : dot, mark > at ? mark + text.length() : mark);
    }

    private void restoreSelection(int dot, int mark) {
        area.setCaretPosition(mark);
        area.moveCaretPosition(dot);
    }

    private void appendBlock(String text) {
        area.append(separator(area.getText()) + text);
    }

    /** What goes between {@code current} and a block appended to it: enough newlines for one blank line. */
    private static String separator(String current) {
        return current.isEmpty() ? "" : current.endsWith("\n\n") ? "" : current.endsWith("\n") ? "\n" : "\n\n";
    }

    /**
     * The caret is in a block that reads as SQL, or that starts like a statement being typed, so completion may pop
     * up on its own and repeated words light up.
     */
    boolean caretInSql() {
        if (!sqlStyle() || !doc.sqlLive()) {
            return false;
        }
        String text = area.getText();
        int caret = area.getCaretPosition();
        Blocks.Block b = Blocks.at(text, caret);
        if (b == null || caret < b.start() || caret > b.end()) {
            return false;
        }
        String block = b.text(text);
        Classifier.Classification c = Classifier.classify(block);
        return c.target() == Target.SQL && !c.text().isEmpty() || SqlCompletion.startsAStatement(block);
    }

    /** Repeated words light up in SQL and in source code, where they are identifiers, not in prose. */
    private void refreshOccurrences() {
        String style = area.getSyntaxEditingStyle();
        boolean on = sqlStyle() ? caretInSql()
                : !style.equals(SyntaxConstants.SYNTAX_STYLE_NONE) && !style.equals(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        if (area.getMarkOccurrences() != on) {
            area.setMarkOccurrences(on);
        }
    }

    /** The blocks the gutter marks with their target; for tests. */
    int marked() {
        return marked;
    }

    private void refreshMarkers() {
        Gutter gutter = scroll.getGutter();
        gutter.removeAllTrackingIcons();
        marked = 0;
        if (!sqlStyle()) {
            return;
        }
        String text = area.getText();
        for (Blocks.Block b : Blocks.all(text)) {
            Classifier.Classification c = Classifier.classify(b.text(text));
            if (c.target() == Target.SQL && c.text().isEmpty()) {
                continue; // comments only: it goes nowhere
            }
            Target t = c.target();
            try {
                gutter.addLineTrackingIcon(b.firstLine(), Icons.get(t == Target.SQL ? "database" : "claude", 14),
                        t == Target.SQL ? "Runs on the panel's connection; click to run the selection in it, or the whole block"
                                : "Goes to the panel's session as a prompt; click to send the selection in it, or the whole block");
                marked++;
            } catch (BadLocationException e) {
                return; // the text changed under us; the next refresh redraws
            }
        }
    }

    @Override
    public int find(String text, boolean forward) {
        return search.find(text, forward);
    }

    @Override
    public void clearFind() {
        search.clear();
    }

    @Override
    public void rewindFind() {
        search.rewind();
    }

    @Override
    public void zoom(int steps) {
        area.setFont(area.getFont().deriveFont(Zoomable.clampSize(area.getFont().getSize2D() + steps)));
    }

    @Override
    public void resetZoom() {
        area.setFont(Theme.mono());
    }

    public void requestFocus() {
        area.requestFocusInWindow();
    }
}
