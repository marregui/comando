package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [ViewTabs] keeps a placement's views as tabs after the fixed ones, one widget per view.
/// - a view replaced in place by another type renders in the same tab, still selected, the old widget unmounted
class ViewTabsTest {

    /** A widget that remembers what it rendered and whether it was unmounted. */
    private static final class Recording<V extends ViewSpec> implements Widget<V> {
        final JLabel label = new JLabel();
        final List<V> rendered = new ArrayList<>();
        boolean unmounted;

        @Override
        public JComponent component() {
            return label;
        }

        @Override
        public void render(V view) {
            rendered.add(view);
        }

        @Override
        public void unmount() {
            unmounted = true;
        }
    }

    /// A markdown view titled Results replaced by a table view with the same title keeps its id; the tab shows the new
    /// table widget in the same place, still selected, and the markdown widget is unmounted. Rendering the same state
    /// twice creates no second widget.
    @Test
    void aViewReplacedInPlaceByAnotherTypeRendersInTheSameTab() throws Exception {
        Reducer reducer = new Reducer(new FakeClock());
        AppState first = reducer.apply(States.twoPanels(),
                new Intent.ShowView("work", "Results", new ViewSpec.Markdown("# first"), Placement.LOWER)).state();
        ViewSpec.Table table = new ViewSpec.Table(List.of("a"), List.of(List.of("1")));
        AppState second = reducer.apply(first, new Intent.ShowView("work", "Results", table, Placement.LOWER)).state();
        assertThat(second.views()).hasSize(1);
        assertThat(second.views().getFirst().id()).isEqualTo(first.views().getFirst().id());

        List<Recording<ViewSpec.Markdown>> markdowns = new ArrayList<>();
        List<Recording<ViewSpec.Table>> tables = new ArrayList<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<JTabbedPane> pane = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            WidgetRegistry registry = new WidgetRegistry();
            registry.register("markdown", ViewSpec.Markdown.class, c -> {
                Recording<ViewSpec.Markdown> w = new Recording<>();
                markdowns.add(w);
                return w;
            });
            registry.register("table", ViewSpec.Table.class, c -> {
                Recording<ViewSpec.Table> w = new Recording<>();
                tables.add(w);
                return w;
            });
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("fixed", new JLabel());
            pane.set(tabs);
            ViewTabs views = new ViewTabs(tabs, 1, Placement.LOWER, registry,
                    new WidgetContext(intent -> { }, new ActionRegistry()));
            try {
                views.render(first.views());
                tabs.setSelectedIndex(1);
                views.render(second.views());
                views.render(second.views());
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });

        assertThat(thrown.get()).isNull();
        assertThat(markdowns).hasSize(1);
        assertThat(markdowns.getFirst().unmounted).isTrue();
        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst().rendered).containsExactly(table);
        JTabbedPane tabs = pane.get();
        assertThat(tabs.getTabCount()).isEqualTo(2);
        assertThat(tabs.getComponentAt(1)).isSameAs(tables.getFirst().label);
        assertThat(tabs.getSelectedIndex()).isEqualTo(1);
    }
}
