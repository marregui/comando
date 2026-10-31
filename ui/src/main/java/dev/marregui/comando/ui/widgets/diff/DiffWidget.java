package dev.marregui.comando.ui.widgets.diff;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.text.DiffPane;
import dev.marregui.comando.ui.kit.text.TextSearch;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widget.capability.Searchable;

import javax.swing.JComponent;
import java.nio.file.Files;

/** A unified diff of two versions of a text. */
public final class DiffWidget implements Widget<ViewSpec.Diff>, Searchable, Copyable, Exportable {

    private final DiffPane pane = new DiffPane();
    private final TextSearch search = new TextSearch(pane.area());

    public DiffWidget() {
        Capabilities.mark(pane.component(), this);
    }

    @Override
    public JComponent component() {
        return pane.component();
    }

    @Override
    public void render(ViewSpec.Diff view) {
        pane.show(view.path(), view.before(), view.after());
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
        String s = pane.area().getSelectedText();
        return s != null ? s : pane.area().getText();
    }

    /** A selection is part of the text, so there is something to copy when the text is not empty. */
    @Override
    public boolean canCopy() {
        return pane.area().getDocument().getLength() > 0;
    }

    @Override
    public String exportName() {
        return "change.diff";
    }

    @Override
    public Export prepare() {
        String text = pane.area().getText();
        return file -> Files.writeString(file, text);
    }
}
