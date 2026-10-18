package dev.marregui.comando.ui.widgets.markdown;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Exportable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.nio.file.Files;

/** Rendered Markdown: READ- documents, ALPHA.md, Claude's notes. */
public final class MarkdownWidget implements Widget<ViewSpec.Markdown>, Copyable, Exportable {

    private final HtmlPane pane = new HtmlPane();
    private final JScrollPane scroll = new JScrollPane(pane);
    private String markdown = "";

    public MarkdownWidget() {
        pane.setBorder(BorderFactory.createEmptyBorder(Theme.PAD, Theme.PAD, Theme.PAD, Theme.PAD));
        scroll.setBorder(null);
        Capabilities.mark(scroll, this);
    }

    @Override
    public JComponent component() {
        return scroll;
    }

    /**
     * Shows the Markdown; a first render starts at the top. A render that replaces what is shown (a view refreshed in
     * place) keeps the reader where it was, or at the end when it was at the end, so a growing document can be followed.
     */
    @Override
    public void render(ViewSpec.Markdown view) {
        boolean first = markdown.isEmpty();
        JScrollBar bar = scroll.getVerticalScrollBar();
        boolean atEnd = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4 && bar.getValue() > 0;
        Point at = scroll.getViewport().getViewPosition();
        markdown = view.markdown();
        pane.setMarkdown(markdown);
        if (first) {
            pane.setCaretPosition(0);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            scroll.validate();
            if (atEnd) {
                bar.setValue(bar.getMaximum());
            } else {
                scroll.getViewport().setViewPosition(at);
            }
        });
    }

    @Override
    public String copyText() {
        String selected = pane.getSelectedText();
        return selected != null ? selected : markdown;
    }

    @Override
    public String exportName() {
        return "view.md";
    }

    @Override
    public Export prepare() {
        String text = markdown;
        return file -> Files.writeString(file, text);
    }
}
