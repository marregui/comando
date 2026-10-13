package dev.marregui.comando.ui.widgets.editor;

import dev.marregui.comando.core.dispatch.Blocks;
import dev.marregui.comando.core.dispatch.Classifier;
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
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.util.function.Consumer;

/**
 * The command editor: SQL and prompts in one document. The gutter marks each block with where it goes
 * when run, a database or Claude, before it runs.
 */
public final class CommandEditor implements Searchable, Zoomable {

    private final RSyntaxTextArea area = Editors.command();
    private final RTextScrollPane scroll = Editors.scroll(area, true);
    private final TextSearch search = new TextSearch(area);
    private final Debouncer markers = new Debouncer(250, this::refreshMarkers);
    private Consumer<String> onChange = t -> {
    };
    private boolean silent;
    private final SqlCompletion completion = new SqlCompletion();

    /** The panel closed: releases what outlives the component (the completion's global listener, the gutter timer). */
    public void dispose() {
        completion.uninstall();
        markers.stop();
    }

    public CommandEditor() {
        completion.install(area);
        scroll.setIconRowHeaderEnabled(true);
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

    /** Completes SQL keywords and the tables and columns of {@code schema}; Ctrl+Space, or after a pause in a word. */
    public void setSchema(Schema schema) {
        completion.setSchema(schema);
    }

    /** Called on the EDT after each edit with the whole text. */
    public void onChange(Consumer<String> listener) {
        onChange = listener;
    }

    private void changed() {
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
        } finally {
            silent = false;
        }
        refreshMarkers();
    }

    public String text() {
        return area.getText();
    }

    /** The selection, or the block under the caret when nothing is selected. */
    public String runText() {
        String selected = area.getSelectedText();
        if (selected != null && !selected.isBlank()) {
            return selected;
        }
        String text = area.getText();
        Blocks.Block b = Blocks.at(text, area.getCaretPosition());
        if (b == null) {
            return "";
        }
        area.select(b.start(), b.end());
        return b.text(text);
    }

    public void replaceAll(String text) {
        area.selectAll();
        area.replaceSelection(text);
        area.setCaretPosition(0);
    }

    public void insertAtCaret(String text) {
        area.replaceSelection(text);
    }

    /** Appends {@code text} as a new block at the end and moves the caret there. */
    public void append(String text) {
        String current = area.getText();
        String sep = current.isEmpty() ? "" : current.endsWith("\n\n") ? "" : current.endsWith("\n") ? "\n" : "\n\n";
        area.append(sep + text);
        area.setCaretPosition(area.getDocument().getLength());
    }

    private void refreshMarkers() {
        Gutter gutter = scroll.getGutter();
        gutter.removeAllTrackingIcons();
        String text = area.getText();
        for (Blocks.Block b : Blocks.all(text)) {
            Target t = Classifier.classify(b.text(text)).target();
            try {
                gutter.addLineTrackingIcon(b.firstLine(), Icons.get(t == Target.SQL ? "database" : "claude", 14),
                        t == Target.SQL ? "Runs on the panel's connection" : "Goes to the panel's session as a prompt");
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
    public void zoom(int steps) {
        area.setFont(area.getFont().deriveFont(Math.max(8f, Math.min(40f, area.getFont().getSize2D() + steps))));
    }

    @Override
    public void resetZoom() {
        area.setFont(Theme.mono());
    }

    public void requestFocus() {
        area.requestFocusInWindow();
    }
}
