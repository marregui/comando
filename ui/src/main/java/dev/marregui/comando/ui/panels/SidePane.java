package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;

/** Views placed beside the editor: files Claude opened, diffs, plots. Hidden when it holds none. */
public final class SidePane {

    private final JTabbedPane tabs = named(new JTabbedPane(), "side");
    private final ViewTabs views;

    public SidePane(WidgetContext context, WidgetRegistry registry) {
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        views = new ViewTabs(tabs, 0, Placement.SIDE, registry, context);
    }

    public JComponent component() {
        return tabs;
    }

    /** The view whose tab holds {@code c}, or -1. */
    public long viewHolding(Component c) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (SwingUtilities.isDescendingFrom(c, tabs.getComponentAt(i))) {
                return views.viewAt(i);
            }
        }
        return -1;
    }

    public boolean hasViews() {
        return views.size() > 0;
    }

    /** Returns true when the pane holds views. */
    public boolean render(AppState s) {
        boolean changed = views.render(s.views());
        if (changed) {
            // the newest side view is shown
            ViewState newest = null;
            for (ViewState v : s.views()) {
                if (v.placement() == Placement.SIDE && (newest == null || v.id() > newest.id())) {
                    newest = v;
                }
            }
            if (newest != null) {
                int i = views.indexOf(newest.id());
                if (i >= 0) {
                    tabs.setSelectedIndex(i);
                }
            }
        }
        return views.size() > 0;
    }

    private static JTabbedPane named(JTabbedPane tabs, String name) {
        tabs.setName(name);
        return tabs;
    }
}
