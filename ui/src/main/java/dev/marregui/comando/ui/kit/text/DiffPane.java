package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.text.BadLocationException;
import java.util.List;

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
    public void show(String path, String before, String after) {
        List<String> lines = Diffs.unified(path, before, after);
        StringBuilder text = new StringBuilder();
        for (String l : lines) {
            text.append(l).append('\n');
        }
        if (lines.isEmpty()) {
            text.append("(no changes)\n");
        }
        area.setText(text.toString());
        area.removeAllLineHighlights();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            try {
                if (Diffs.isAdded(i, l)) {
                    area.addLineHighlight(i, Theme.content().added);
                } else if (Diffs.isRemoved(i, l)) {
                    area.addLineHighlight(i, Theme.content().removed);
                }
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
        area.setCaretPosition(0);
    }
}
