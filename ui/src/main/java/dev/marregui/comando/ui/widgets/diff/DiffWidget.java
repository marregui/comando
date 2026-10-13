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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    public String copyText() {
        String s = pane.area().getSelectedText();
        return s != null ? s : pane.area().getText();
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
