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
     * Shows the Markdown at its top. A render of the same document grown or edited in place (a view refreshed while it
     * is followed: the same first {@link #SAME_START} characters) keeps the reader where it was, or at the end when it
     * was at the end; another document under the same view starts at its top.
     */
    @Override
    public void render(ViewSpec.Markdown view) {
        boolean first = !sameDocument(markdown, view.markdown());
        JScrollBar bar = scroll.getVerticalScrollBar();
        boolean atEnd = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4 && bar.getValue() > 0;
        Point at = scroll.getViewport().getViewPosition();
        markdown = view.markdown();
        pane.setMarkdown(markdown);
        if (first) {
            pane.setCaretPosition(0);
            scroll.getViewport().setViewPosition(new Point(0, 0)); // the caret's own scroll stops at the border
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

    static final int SAME_START = 200;

    private static boolean sameDocument(String before, String after) {
        if (before.isEmpty()) {
            return false;
        }
        int n = Math.min(SAME_START, Math.min(before.length(), after.length()));
        return before.regionMatches(0, after, 0, n);
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
