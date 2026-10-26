package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import java.nio.file.Path;

/**
 * Markdown rendered as comando shows it wherever a text is read rather than edited: an {@link HtmlPane} on the editors'
 * paper, padded, in a borderless scroll pane, its relative images read from the file's folder. An editor panel's
 * preview is one; the comparison window's two rendered sides are two.
 */
public final class RenderedMarkdown {

    private final HtmlPane pane = new HtmlPane();
    private final JScrollPane scroll = new JScrollPane(pane);

    public RenderedMarkdown() {
        pane.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD * 2, Theme.PAD, Theme.PAD * 2));
        scroll.setBorder(null);
    }

    public JComponent component() {
        return scroll;
    }

    public JScrollPane scroll() {
        return scroll;
    }

    public HtmlPane pane() {
        return pane;
    }

    /** Renders {@code markdown} from its top, its relative images read from {@code folder} (null: none are). */
    public void show(String markdown, Path folder) {
        show(markdown, folder, MarkdownHtml.Marks.NONE);
    }

    /** As {@link #show(String, Path)}, what {@code marks} names shaded as changed. */
    public void show(String markdown, Path folder, MarkdownHtml.Marks marks) {
        pane.setMarkdown(markdown, folder, marks);
        pane.setCaretPosition(0);
    }
}
