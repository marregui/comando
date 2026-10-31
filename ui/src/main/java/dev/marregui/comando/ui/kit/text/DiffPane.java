package dev.marregui.comando.ui.kit.text;

import java.util.List;
import javax.swing.text.BadLocationException;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import dev.marregui.comando.ui.kit.theme.Theme;

/** A unified diff of two texts with added and removed lines highlighted. */
public final class DiffPane {

    private final RSyntaxTextArea area = Editors.viewer("");
    private final RTextScrollPane scroll = Editors.scroll(area, false);

    public DiffPane() {
        area.setHighlightCurrentLine(false);
    }

    public RTextScrollPane component() {
        return scroll;
    }

    public RSyntaxTextArea area() {
        return area;
    }

    /** Shows the diff of {@code before} and {@code after}, or "(no changes)" when they are equal. */
    public void show(final String path, final String before, final String after) {
        final List<String> lines = Diffs.unified(path, before, after);
        final StringBuilder text = new StringBuilder();
        for (final String l : lines) {
            text.append(l).append('\n');
        }
        if (lines.isEmpty()) {
            text.append("(no changes)\n");
        }
        area.setText(text.toString());
        area.removeAllLineHighlights();
        for (int i = 0; i < lines.size(); i++) {
            final String l = lines.get(i);
            try {
                if (Diffs.isAdded(i, l)) {
                    area.addLineHighlight(i, Theme.content().added);
                } else if (Diffs.isRemoved(i, l)) {
                    area.addLineHighlight(i, Theme.content().removed);
                }
            } catch (final BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
        area.setCaretPosition(0);
    }
}
