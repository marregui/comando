package dev.marregui.comando.ui.widgets.table;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.widget.Widget;

import javax.swing.JComponent;

/** A table Claude placed: the grid over the view's rows, or the empty state when it has none. */
public final class TableViewWidget implements Widget<ViewSpec.Table> {

    private final GridTable grid = new GridTable();
    private final Overlay overlay = new Overlay(grid.component());

    @Override
    public JComponent component() {
        return overlay;
    }

    @Override
    public void render(ViewSpec.Table view) {
        grid.setSource(TableSource.of(view), "table.csv");
        if (view.rows().isEmpty()) {
            overlay.showEmpty("No rows");
        } else {
            overlay.showContent();
        }
    }
}
