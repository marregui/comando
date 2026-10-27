package dev.marregui.comando.ui.widgets.text;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.desktop.ExternalEditor;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Editors;
import dev.marregui.comando.ui.kit.text.TextSearch;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Searchable;
import dev.marregui.comando.ui.widget.capability.Zoomable;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** A file or text, read-only, highlighted by type, scrolled to a line. */
public final class TextWidget implements Widget<ViewSpec.Text>, Searchable, Copyable, Zoomable {

    private final RSyntaxTextArea area = Editors.viewer("");
    private final RTextScrollPane scroll = Editors.scroll(area, true);
    private final TextSearch search = new TextSearch(area);
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel header = new JPanel(new BorderLayout());
    private final JLabel pathLabel = new JLabel();
    private final JButton openExternal = new JButton("Open in IntelliJ", Icons.get("export"));
    private Path file;
    private int line;

    public TextWidget() {
        header.setBorder(BorderFactory.createEmptyBorder(2, Theme.GAP, 2, Theme.GAP));
        pathLabel.setForeground(Theme.MUTED);
        openExternal.putClientProperty("JButton.buttonType", "toolBarButton");
        openExternal.setToolTipText("Open the file at this line in IntelliJ IDEA");
        openExternal.addActionListener(e -> {
            if (file != null) {
                ExternalEditor.open(file, line);
            }
        });
        header.add(pathLabel, BorderLayout.CENTER);
        header.add(openExternal, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        Capabilities.mark(root, this);
    }

    @Override
    public JComponent component() {
        return root;
    }

    /** The view's path; null for none, or for text no file system can name, which then shows without its header. */
    private static Path path(String path) {
        try {
            return path.isEmpty() ? null : Path.of(path);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    @Override
    public void render(ViewSpec.Text view) {
        Path p = path(view.path());
        file = p != null && p.isAbsolute() ? p : null;
        line = view.line();
        header.setVisible(file != null);
        pathLabel.setText(file == null ? "" : Labels.shortPath(file.toString()) + (line > 0 ? ":" + line : ""));
        area.setSyntaxEditingStyle(Editors.styleFor(view.syntax()));
        area.setText(view.text());
        area.removeAllLineHighlights();
        int line = view.line();
        if (line > 0 && line <= area.getLineCount()) {
            try {
                int offset = area.getLineStartOffset(line - 1);
                area.setCaretPosition(offset);
                area.addLineHighlight(line - 1, Theme.withAlpha(Theme.tint().body, 60));
                SwingUtilities.invokeLater(() -> {
                    try {
                        Rectangle r = area.modelToView2D(offset).getBounds();
                        r.y = Math.max(0, r.y - scroll.getViewport().getHeight() / 3);
                        r.height = scroll.getViewport().getHeight();
                        area.scrollRectToVisible(r);
                    } catch (BadLocationException ignore) {
                        // the text changed between render and scroll; the next render scrolls again
                    }
                });
            } catch (BadLocationException e) {
                area.setCaretPosition(0);
            }
        } else {
            area.setCaretPosition(0);
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
    public String copyText() {
        String s = area.getSelectedText();
        return s != null ? s : area.getText();
    }

    @Override
    public void zoom(int steps) {
        area.setFont(area.getFont().deriveFont(Zoomable.clampSize(area.getFont().getSize2D() + steps)));
    }

    @Override
    public void resetZoom() {
        area.setFont(Theme.mono());
    }
}
