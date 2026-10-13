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
import javax.swing.JScrollPane;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Override
    public void render(ViewSpec.Markdown view) {
        markdown = view.markdown();
        pane.setMarkdown(markdown);
        pane.setCaretPosition(0);
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
